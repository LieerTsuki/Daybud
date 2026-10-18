package com.example.lifemanager.ui.subscription

import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.model.SubscriptionPayment
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.*

class SubscriptionBillsTest {
    private fun subscription(id: Long, anchor: String, amount: Long = 1500, cycle: BillingCycle = BillingCycle.MONTHLY) = Subscription(
        id = id, appName = "订阅$id", amountMinor = amount, billingCycle = cycle,
        nextBillingDate = LocalDate.parse(anchor), startDate = LocalDate.of(2026, 1, 1), createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)

    @Test fun requestedMonthUsesDateOrderAndStableIdsWithoutCombiningForeignMoneyOrActualPayments() {
        val list = listOf(subscription(4, "2026-10-22", 2000), subscription(2, "2026-10-02", 3500),
            subscription(1, "2026-10-02", 1200), subscription(5, "2026-10-01").copy(currency = "USD"))
        val payments = listOf(SubscriptionPayment(1, 2, 3000, "CNY", LocalDate.of(2026, 10, 3)),
            SubscriptionPayment(2, 4, 999, "USD", LocalDate.of(2026, 10, 5)),
            SubscriptionPayment(3, 2, 3500, "CNY", LocalDate.of(2026, 9, 3)))
        val bill = subscriptionBillMonth(list, payments, YearMonth.of(2026, 10))
        assertEquals(listOf(1L, 2L, 4L), bill.entries.map { it.subscription.id })
        assertEquals(listOf("2026-10-02", "2026-10-02", "2026-10-22"), bill.entries.map { it.date.toString() })
        assertEquals(6700L, bill.forecastMinor)
        assertEquals(3000L, bill.actualMinor)
        assertEquals(listOf(1L), bill.payments.map { it.id })
    }

    @Test fun weeklySubscriptionHasEveryChargeInSelectedMonthAndCanceledHistoryStaysActualOnly() {
        val week = subscription(1, "2026-10-01", 1000, BillingCycle.WEEKLY)
        val cancelled = subscription(2, "2026-10-01", 4000).copy(isActive = false, cancelDate = LocalDate.of(2026, 10, 2))
        val payment = SubscriptionPayment(8, 2, 4000, "CNY", LocalDate.of(2026, 10, 1))
        val bill = subscriptionBillMonth(listOf(cancelled, week), listOf(payment), YearMonth.of(2026, 10))
        assertEquals(listOf(1, 8, 15, 22, 29), bill.entries.map { it.date.dayOfMonth })
        assertEquals(5000L, bill.forecastMinor)
        assertEquals(4000L, bill.actualMinor)
    }

    @Test fun monthSwitchRetainsOriginalMonthEndAndLeapAnchors() {
        val monthly = subscription(1, "2026-01-31")
        assertEquals(LocalDate.of(2026, 2, 28), subscriptionBillMonth(listOf(monthly), emptyList(), YearMonth.of(2026, 2)).entries.single().date)
        assertEquals(LocalDate.of(2026, 3, 31), subscriptionBillMonth(listOf(monthly), emptyList(), YearMonth.of(2026, 3)).entries.single().date)
        val yearly = subscription(2, "2024-02-29", cycle = BillingCycle.YEARLY).copy(startDate = LocalDate.of(2024, 1, 1))
        assertEquals(LocalDate.of(2028, 2, 29), subscriptionBillMonth(listOf(yearly), emptyList(), YearMonth.of(2028, 2)).entries.single().date)
    }

    @Test fun emptyAndOutOfMonthRecordsProduceZeroAndOverflowIsNeverPresentedAsNegativeMoney() {
        val empty = subscriptionBillMonth(emptyList(), emptyList(), YearMonth.of(2026, 10))
        assertEquals(0L, empty.forecastMinor)
        assertEquals(0L, empty.actualMinor)
        assertFailsWith<ArithmeticException> {
            subscriptionBillMonth(listOf(subscription(1, "2026-10-01", Long.MAX_VALUE), subscription(2, "2026-10-02", 1)), emptyList(), YearMonth.of(2026, 10))
        }
        assertEquals("¥15.00", moneyText(1500))
        assertEquals("JPY 1500", moneyText(1500, "JPY"))
    }
}
