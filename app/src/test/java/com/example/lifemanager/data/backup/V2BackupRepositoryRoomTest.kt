package com.example.lifemanager.data.backup

import android.app.Application
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteQuery
import com.example.lifemanager.data.local.dao.BackupSnapshotDao
import com.example.lifemanager.domain.backup.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class V2BackupRepositoryRoomTest {
    private val f = V2BackupRoomFixtures
    @Test fun `snapshot reads independently seeded ten tables raw fields settings and no device metadata`() = f.withDatabase { db ->
        f.seed(db); f.inventory(db)
        val result = f.snapshot(db)
        f.sameRows(f.full(), result)
        assertEquals(12, result.tables.values.sumOf { it.size }); assertEquals(10, result.tables.size)
        assertEquals(BackupValue.Text("7,01,1"), result.tables.getValue("plans")[0].fields["repeatDaysOfWeek"])
        assertEquals(BackupValue.Integer(9007199254740993), result.tables.getValue("subscription_payments").single().fields["amountMinor"])
        assertEquals(BackupValue.Integer(Long.MAX_VALUE), result.tables.getValue("subscriptions").single().fields["amountMinor"])
        assertEquals(5L, f.generation(db)); assertEquals(listOf(listOf(17L,4L,21000L,Long.MAX_VALUE,2L)), f.technical(db))
    }
    @Test fun `empty snapshot returns actual defaults and every empty table`() = f.withDatabase { db ->
        assertEquals(V2BackupFixtures.empty(), f.snapshot(db)); assertEquals(5L, f.generation(db))
    }
    @Test fun `settings mask decodes all eight combinations without inventing flags`() = f.withDatabase { db ->
        val expected = listOf(emptySet(), setOf(1), setOf(3), setOf(1,3), setOf(7), setOf(1,7), setOf(3,7), setOf(1,3,7))
        for ((mask, days) in expected.withIndex()) {
            db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET defaultReminderMask=?,planReminders=0,subscriptionReminders=1", arrayOf(mask))
            val settings = f.snapshot(db).settings
            assertEquals(days, settings.defaultReminderDays); assertFalse(settings.planReminders); assertTrue(settings.subscriptionReminders)
        }
    }
    @Test fun `aggregate row preflight rejects before loading business cells`() = f.withDatabase { db ->
        f.seed(db); var loaded = false
        val dao = object : BackupSnapshotDao {
            override fun read(query: SupportSQLiteQuery): Cursor {
                if (query.sql.contains("`title`") && !query.sql.contains("length(")) loaded = true
                return db.backup().read(query)
            }
        }
        assertFailsWith<BackupValidationException> { f.snapshot(db, BackupLimits(maxRows = 11), dao) }
        assertFalse(loaded); assertEquals(12, f.snapshot(db, BackupLimits(maxRows = 12)).tables.values.sumOf { it.size })
    }
    @Test fun `utf8 text preflight rejects before loading oversized title`() = f.withDatabase { db ->
        f.seed(db); db.openHelper.writableDatabase.execSQL("UPDATE plans SET title=? WHERE id=17", arrayOf("🌱".repeat(2000)))
        var loaded = false
        val dao = object : BackupSnapshotDao {
            override fun read(query: SupportSQLiteQuery): Cursor {
                if (query.sql.contains("`title`") && !query.sql.contains("length(")) loaded = true
                return db.backup().read(query)
            }
        }
        assertFailsWith<BackupValidationException> { f.snapshot(db, BackupLimits(maxBytes = 4000), dao) }; assertFalse(loaded)
    }
    @Test fun `canonical escaping and structure may exceed budget even when stored text fits`() = f.withDatabase { db ->
        f.seed(db); db.openHelper.writableDatabase.execSQL("UPDATE plans SET title=? WHERE id=17", arrayOf("\"".repeat(2000)))
        assertFailsWith<BackupValidationException> { f.snapshot(db, BackupLimits(maxBytes = 3000)) }
    }
    @Test fun `exact payload byte budget passes one less fails while envelope still needs extra space`() = f.withDatabase { db ->
        f.seed(db)
        val bytes = V2BackupJsonCodec().encode(V2BackupFixtures.document(f.full()))
        val size = Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("payload").toString().toByteArray().size
        val result = f.snapshot(db, BackupLimits(maxBytes = size))
        f.sameRows(f.full(), result)
        assertFailsWith<BackupValidationException> { f.snapshot(db, BackupLimits(maxBytes = size - 1)) }
        assertFailsWith<BackupValidationException> { V2BackupJsonCodec(BackupLimits(maxBytes = size)).encode(V2BackupFixtures.document(result)) }
    }
    @Test fun `invalid stored sqlite type and active configuration reject instead of empty success`() = f.withDatabase { db ->
        f.seed(db)
        for (sql in listOf("UPDATE plans SET isCompleted=2 WHERE id=17", "UPDATE plans SET isCompleted=0.5 WHERE id=17",
            "UPDATE plans SET isCompleted=X'01' WHERE id=17")) {
            db.openHelper.writableDatabase.execSQL(sql); assertFailsWith<BackupValidationException> { f.snapshot(db) }
        }
        db.openHelper.writableDatabase.execSQL("UPDATE plans SET isCompleted=0,timeMode='TIMED',endAt=startAt WHERE id=17")
        assertFailsWith<BackupValidationException> { f.snapshot(db) }
    }
    @Test fun `bad settings values or extra settings row are never default repaired`() = f.withDatabase { db ->
        for (sql in listOf("UPDATE app_settings SET defaultReminderMask=8", "UPDATE app_settings SET planReminders=2",
            "UPDATE app_settings SET defaultCurrency='invalid'", "UPDATE app_settings SET theme='INVALID'")) {
            db.openHelper.writableDatabase.execSQL(sql); assertFailsWith<BackupValidationException> { f.snapshot(db) }
            db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET theme='SYSTEM',defaultCurrency='CNY',planReminders=1,defaultReminderMask=0")
        }
        db.openHelper.writableDatabase.execSQL("INSERT INTO app_settings VALUES (2,'SYSTEM','YMD','CNY',1,1,0)")
        assertFailsWith<BackupValidationException> { f.snapshot(db) }
    }
    @Test fun `missing or corrupt local generation rejects but exhausted readable generation may export`() = f.withDatabase { db ->
        db.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=9223372036854775807")
        assertEquals(V2BackupFixtures.empty(), f.snapshot(db))
        db.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=0.5")
        assertFailsWith<BackupValidationException> { f.snapshot(db) }
        db.openHelper.writableDatabase.execSQL("DELETE FROM app_maintenance")
        assertFailsWith<BackupValidationException> { f.snapshot(db) }
    }
    @Test fun `max primary key can be read and encoded for rescue even though restore refuses it`() = f.withDatabase { db ->
        f.seed(db, V2BackupFixtures.full())
        val result = f.snapshot(db); f.sameRows(V2BackupFixtures.full(), result)
        val decoded = V2BackupJsonCodec().decode(V2BackupJsonCodec().encode(V2BackupFixtures.document(result)))
        f.sameRows(V2BackupFixtures.full(), decoded.payload)
    }
    @Test fun `invalid settings cardinality rejects before loading any settings cells`() = f.withDatabase { db ->
        db.openHelper.writableDatabase.execSQL("INSERT INTO app_settings VALUES (2,'SYSTEM','YMD','CNY',1,1,0)")
        var loaded=false
        val dao=object:BackupSnapshotDao {
            override fun read(query:SupportSQLiteQuery):Cursor {
                if(query.sql.contains("`theme`") && !query.sql.contains("length(")) loaded=true
                return db.backup().read(query)
            }
        }
        assertFailsWith<BackupValidationException> { f.snapshot(db,dao=dao) }; assertFalse(loaded)
    }
    @Test fun `snapshot transaction excludes concurrent replacement and returns one consistent database state`() = f.withDatabase { db ->
        f.seed(db)
        coroutineScope {
            val reached=CountDownLatch(1); val release=CountDownLatch(1); val once=AtomicBoolean(false)
            val dao=object:BackupSnapshotDao {
                override fun read(query:SupportSQLiteQuery):Cursor {
                    if(query.sql.contains("`title`") && !query.sql.contains("length(") && once.compareAndSet(false,true)) {
                        reached.countDown(); check(release.await(5,TimeUnit.SECONDS))
                    }
                    return db.backup().read(query)
                }
            }
            val read=async(Dispatchers.IO) { f.snapshot(db,dao=dao) }
            try {
                assertTrue(reached.await(5,TimeUnit.SECONDS))
                val entered=CompletableDeferred<Unit>()
                val write=async(Dispatchers.IO) { entered.complete(Unit); f.replace(db,V2BackupFixtures.empty()) }
                entered.await()
                assertFailsWith<TimeoutCancellationException> { withTimeout(150) { write.await() } }
                release.countDown()
                f.sameRows(f.full(),read.await()); assertEquals(com.example.lifemanager.domain.maintenance.DataGeneration(6),write.await())
                assertEquals(V2BackupFixtures.empty(),f.snapshot(db))
            } finally { release.countDown() }
        }
    }
}
