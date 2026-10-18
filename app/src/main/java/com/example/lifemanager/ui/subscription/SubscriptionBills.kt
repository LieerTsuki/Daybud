package com.example.lifemanager.ui.subscription

import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.model.SubscriptionPayment
import com.example.lifemanager.domain.usecase.SubscriptionRules
import java.time.LocalDate
import java.time.YearMonth

internal data class SubscriptionBillEntry(val subscription: Subscription, val date: LocalDate)

internal data class SubscriptionBillMonth(
    val month: YearMonth,
    val entries: List<SubscriptionBillEntry>,
    val payments: List<SubscriptionPayment>,
    val forecastMinor: Long,
    val actualMinor: Long,
)

/** Derives only the requested month, using the original calendar anchors and exact minor units. */
internal fun subscriptionBillMonth(subscriptions: List<Subscription>, payments: List<SubscriptionPayment>, month: YearMonth): SubscriptionBillMonth {
    val entries = subscriptions.filter { it.currency == "CNY" }.flatMap { subscription ->
        SubscriptionRules.forecastOccurrences(subscription, month.atDay(1), month.atEndOfMonth())
            .map { SubscriptionBillEntry(subscription, it) }
    }.sortedWith(compareBy<SubscriptionBillEntry> { it.date }.thenBy { it.subscription.id })
    val actual = payments.filter { it.currency == "CNY" && YearMonth.from(it.paidAt) == month }
        .sortedWith(compareByDescending<SubscriptionPayment> { it.paidAt }.thenByDescending { it.id })
    return SubscriptionBillMonth(month, entries, actual,
        entries.fold(0L) { total, entry -> Math.addExact(total, entry.subscription.amountMinor) },
        actual.fold(0L) { total, payment -> Math.addExact(total, payment.amountMinor) })
}
