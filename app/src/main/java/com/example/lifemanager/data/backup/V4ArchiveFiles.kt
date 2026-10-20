package com.example.lifemanager.data.backup

import com.example.lifemanager.data.diary.*
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.diary.*
import java.io.*
import java.security.MessageDigest
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*

/** Streams full archives without loading video files into memory or trusting provider sizes. */
class V4ArchiveFiles internal constructor(private val streams:BackupStreamAccess,
    private val storage:FileDiaryStorage,private val legacy:VersionedBackupCodec,
    private val manifest:V4BackupManifest=V4BackupManifest(),
    private val container:V4ArchiveContainer=V4ArchiveContainer()):BackupArchive {
    private data class Lease(val document:V2BackupDocument,val pins:List<DiaryMediaPin>,var completion:DisposableHandle?=null)
    private val leases=mutableMapOf<String,Lease>()

    override suspend fun capture(snapshot:suspend ()->V2BackupPayload):BackupCapture = storage.withGate {
        val owned=ownV2Document(V2BackupDocument("1.2.0","2026-10-19T00:00:00Z",snapshot()),BackupLimits()).payload
        val pin=storage.pin(requireNotNull(owned.diary).media)
        object:BackupCapture { override val payload=owned;override fun close()=pin.close() }
    }

    override suspend fun writeVerified(location:BackupLocation,document:V2BackupDocument):V2BackupDocument {
        val context=currentCoroutineContext()
        val owned=ownV2Document(document.copy(previewToken=null),BackupLimits())
        val diary=requireNotNull(owned.payload.diary) { "完整备份缺少日记快照" }
        val pin=storage.withGate { storage.pin(diary.media) }
        try {
            context.ensureActive()
            val bytes=manifest.encode(owned)
            fileErrors {
                val output=streams.openWrite(location) ?: throw BackupFileException("无法打开输出文档")
                output.use { container.write(ActiveOutput(it,context.job),bytes,diary.media.map(::record)) { media ->
                    ActiveInput(storage.open(diary.media.single { it.id==media.id }),context.job)
                } }
            }
            context.ensureActive()
            var reopened:V2BackupDocument?=null
            fileErrors {
                val input=streams.openRead(location) ?: throw BackupFileException("无法重读输出文档")
                input.use { container.read(ActiveInput(it,context.job),{ raw ->
                    manifest.decode(raw).also { decoded -> reopened=decoded }.payload.diary!!.media.map(::record)
                }) { _,_ -> } }
            }
            checkBackup(reopened==owned,"输出不是本次完整v4备份，不能作为保护备份")
            context.ensureActive()
            return owned
        } finally { pin.close() }
    }

    override suspend fun preview(location:BackupLocation,legacyTodoZone:ZoneId):BackupImportResult {
        val owner=currentCoroutineContext().job
        val prepared=mutableListOf<DiaryPreparedMedia>()
        val promoted=mutableListOf<DiaryMediaPin>()
        var leased=false
        try {
            var decoded:V2BackupDocument?=null
            val historical=fileErrors {
                val raw=streams.openRead(location) ?: throw BackupFileException("无法打开来源文档")
                BufferedInputStream(ActiveInput(raw,owner)).use { input ->
                    input.mark(4);val header=ByteArray(4);var count=0
                    while(count<header.size) {
                        val n=input.read(header,count,header.size-count)
                        if(n<0)break
                        if(n==0) { val value=input.read();if(value<0)break;header[count++]=value.toByte() }
                        else count+=n
                    }
                    input.reset()
                    if(count==4 && header.contentEquals(byteArrayOf(0x50,0x4b,0x03,0x04))) {
                        container.read(input,{ bytes ->
                            manifest.decode(bytes).also { decoded=it }.payload.diary!!.media.map(::record)
                        }) { media,source ->
                            val metadata=decoded!!.payload.diary!!.media.single { it.id==media.id }
                            prepared += storage.prepareRestore(metadata,ActiveInput(source,owner))
                        }
                        null
                    } else legacy.decode(readLegacy(input,owner),legacyTodoZone)
                }
            }
            if(historical!=null)return historical.copy(document=ownV2Document(historical.document,BackupLimits()))
            val document=requireNotNull(decoded)
            return storage.withGate {
                prepared.forEach { owner.ensureActive();promoted+=storage.promotePrepared(it) }
                owner.ensureActive()
                val token=UUID.randomUUID().toString()
                val lease=Lease(document,promoted.toList())
                synchronized(leases) { leases[token]=lease }
                // Also release if cancellation discards this return value at a dispatcher bridge.
                lease.completion=owner.invokeOnCompletion { cause -> if(cause!=null)release(token) }
                leased=true
                BackupImportResult(document.copy(previewToken=token),BackupSourceFormat.V4)
            }
        } finally {
            prepared.forEach { it.close() }
            if(!leased)promoted.forEach { it.close() }
        }
    }

    override suspend fun validateDocument(document:V2BackupDocument) {
        if(document.payload.diary==null) { legacy.encode(document);return }
        val token=document.previewToken ?: throw BackupValidationException("请从文件重新预览完整备份")
        val lease=synchronized(leases) { leases[token] } ?: throw BackupValidationException("备份预览已失效")
        checkBackup(lease.document==document.copy(previewToken=null),"备份预览内容已变化")
        manifest.encode(lease.document)
        val owner=currentCoroutineContext().job
        // Files have immutable identities and remain pinned until this operation completes.
        for(media in document.payload.diary.media) {
            val digest=MessageDigest.getInstance("SHA-256");var size=0L
            fileErrors { storage.open(media).use { source ->
                val input=ActiveInput(source,owner);val buffer=ByteArray(64*1024)
                while(true) { val count=input.read(buffer);if(count<0)break
                    checkBackup(count.toLong()<=media.bytes-size,"预览媒体大小已变化")
                    size+=count;digest.update(buffer,0,count)
                }
            } }
            checkBackup(size==media.bytes && digest.digest().joinToString("") { "%02x".format(it) }==media.sha256,"预览媒体摘要已变化")
        }
    }
    override fun releasePreview(document:V2BackupDocument) { document.previewToken?.let(::release) }
    private fun release(token:String) {
        val lease=synchronized(leases) { leases.remove(token) } ?: return
        lease.completion?.dispose();lease.pins.forEach { it.close() }
    }
    private fun record(media:DiaryMedia)=V4MediaRecord(media.id,media.bytes,media.sha256)
    private fun readLegacy(input:InputStream,owner:Job):ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(64*1024);val maximum=BackupLimits().maxBytes
        while(true) { owner.ensureActive();val count=input.read(buffer);if(count<0)break
            checkBackup(count<=maximum-out.size(),"旧备份超过文件大小限制");out.write(buffer,0,count)
        }
        return out.toByteArray()
    }
    private inline fun <T> fileErrors(block:()->T):T = try { block() }
        catch(error:CancellationException) { throw error }
        catch(error:BackupValidationException) { throw error }
        catch(error:BackupFileException) { throw error }
        catch(error:IOException) { throw BackupFileException("文档读写未完成",error) }
        catch(error:SecurityException) { throw BackupFileException("文档读写权限不足",error) }
        catch(error:IllegalArgumentException) { throw BackupValidationException("备份内容无效",error) }
        catch(error:IllegalStateException) { throw BackupValidationException("备份内容不完整",error) }

    private class ActiveInput(input:InputStream,private val owner:Job):FilterInputStream(input) {
        override fun read():Int { owner.ensureActive();return `in`.read() }
        override fun read(bytes:ByteArray,offset:Int,length:Int):Int { owner.ensureActive();return `in`.read(bytes,offset,length) }
    }
    private class ActiveOutput(output:OutputStream,private val owner:Job):FilterOutputStream(output) {
        override fun write(value:Int) { owner.ensureActive();out.write(value) }
        override fun write(bytes:ByteArray,offset:Int,length:Int) { owner.ensureActive();out.write(bytes,offset,length) }
    }
}
