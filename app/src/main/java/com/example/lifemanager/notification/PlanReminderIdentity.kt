package com.example.lifemanager.notification

import com.example.lifemanager.domain.plan.PlanReminderToken
import com.example.lifemanager.domain.maintenance.DataGeneration
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate

/** Value protocol only, never authorization to deliver. Full data, not the hash, identifies an alarm. */
object PlanReminderIdentity {
    private const val PREFIX = "lifemanager://plan-reminder/v1/"
    fun data(token: PlanReminderToken): String =
        "$PREFIX${token.planId}/${token.generation.value}/${token.occurrenceDate.toEpochDay()}/${token.triggerAt.toEpochMilli()}"

    fun requestCode(token: PlanReminderToken): Int = (token.planId xor (token.planId ushr 32)).toInt()

    fun parse(data: String?): PlanReminderToken? {
        if (data == null || !data.startsWith(PREFIX)) return null
        val fields = data.removePrefix(PREFIX).split('/')
        if (fields.size != 4) return null
        val id = fields[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val generation = fields[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val date = fields[2].toLongOrNull() ?: return null
        val millis = fields[3].toLongOrNull() ?: return null
        val token = try {
            PlanReminderToken(id, DataGeneration(generation), LocalDate.ofEpochDay(date), Instant.ofEpochMilli(millis))
        } catch (_: DateTimeException) { return null }
        return token.takeIf { data(it) == data }
    }
}
