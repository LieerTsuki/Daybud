package com.example.lifemanager.data.diary

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import com.example.lifemanager.domain.diary.DiaryMedia
import com.example.lifemanager.domain.diary.DiaryMediaAccess
import com.example.lifemanager.domain.diary.DiaryImportedAttachment
import com.example.lifemanager.platform.media.decodeDiaryImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidDiaryMediaAccess(context:Context,private val storage:FileDiaryStorage):DiaryMediaAccess {
    private val resolver=context.applicationContext.contentResolver
    override suspend fun importSource(source:String,remainingBytes:Long):DiaryImportedAttachment {
        var allocated:com.example.lifemanager.data.diary.DiaryImportedMedia?=null
        return try { withContext(Dispatchers.IO) {
        val uri=Uri.parse(source)
        val mime=resolver.getType(uri) ?: error("无法识别附件类型")
        require(mime.startsWith("image/") || mime.startsWith("video/")) { "请选择照片或视频" }
        val stream=resolver.openInputStream(uri) ?: error("附件无法读取")
        val imported=storage.importPinned(stream,mime,remainingBytes).also { allocated=it }
        try {
            val file=storage.file(imported.media)
            if(mime.startsWith("image/")) {
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeFile(file.absolutePath,bounds)
                require(bounds.outWidth>0 && bounds.outHeight>0) { "设备不支持这张图片" }
                val preview=decodeDiaryImage(file,128) ?: error("图片损坏或设备不支持")
                preview.recycle()
            } else {
                val extractor=MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    val format=(0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true } ?: error("视频无法读取")
                    require(MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)!=null) { "设备不支持这段视频" }
                } finally { extractor.release() }
                val retriever=MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)=="yes") { "视频损坏或设备不支持" }
                    require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()!=null) { "视频无法读取" }
                } finally { retriever.release() }
            }
            DiaryImportedAttachment(imported.media,imported.pin)
        } catch(error:Throwable) { imported.pin.close();throw error }
        } } catch(error:Throwable) { allocated?.pin?.close();throw error }
    }
    override fun file(media:DiaryMedia)=storage.file(media)
}

