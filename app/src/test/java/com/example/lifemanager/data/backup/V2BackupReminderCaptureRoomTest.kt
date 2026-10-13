package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.plan.PlanReminderToken
import java.time.Instant
import java.time.LocalDate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class V2BackupReminderCaptureRoomTest {
    private val f = V2BackupRoomFixtures
    private val card = PlanReminderToken(17, DataGeneration(3), LocalDate.ofEpochDay(20999), Instant.ofEpochMilli(1000))
    @Test fun `captures signed sources all subscriptions alarm B and separate card A without writing state`() = f.withDatabase { db ->
        f.seed(db); f.inventory(db)
        val capture = capture(db, BackupPlanNotificationSource { assertFalse(db.inTransaction()); listOf(card) })
        val actual = capture.snapshot()
        assertEquals(listOf(Long.MIN_VALUE), actual.todoIds); assertEquals(listOf(Long.MAX_VALUE), actual.scheduleIds)
        assertEquals(listOf(8L), actual.subscriptionIds)
        assertEquals(listOf(PlanReminderToken(17, DataGeneration(4), LocalDate.ofEpochDay(21000), Instant.ofEpochMilli(Long.MAX_VALUE))), actual.planAlarmTokens)
        assertEquals(listOf(card), actual.planNotificationTokens)
        assertEquals(5, f.generation(db)); assertEquals(1, f.technical(db).size)
        f.sameRows(f.full(), f.snapshot(db))
    }
    @Test fun `no technical row still captures system card and empty tables are real empty`() = f.withDatabase { db ->
        val actual = capture(db, BackupPlanNotificationSource { listOf(card, card) }).snapshot()
        assertTrue(actual.todoIds.isEmpty()); assertTrue(actual.scheduleIds.isEmpty()); assertTrue(actual.subscriptionIds.isEmpty())
        assertTrue(actual.planAlarmTokens.isEmpty()); assertEquals(listOf(card), actual.planNotificationTokens)
        assertEquals(5, f.generation(db))
    }
    @Test fun `deleted business plan does not discard its technical cleanup token`() = f.withDatabase { db ->
        f.inventory(db)
        val actual = capture(db, BackupPlanNotificationSource { emptyList() }).snapshot()
        assertEquals(17, actual.planAlarmTokens.single().planId); assertEquals(1, f.technical(db).size)
    }
    @Test fun `corrupt technical sqlite values reject before system enumeration`() = f.withDatabase { db ->
        f.inventory(db)
        val source = BackupPlanNotificationSource { error("must not enumerate after corrupt SQL") }
        val capture = capture(db, source)
        for ((column, value) in listOf("delivered" to "3", "generation" to "-1", "occurrenceDate" to "9223372036854775807", "triggerAt" to "x'00'")) {
            db.openHelper.writableDatabase.execSQL("DELETE FROM plan_reminder_state"); f.inventory(db)
            db.openHelper.writableDatabase.execSQL("UPDATE plan_reminder_state SET $column=$value")
            assertFailsWith<Exception> { capture.snapshot() }
            assertEquals(5, f.generation(db))
        }
    }
    @Test fun `invalid legacy source or dangling mapping fails rather than inventing old ids`() = f.withDatabase { db ->
        f.seed(db)
        val capture = capture(db, BackupPlanNotificationSource { emptyList() })
        db.openHelper.writableDatabase.execSQL("UPDATE plan_legacy_refs SET source='UNKNOWN' WHERE source='TODO'")
        assertFailsWith<Exception> { capture.snapshot() }
        db.openHelper.writableDatabase.execSQL("UPDATE plan_legacy_refs SET source='TODO' WHERE source='UNKNOWN'")
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=OFF")
        db.openHelper.writableDatabase.execSQL("UPDATE plan_legacy_refs SET planId=999 WHERE source='TODO'")
        assertFailsWith<Exception> { capture.snapshot() }
        assertEquals(5, f.generation(db))
    }
    @Test fun `system enumeration failure propagates after SQL transaction ends without deleting data`() = f.withDatabase { db ->
        f.seed(db); f.inventory(db)
        val capture = capture(db, BackupPlanNotificationSource { assertFalse(db.inTransaction()); throw IllegalStateException("synthetic service unavailable") })
        assertFailsWith<IllegalStateException> { capture.snapshot() }
        f.sameRows(f.full(), f.snapshot(db)); assertEquals(1, f.technical(db).size); assertEquals(5, f.generation(db))
    }
    private fun capture(db: com.example.lifemanager.data.plan.PlanMigrationTestDatabase, notifications: BackupPlanNotificationSource): BackupReminderCapture {
        return V2BackupReminderCaptureImpl(db, db.backup(), notifications)
    }
}
