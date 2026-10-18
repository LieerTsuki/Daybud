package com.example.lifemanager.notification

import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.usecase.SubscriptionRules
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class SubscriptionReminderOccurrence(val dueDate: LocalDate, val triggerAt: Instant)

object SubscriptionReminderRules {
    // Include retired offsets only so their operational alarms/cards can be identified and removed.
    // Persisted reminder preferences remain untouched for strict historical backup compatibility.
    val supportedDays: Set<Int> = setOf(0, 1, 3, 7)
    const val DUE_DAY = 0
    private val reminderTime = LocalTime.of(9, 0)

    fun nextReminder(
        subscription: Subscription,
        daysBefore: Int,
        now: Instant,
        zone: ZoneId,
    ): SubscriptionReminderOccurrence? {
        if (daysBefore != DUE_DAY || !subscription.isActive) return null
        val localNow = now.atZone(zone)
        // A fresh schedule at or after nine must seek the next cycle instead of replaying today.
        val firstReminderDate = if (localNow.toLocalTime().isBefore(reminderTime)) {
            localNow.toLocalDate()
        } else {
            localNow.toLocalDate().plusDays(1)
        }
        val dueDate = SubscriptionRules.effectiveNextBillingDate(
            subscription, firstReminderDate.plusDays(daysBefore.toLong()),
        ) ?: return null
        val triggerAt = dueDate.minusDays(daysBefore.toLong()).atTime(reminderTime).atZone(zone).toInstant()
        return SubscriptionReminderOccurrence(dueDate, triggerAt).takeIf { it.triggerAt > now }
    }

    fun matchesCurrentReminder(
        subscription: Subscription?,
        selectedDays: Set<Int>,
        daysBefore: Int,
        dueDate: LocalDate,
        now: Instant,
        zone: ZoneId,
    ): Boolean {
        // Automatic due-day reminders apply even when the historical preference set is empty.
        // A queued advance-reminder broadcast from an older app must never post after upgrade.
        if (subscription == null || daysBefore != DUE_DAY) return false
        // Permit inexact delivery later on the same local day; reject historical/early payloads.
        val todayStart = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val occurrence = nextReminder(subscription, daysBefore, todayStart.minusNanos(1), zone) ?: return false
        return occurrence.dueDate == dueDate && occurrence.triggerAt <= now &&
            occurrence.triggerAt.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
    }

    fun shouldPreservePending(
        subscription: Subscription,
        selectedDays: Set<Int>,
        daysBefore: Int,
        pending: SubscriptionReminderOccurrence?,
        now: Instant,
        zone: ZoneId,
    ): Boolean {
        if (pending == null || !matchesCurrentReminder(
                subscription, selectedDays, daysBefore, pending.dueDate, now, zone,
            )
        ) return false
        // Preserve only an existing, undelivered alarm at today's current local 09:00.
        // A timezone change can leave the same due-date identity pointing at a different instant.
        return pending.triggerAt == pending.dueDate.minusDays(daysBefore.toLong())
            .atTime(reminderTime).atZone(zone).toInstant()
    }
}
