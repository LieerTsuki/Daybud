package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.diary.*
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.*

/** Format4 manifests contain the independently checked frozen V3 object and all schema8 diary data. */
class V4BackupManifest(
    private val legacy: VersionedBackupCodec = VersionedBackupJsonCodec(),
    private val limits: BackupLimits = BackupLimits(),
) {
    fun encode(document: V2BackupDocument): ByteArray = checked {
        val diary = document.payload.diary ?: throw BackupValidationException("完整备份缺少日记数据")
        validate(document,diary)
        val legacyBytes = legacy.encode(document.copy(payload=document.payload.copy(diary=null),previewToken=null))
        val old = StrictBackupJson(utf8(legacyBytes),limits,BackupJsonContract.V3).parse()
        val payload = buildJsonObject { put("legacy",old); put("diary",diaryJson(diary)) }
        val root = buildJsonObject {
            put("format","life-manager-backup"); put("formatVersion",4); put("schemaVersion",8)
            put("appVersion",document.appVersion); put("exportedAt",document.exportedAt)
            put("payload",payload); put("payloadSha256",CanonicalBackupJson.sha256(payload))
        }
        CanonicalBackupJson.encode(root,CanonicalBackupJson.byteCount(root,limits.maxBytes))
    }

    fun decode(bytes: ByteArray): V2BackupDocument = checked {
        val root = StrictBackupJson(utf8(bytes),limits,BackupJsonContract.V4).parse().objectWithKeys(setOf("format","formatVersion","schemaVersion","appVersion","exportedAt","payload","payloadSha256"))
        checkBackup(root.getValue("format").textValue() == "life-manager-backup" && root.getValue("formatVersion").integerValue() == 4L && root.getValue("schemaVersion").integerValue() == 8L,"不支持的完整备份格式")
        val payload = root.getValue("payload").objectWithKeys(setOf("legacy","diary"))
        val hash = root.getValue("payloadSha256").textValue()
        checkBackup(hash.matches(Regex("[0-9a-f]{64}")) && MessageDigest.isEqual(hash.toByteArray(Charsets.US_ASCII),CanonicalBackupJson.sha256(payload).toByteArray(Charsets.US_ASCII)),"备份清单校验不通过")
        val oldJson = payload.getValue("legacy")
        val oldRoot = oldJson.jsonObject
        checkBackup(oldRoot["formatVersion"]?.integerValue() == 3L && oldRoot["schemaVersion"]?.integerValue() == 7L,"完整备份的历史数据格式无效")
        val old = legacy.decode(CanonicalBackupJson.encode(oldJson,CanonicalBackupJson.byteCount(oldJson,limits.maxBytes)),ZoneId.systemDefault()).document
        checkBackup(old.appVersion == root.getValue("appVersion").textValue() && old.exportedAt == root.getValue("exportedAt").textValue(),"清单与历史数据元信息不一致")
        val diary = readDiary(payload.getValue("diary"))
        old.copy(payload=old.payload.copy(diary=diary),previewToken=null).also { validate(it,diary) }
    }

    private fun validate(document: V2BackupDocument, diary: DiaryBackupSnapshot) {
        DiaryBackupValidator.validate(diary)
        var rows = document.payload.tables.values.sumOf { it.size.toLong() }
        rows += diary.entries.size + diary.drafts.size.toLong() + diary.media.size + diary.reviews.size
        rows += diary.entries.sumOf { it.media.size.toLong() } + diary.drafts.sumOf { it.media.size.toLong() } + diary.reviews.sumOf { it.sources.size.toLong() }
        checkBackup(rows <= limits.maxRows,"完整备份超过行数限制")
    }

    private fun diaryJson(snapshot: DiaryBackupSnapshot): JsonObject = buildJsonObject {
        put("entrySequence",snapshot.entrySequence)
        put("entries",JsonArray(snapshot.entries.map { value -> buildJsonObject {
            put("id",value.id); common(value.date,value.title,value.body,value.mood,value.createdAt,value.updatedAt,value.revision,value.media)
        } }))
        put("drafts",JsonArray(snapshot.drafts.map { value -> buildJsonObject {
            put("id",value.id); put("entryId",value.entryId?.let(::JsonPrimitive) ?: JsonNull); put("baseRevision",value.baseRevision?.let(::JsonPrimitive) ?: JsonNull)
            common(value.date,value.title,value.body,value.mood,value.createdAt,value.updatedAt,value.revision,value.media)
        } }))
        put("media",JsonArray(snapshot.media.map(::mediumJson)))
        put("reviews",JsonArray(snapshot.reviews.map { value -> buildJsonObject {
            put("id",value.id); put("from",value.from.toEpochDay()); put("to",value.to.toEpochDay()); put("createdAt",value.createdAt.toEpochMilli())
            put("needsUpdate",value.needsUpdate); put("provider",value.provider); put("model",value.model); put("inputHash",value.inputHash)
            put("sections",JsonArray(value.sections.map { section -> buildJsonObject {
                put("title",section.title); put("text",section.text); put("sourceIds",JsonArray(section.sourceIds.map(::JsonPrimitive)))
            } }))
            put("sources",JsonArray(value.sources.map { source -> buildJsonObject { put("entryId",source.entryId); put("revision",source.revision) } }))
        } }))
    }
    private fun JsonObjectBuilder.common(date: LocalDate,title: String,body: String,mood: DiaryMood?,createdAt: Instant,updatedAt: Instant,revision: Long,media: List<DiaryMedia>) {
        put("date",date.toEpochDay()); put("title",title); put("body",body); put("mood",mood?.let { JsonPrimitive(it.name) } ?: JsonNull)
        put("createdAt",createdAt.toEpochMilli()); put("updatedAt",updatedAt.toEpochMilli()); put("revision",revision); put("media",JsonArray(media.map(::mediumJson)))
    }
    private fun mediumJson(value: DiaryMedia): JsonObject = buildJsonObject {
        put("id",value.id); put("mimeType",value.mimeType); put("bytes",value.bytes); put("sha256",value.sha256); put("position",value.position)
    }
    private fun readDiary(element: JsonElement): DiaryBackupSnapshot {
        val value=element.objectWithKeys(setOf("entries","drafts","media","reviews","entrySequence"))
        val common=setOf("date","title","body","mood","createdAt","updatedAt","revision","media")
        fun JsonObject.mood(): DiaryMood? = getValue("mood").let { if(it==JsonNull) null else DiaryMood.valueOf(it.textValue()) }
        fun JsonObject.day(key:String)=LocalDate.ofEpochDay(getValue(key).integerValue())
        fun JsonObject.time(key:String)=Instant.ofEpochMilli(getValue(key).integerValue())
        fun JsonObject.number(key:String)=getValue(key).integerValue()
        fun JsonObject.text(key:String)=getValue(key).textValue()
        fun JsonObject.media()=getValue("media").arrayValue().map(::readMedium)
        val entries=value.getValue("entries").arrayValue().map { item ->
            val row=item.objectWithKeys(common+"id")
            DiaryEntry(row.number("id"),row.day("date"),row.text("title"),row.text("body"),row.mood(),row.media(),row.time("createdAt"),row.time("updatedAt"),row.number("revision"))
        }
        val drafts=value.getValue("drafts").arrayValue().map { item ->
            val row=item.objectWithKeys(common+setOf("id","entryId","baseRevision"))
            DiaryDraft(row.text("id"),row.getValue("entryId").let { if(it==JsonNull) null else it.integerValue() },row.getValue("baseRevision").let { if(it==JsonNull) null else it.integerValue() },row.day("date"),row.text("title"),row.text("body"),row.mood(),row.media(),row.time("createdAt"),row.time("updatedAt"),row.number("revision"))
        }
        val reviews=value.getValue("reviews").arrayValue().map { item ->
            val row=item.objectWithKeys(setOf("id","from","to","createdAt","sections","sources","needsUpdate","provider","model","inputHash"))
            val sections=row.getValue("sections").arrayValue().map { section ->
                val part=section.objectWithKeys(setOf("title","text","sourceIds"))
                DiaryReviewSection(part.text("title"),part.text("text"),part.getValue("sourceIds").arrayValue().map { it.integerValue() })
            }
            val sources=row.getValue("sources").arrayValue().map { source -> val part=source.objectWithKeys(setOf("entryId","revision")); DiarySourceRevision(part.number("entryId"),part.number("revision")) }
            DiaryReview(row.text("id"),row.day("from"),row.day("to"),row.time("createdAt"),sections,sources,row.getValue("needsUpdate").flagValue(),row.text("provider"),row.text("model"),row.text("inputHash"))
        }
        return DiaryBackupSnapshot(entries,drafts,value.getValue("media").arrayValue().map(::readMedium),reviews,value.getValue("entrySequence").integerValue())
    }
    private fun readMedium(element:JsonElement): DiaryMedia {
        val value=element.objectWithKeys(setOf("id","mimeType","bytes","sha256","position"))
        val position=value.getValue("position").integerValue()
        checkBackup(position in 0..Int.MAX_VALUE,"附件顺序无效")
        return DiaryMedia(value.getValue("id").textValue(),value.getValue("mimeType").textValue(),value.getValue("bytes").integerValue(),value.getValue("sha256").textValue(),position.toInt())
    }
    private fun utf8(bytes:ByteArray):String {
        checkBackup(bytes.size <= limits.maxBytes,"备份清单超过字节限制")
        return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch(error:CharacterCodingException) { throw BackupValidationException("备份清单不是有效UTF-8",error) }
    }
    private inline fun <T> checked(block:()->T):T = try { block() } catch(error:BackupValidationException) { throw error }
        catch(error:IllegalArgumentException) { throw BackupValidationException("日记备份字段无效",error) }
        catch(error:IllegalStateException) { throw BackupValidationException("日记备份结构无效",error) }
}
