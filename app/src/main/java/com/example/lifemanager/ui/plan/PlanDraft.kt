package com.example.lifemanager.ui.plan

import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.plan.Plan
import com.example.lifemanager.domain.plan.PlanTimeMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import com.example.lifemanager.domain.plan.PlanRules

enum class PlanField { TITLE, DESCRIPTION, ZONE, DUE_AT, DUE_DATE, START_AT, END_AT,
    ALL_DAY_START, ALL_DAY_END, LOCATION, PARTICIPANTS, REMINDER_MINUTES, REPEAT_INTERVAL, REPEAT_END, COLOR, NEW_TAG }

/** UI text stays a draft until explicit strict validation; base retains untouched historical values. */
data class PlanDraft(
    val base: Plan,
    val title: String = base.title,
    val description: String = base.description.orEmpty(),
    val timeZone: String = base.timeZone,
    val mode: PlanTimeMode = base.timeMode,
    val dateOnly: Boolean = base.dueDate != null,
    val dueAt: String = instantText(base.dueAt, base.timeZone),
    val dueDate: String = base.dueDate?.toString().orEmpty(),
    val startAt: String = instantText(base.startAt, base.timeZone),
    val endAt: String = instantText(base.endAt, base.timeZone),
    val allDayStart: String = base.allDayStartDate?.toString().orEmpty(),
    val allDayEnd: String = base.allDayEndDate?.toString().orEmpty(),
    val location: String = base.location.orEmpty(),
    val participants: String = base.participants.orEmpty(),
    val color: String = "%08X".format(base.color),
    val priority: TodoPriority = base.priority,
    val tags: List<String> = base.tagNames.toList(),
    val newTag: String = "",
    val repeatRule: ScheduleRepeatRule = base.repeatRule,
    val repeatInterval: String = base.repeatInterval.toString(),
    val repeatDays: Set<DayOfWeek> = base.repeatDaysOfWeek.toSet(),
    val repeatEnd: String = base.repeatEndDate?.toString().orEmpty(),
    val reminderEnabled: Boolean = base.reminderEnabled,
    val reminderMinutes: String = base.reminderMinutes?.toString().orEmpty(),
)

internal fun instantText(value: Instant?, zone: String): String = value?.atZone(ZoneId.of(zone))?.toOffsetDateTime()?.toString().orEmpty()

object PlanDraftRules {
    fun build(draft: PlanDraft): Plan {
        require(draft.title.isNotBlank()) { "标题不能为空" }
        require(draft.timeZone in ZoneId.getAvailableZoneIds()) { "请选择有效 IANA 时区，历史偏移值不能用于新编辑" }
        val base=draft.base;val zone=ZoneId.of(draft.timeZone)
        fun time(text:String,old:Instant?):Instant? {
            if(text==instantText(old,base.timeZone)) return old
            if(text.isBlank()) return null
            val normalized=text.trim().replace(' ','T')
            val instant=try {
                val offset=OffsetDateTime.parse(normalized)
                require(offset.offset in zone.rules.getValidOffsets(offset.toLocalDateTime())) { "时间偏移不属于所选时区" }
                offset.toInstant()
            } catch(error:java.time.format.DateTimeParseException) {
                val local=LocalDateTime.parse(normalized)
                val offsets=zone.rules.getValidOffsets(local)
                require(offsets.size==1) { "该本地时间不存在或有两个偏移，请输入有效的带偏移时间" }
                local.toInstant(offsets.single())
            }
            require(instant.nano%1_000_000==0) { "时间精度最多支持毫秒" }
            instant.toEpochMilli() // Explicitly reject values outside Room's signed Long representation.
            return instant
        }
        fun date(text:String):LocalDate?=text.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it.trim()) }
        fun optional(text:String,old:String?):String?=if(text==old.orEmpty())old else text
        val minute=if(draft.reminderMinutes.isBlank())null else requireNotNull(draft.reminderMinutes.toIntOrNull()) { "提前分钟必须为整数" }
        val interval=requireNotNull(draft.repeatInterval.toIntOrNull()) { "重复间隔必须为整数" }
        val hex=draft.color.removePrefix("#")
        require(hex.length in listOf(6,8) && hex.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) { "颜色须为 RRGGBB 或 AARRGGBB" }
        val color=(hex.toLong(16) or if(hex.length==6)0xFF000000L else 0L).toInt()
        val dueAt=if(draft.mode==PlanTimeMode.DEADLINE && draft.dateOnly)null else time(draft.dueAt,base.dueAt)
        val dueDate=if(draft.mode==PlanTimeMode.DEADLINE && !draft.dateOnly)null else date(draft.dueDate)
        val plan=base.copy(title=draft.title,description=optional(draft.description,base.description),timeZone=draft.timeZone,
            timeMode=draft.mode,dueAt=dueAt,dueDate=dueDate,startAt=time(draft.startAt,base.startAt),endAt=time(draft.endAt,base.endAt),
            allDayStartDate=date(draft.allDayStart),allDayEndDate=date(draft.allDayEnd),location=optional(draft.location,base.location),
            participants=optional(draft.participants,base.participants),color=color,priority=draft.priority,
            tagNames=(draft.tags+draft.newTag.trim().takeIf { it.isNotEmpty() }.let { if(it==null)emptyList()else listOf(it) }).distinct(),
            repeatRule=draft.repeatRule,repeatInterval=interval,repeatDaysOfWeek=draft.repeatDays.toSet(),repeatEndDate=date(draft.repeatEnd),
            reminderEnabled=draft.reminderEnabled,reminderMinutes=minute)
        require(PlanRules.validate(plan)==null) { "有效日期/区间、重复间隔或提前分钟无效" }
        return plan
    }
    fun edit(draft: PlanDraft, field: PlanField, value: String): PlanDraft = when(field) {
        PlanField.TITLE->draft.copy(title=value)
        PlanField.DESCRIPTION->draft.copy(description=value)
        PlanField.ZONE->draft.copy(timeZone=value)
        PlanField.DUE_AT->draft.copy(dueAt=value)
        PlanField.DUE_DATE->draft.copy(dueDate=value)
        PlanField.START_AT->draft.copy(startAt=value)
        PlanField.END_AT->draft.copy(endAt=value)
        PlanField.ALL_DAY_START->draft.copy(allDayStart=value)
        PlanField.ALL_DAY_END->draft.copy(allDayEnd=value)
        PlanField.LOCATION->draft.copy(location=value)
        PlanField.PARTICIPANTS->draft.copy(participants=value)
        PlanField.REMINDER_MINUTES->draft.copy(reminderMinutes=value)
        PlanField.REPEAT_INTERVAL->draft.copy(repeatInterval=value)
        PlanField.REPEAT_END->draft.copy(repeatEnd=value)
        PlanField.COLOR->draft.copy(color=value)
        PlanField.NEW_TAG->draft.copy(newTag=value)
    }
}
