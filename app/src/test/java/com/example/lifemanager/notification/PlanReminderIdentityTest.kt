package com.example.lifemanager.notification

import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.plan.*
import java.time.Instant
import java.time.LocalDate
import org.junit.Test
import kotlin.test.*

class PlanReminderIdentityTest {
    private val token = PlanReminderToken(17, DataGeneration(4), LocalDate.of(2026, 1, 1), Instant.parse("2026-01-01T09:45:00Z"))

    @Test fun `canonical identity roundtrips independently derived literal`() {
        val literal = "lifemanager://plan-reminder/v1/17/4/20454/1767260700000"
        assertEquals(literal, PlanReminderIdentity.data(token))
        assertEquals(token, PlanReminderIdentity.parse(literal))
    }
    @Test fun `request code collision does not collapse full Long URI identity`() {
        val first = token.copy(planId = 1)
        val second = token.copy(planId = 4_294_967_296)
        assertEquals(PlanReminderIdentity.requestCode(first), PlanReminderIdentity.requestCode(second))
        assertNotEquals(PlanReminderIdentity.data(first), PlanReminderIdentity.data(second))
        assertEquals(second, PlanReminderIdentity.parse(PlanReminderIdentity.data(second)))
    }
    @Test fun `generation occurrence date and trigger each change identity`() {
        val alternatives = listOf(token, token.copy(generation = DataGeneration(5)),
            token.copy(occurrenceDate = LocalDate.of(2026, 1, 2)), token.copy(triggerAt = token.triggerAt.plusMillis(1)))
        assertEquals(4, alternatives.map(PlanReminderIdentity::data).toSet().size)
    }
    @Test fun `maximum identity generation and signed millisecond extremes preserve precision`() {
        for (millis in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val value = token.copy(planId = Long.MAX_VALUE, generation = DataGeneration(Long.MAX_VALUE), triggerAt = Instant.ofEpochMilli(millis))
            assertEquals(value, PlanReminderIdentity.parse(PlanReminderIdentity.data(value)))
        }
    }
    @Test fun `entire representable local date range is not narrowed to milliseconds`() {
        for (date in listOf(LocalDate.MIN, LocalDate.MAX)) {
            val value = token.copy(occurrenceDate = date)
            assertEquals(value, PlanReminderIdentity.parse(PlanReminderIdentity.data(value)))
        }
    }
    @Test fun `old namespaces missing identity and unknown protocols are rejected`() {
        for (data in listOf(null, "", "lifemanager://schedule-reminder/17", "lifemanager://todo-reminder/17",
            "lifemanager://subscription-reminder/17/1/2026-01-01", "lifemanager://plan-reminder/v2/17/4/20454/1767260700000",
            "lifemanager://plan-reminder/v1/17/4/20454")) assertNull(PlanReminderIdentity.parse(data), data)
    }
    @Test fun `noncanonical metadata cannot alias canonical PendingIntent identity`() {
        val prefix = "lifemanager://plan-reminder/v1/"
        for (suffix in listOf("017/4/20454/1767260700000", "+17/4/20454/1767260700000", "17/04/20454/1767260700000",
            "17/4/020454/1767260700000", "17/4/20454/1767260700000/", "17/4/20454/1767260700000?x=1",
            "17/4/20454/1767260700000#x", "17/4/20454/1767260700000 ", "17/4/20454/1.0", "17/4/20454/%31",
            "17/-0/20454/1", "17/4/-0/1", "17/4/20454/-0", "17/4/20454/01")) {
            assertNull(PlanReminderIdentity.parse(prefix + suffix), suffix)
        }
    }
    @Test fun `negative zero overflow and malformed metadata are rejected`() {
        val prefix = "lifemanager://plan-reminder/v1/"
        for (suffix in listOf("0/4/20454/1", "-1/4/20454/1", "17/-1/20454/1", "9223372036854775808/4/20454/1",
            "17/4/9223372036854775807/1", "17/4/20454/9223372036854775808", "17/four/20454/1", "17//20454/1")) {
            assertNull(PlanReminderIdentity.parse(prefix + suffix), suffix)
        }
    }
    @Test fun `token cannot silently round submillisecond trigger`() {
        assertFailsWith<IllegalArgumentException> { token.copy(triggerAt = token.triggerAt.plusNanos(1)) }
        assertFailsWith<ArithmeticException> { token.copy(triggerAt = Instant.MAX) }
        assertFailsWith<IllegalArgumentException> { token.copy(planId = 0) }
        assertFailsWith<IllegalArgumentException> { token.copy(planId = -1) }
    }
    @Test fun `candidate factory carries occurrence and source generation without recapture`() {
        val reminder = PlanReminder(PlanOccurrence(17, LocalDate.of(2026, 1, 1),
            PlanOccurrenceTime.DeadlineInstant(Instant.parse("2026-01-01T10:00:00Z"))), token.triggerAt)
        assertEquals(token, PlanReminderToken.from(reminder, DataGeneration(4)))
    }
}
