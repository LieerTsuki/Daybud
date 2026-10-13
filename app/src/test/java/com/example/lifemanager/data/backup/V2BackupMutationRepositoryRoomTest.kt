package com.example.lifemanager.data.backup

import android.app.Application
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteQuery
import com.example.lifemanager.data.local.dao.BackupSnapshotDao
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.*
import java.time.ZoneId
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class V2BackupMutationRepositoryRoomTest {
    private val f = V2BackupRoomFixtures
    @Test fun `restore preserves forward parents all raw fields relations and preferences in one commit`() = f.withDatabase { db ->
        assertEquals(DataGeneration(6), f.replace(db, f.full()))
        f.sameRows(f.full(), f.snapshot(db))
        assertEquals(9007199254740993L, f.scalar(db,"SELECT parentId FROM plans WHERE id=17"))
        assertEquals(5L, f.scalar(db,"SELECT defaultReminderMask FROM app_settings"))
        assertEquals(6L, f.generation(db))
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
    @Test fun `clear removes all business rows preserves settings inventory and high water ids`() = f.withDatabase { db ->
        f.seed(db); f.inventory(db)
        assertEquals(DataGeneration(6), f.clear(db))
        assertEquals(V2BackupFixtures.empty().copy(settings=f.full().settings), f.snapshot(db))
        assertEquals(listOf(listOf(17L,4L,21000L,Long.MAX_VALUE,2L)), f.technical(db))
        assertEquals(9007199254740993L, f.scalar(db,"SELECT seq FROM sqlite_sequence WHERE name='plans'"))
    }
    @Test fun `empty restore replaces preferences unlike clear and inventory survives reused ids`() = f.withDatabase { db ->
        f.seed(db); f.inventory(db)
        assertEquals(DataGeneration(6), f.replace(db,V2BackupFixtures.empty()))
        assertEquals(V2BackupFixtures.empty(), f.snapshot(db)); assertEquals(1,f.technical(db).size)
        assertEquals(DataGeneration(7), f.replace(db,f.full(),6)); f.sameRows(f.full(),f.snapshot(db))
        assertEquals(listOf(listOf(17L,4L,21000L,Long.MAX_VALUE,2L)),f.technical(db))
    }
    @Test fun `large nonmaximum restored identity permits subsequent autoincrement without sequence reset`() = f.withDatabase { db ->
        f.replace(db,f.full())
        val next = db.plans().insert(com.example.lifemanager.data.plan.PlanEntity(title="synthetic next",timeZone="UTC",createdAt=0,updatedAt=0))
        assertEquals(9007199254740994L,next); assertEquals(6L,f.generation(db))
    }
    @Test fun `legacy file validates converts then stores real target while native v2 roundtrips`() = f.withDatabase { db ->
        val codec = VersionedBackupJsonCodec()
        val bytes = BackupJsonCodec().encode(BackupDocument("0.1.1","2026-10-13T00:00:00Z",BackupFixtures.fullPayload()))
        val result = codec.decode(bytes,ZoneId.of("Asia/Shanghai"))
        f.replace(db,result.document.payload); f.sameRows(result.document.payload,f.snapshot(db))
        assertEquals(BackupValue.Text("Asia/Shanghai"),f.snapshot(db).tables.getValue("plans")[0].fields["timeZone"])
        val restored = codec.decode(codec.encode(V2BackupFixtures.document(f.snapshot(db))),ZoneId.of("Europe/Paris"))
        f.replace(db,restored.document.payload,6); f.sameRows(result.document.payload,f.snapshot(db)); assertEquals(7L,f.generation(db))
    }
    @Test fun `every auto table MAX primary key rejects before any delete while codec contract stays intact`() = f.withDatabase { db ->
        f.seed(db)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER no_delete BEFORE DELETE ON plans BEGIN SELECT RAISE(ABORT,'deletion reached'); END")
        for (name in f.autoTables) {
            var value = if(name=="plans") V2BackupFixtures.full() else f.full().table(name,listOf(f.full().tables.getValue(name).single().with("id",BackupValue.Integer(Long.MAX_VALUE))))
            val fk = when(name) { "tags"->"tagId"; "habits"->"habitId"; "subscriptions"->"subscriptionId"; else->null }
            if(fk!=null) for(table in listOf("plan_tag_cross_ref","habit_records","subscription_payments","subscription_reminders")) {
                value=value.table(table,value.tables.getValue(table).map { if(fk in it.fields) it.with(fk,BackupValue.Integer(Long.MAX_VALUE)) else it })
            }
            V2BackupJsonCodec().encode(V2BackupFixtures.document(value))
            val error=assertFailsWith<BackupValidationException> { f.replace(db,value) }
            assertTrue(error.message.orEmpty().contains(name)); assertEquals(5L,f.generation(db)); f.sameRows(f.full(),f.snapshot(db))
        }
    }
    @Test fun `destination exhausted sequence refuses restore and clear without repairing any state`() = f.withDatabase { db ->
        f.seed(db)
        for (name in f.autoTables) {
            val before=f.scalar(db,"SELECT seq FROM sqlite_sequence WHERE name='$name'")
            db.openHelper.writableDatabase.execSQL("UPDATE sqlite_sequence SET seq=9223372036854775807 WHERE name=?",arrayOf(name))
            assertFailsWith<BackupValidationException> { f.replace(db,V2BackupFixtures.empty()) }
            assertFailsWith<BackupValidationException> { f.clear(db) }
            assertEquals(Long.MAX_VALUE,f.scalar(db,"SELECT seq FROM sqlite_sequence WHERE name='$name'"))
            assertEquals(5L,f.generation(db)); f.sameRows(f.full(),f.snapshot(db))
            db.openHelper.writableDatabase.execSQL("UPDATE sqlite_sequence SET seq=? WHERE name=?",arrayOf<Any>(before,name))
        }
    }
    @Test fun `invalid sequence type duplicate missing or lower high water rejects rather than normalizes`() = f.withDatabase { db ->
        f.seed(db)
        for (bad in listOf("'bad'","1.5","-1","1")) {
            db.openHelper.writableDatabase.execSQL("UPDATE sqlite_sequence SET seq=$bad WHERE name='plans'")
            assertFailsWith<BackupValidationException> { f.clear(db) }; assertEquals(5L,f.generation(db))
        }
        db.openHelper.writableDatabase.execSQL("UPDATE sqlite_sequence SET seq=9007199254740993 WHERE name='plans'")
        db.openHelper.writableDatabase.execSQL("INSERT INTO sqlite_sequence(name,seq) VALUES ('plans',9007199254740993)")
        assertFailsWith<BackupValidationException> { f.replace(db,V2BackupFixtures.empty()) }
        db.openHelper.writableDatabase.execSQL("DELETE FROM sqlite_sequence WHERE name='plans'")
        assertFailsWith<BackupValidationException> { f.clear(db) }; f.sameRows(f.full(),f.snapshot(db))
    }
    @Test fun `invalid source rows types foreign keys technical tables and byte limits never mutate destination`() = f.withDatabase { db ->
        f.seed(db)
        for(bad in listOf(f.full().copy(tables=emptyMap()), f.full().copy(tables=f.full().tables+("plan_reminder_state" to emptyList())),
            f.full().table("plans",listOf(V2BackupFixtures.plan().with("id",BackupValue.Text("17")))),
            f.full().table("plan_tag_cross_ref",listOf(V2BackupFixtures.row("planId" to 17L,"tagId" to 999L))))) {
            assertFailsWith<BackupValidationException> { f.replace(db,bad) }; assertEquals(5L,f.generation(db))
        }
        assertFailsWith<BackupValidationException> { f.replace(db,f.full(),limits=BackupLimits(maxRows=11)) }
        assertFailsWith<BackupValidationException> { f.replace(db,f.full(),limits=BackupLimits(maxBytes=100)) }
        f.sameRows(f.full(),f.snapshot(db))
    }
    @Test fun `stale or exhausted generation and bad current settings refuse both mutations`() = f.withDatabase { db ->
        f.seed(db)
        assertFailsWith<StaleGenerationException> { f.replace(db,f.full(),4) }; assertFailsWith<StaleGenerationException> { f.clear(db,4) }
        db.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=9223372036854775807")
        assertFailsWith<IllegalStateException> { f.replace(db,f.full(),Long.MAX_VALUE) }; assertFailsWith<IllegalStateException> { f.clear(db,Long.MAX_VALUE) }
        db.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=5")
        db.openHelper.writableDatabase.execSQL("UPDATE app_settings SET defaultReminderMask=8")
        assertFailsWith<BackupValidationException> { f.replace(db,V2BackupFixtures.empty()) }; assertFailsWith<BackupValidationException> { f.clear(db) }
        assertEquals(5L,f.generation(db)); assertEquals(2L,f.scalar(db,"SELECT count(*) FROM plans"))
    }
    @Test fun `insert failure rolls back deleted rows preferences inventory sequence and generation`() = f.withDatabase { db ->
        f.seed(db); f.inventory(db)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_insert BEFORE INSERT ON habit_records BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        assertFailsWith<Exception> { f.replace(db,f.full().copy(settings=V2BackupSettings())) }
        f.sameRows(f.full(),f.snapshot(db)); assertEquals(5L,f.generation(db)); assertEquals(1,f.technical(db).size)
        assertEquals(9007199254740993L,f.scalar(db,"SELECT seq FROM sqlite_sequence WHERE name='plans'"))
    }
    @Test fun `clear failure rolls back earlier child deletes`() = f.withDatabase { db ->
        f.seed(db)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_delete BEFORE DELETE ON habits BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        assertFailsWith<Exception> { f.clear(db) }; f.sameRows(f.full(),f.snapshot(db)); assertEquals(5L,f.generation(db))
    }
    @Test fun `late generation advance failure rolls back all replacement data and preferences`() = f.withDatabase { db ->
        f.seed(db)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_generation BEFORE UPDATE ON app_maintenance BEGIN SELECT RAISE(ABORT,'late synthetic'); END")
        assertFailsWith<Exception> { f.replace(db,V2BackupFixtures.empty()) }; f.sameRows(f.full(),f.snapshot(db)); assertEquals(5L,f.generation(db))
    }
    @Test fun `silent trigger mutation after generation advance rejects and rolls back`() = f.withDatabase { db ->
        f.seed(db)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER corrupt_saved AFTER UPDATE ON app_maintenance BEGIN UPDATE plans SET title='mutated'; END")
        assertFailsWith<Exception> { f.replace(db,f.full()) }; f.sameRows(f.full(),f.snapshot(db)); assertEquals(5L,f.generation(db))
    }
    @Test fun `late inventory or sequence trigger side effects are rolled back`() = f.withDatabase { db ->
        f.seed(db); f.inventory(db)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER corrupt_inventory AFTER UPDATE ON app_maintenance BEGIN DELETE FROM plan_reminder_state; END")
        assertFailsWith<Exception> { f.clear(db) }; assertEquals(1,f.technical(db).size); f.sameRows(f.full(),f.snapshot(db)); assertEquals(5L,f.generation(db))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER corrupt_inventory")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER corrupt_sequence AFTER UPDATE ON app_maintenance BEGIN UPDATE sqlite_sequence SET seq=0 WHERE name='plans'; END")
        assertFailsWith<Exception> { f.replace(db,f.full()) }; f.sameRows(f.full(),f.snapshot(db)); assertEquals(9007199254740993L,f.scalar(db,"SELECT seq FROM sqlite_sequence WHERE name='plans'"))
    }
    @Test fun `actual job cancellation after CAS but before commit rolls back restore and clear`() = f.withDatabase { db ->
        f.seed(db); f.inventory(db)
        for (clear in listOf(false,true)) {
            val operation=Job(); var reached=false
            val dao=object:BackupSnapshotDao {
                override fun read(query:SupportSQLiteQuery):Cursor {
                    if(f.generation(db)==6L) { reached=true; operation.cancel(CancellationException("synthetic before commit")) }
                    return db.backup().read(query)
                }
            }
            assertFailsWith<CancellationException> { withContext(operation+Dispatchers.IO) {
                if(clear) f.clear(db,dao=dao) else f.replace(db,V2BackupFixtures.empty(),dao=dao)
            } }
            assertTrue(reached); f.sameRows(f.full(),f.snapshot(db)); assertEquals(5L,f.generation(db)); assertEquals(1,f.technical(db).size)
        }
    }
    @Test fun `concurrent same expected generation admits exactly one atomic replacement`() = f.withDatabase { db ->
        val outcomes=coroutineScope { (1..2).map { async(Dispatchers.IO) { runCatching { f.replace(db,f.full()) } } }.awaitAll() }
        assertEquals(1,outcomes.count { it.isSuccess }); assertEquals(1,outcomes.count { it.exceptionOrNull() is StaleGenerationException })
        assertEquals(6L,f.generation(db)); f.sameRows(f.full(),f.snapshot(db))
    }
    @Test fun `restore owns the validated source rather than following mutable caller changes`() = f.withDatabase { db ->
        val expected=f.full()
        val plans=expected.tables.getValue("plans").map { BackupRow(it.fields.toMutableMap()) }
        val source=expected.table("plans",plans); var changed=false
        val dao=object:BackupSnapshotDao {
            override fun read(query:SupportSQLiteQuery):Cursor {
                if(!changed && query.sql.contains("sqlite_sequence")) {
                    changed=true
                    (plans[0].fields as MutableMap<String,BackupValue>)["title"]=BackupValue.Text("changed after validation")
                }
                return db.backup().read(query)
            }
        }
        assertEquals(DataGeneration(6),f.replace(db,source,dao=dao)); assertTrue(changed)
        f.sameRows(expected,f.snapshot(db))
    }
    @Test fun `four time modes retain active and inactive raw configuration through real Room restore`() = f.withDatabase { db ->
        val plans=listOf(V2BackupFixtures.plan(1),
            V2BackupFixtures.plan(2).with("timeMode",BackupValue.Text("DEADLINE")).with("dueDate",BackupValue.Null),
            V2BackupFixtures.plan(3).with("timeMode",BackupValue.Text("TIMED")),
            V2BackupFixtures.plan(4).with("timeMode",BackupValue.Text("ALL_DAY")))
        val source=V2BackupFixtures.empty().table("plans",plans)
        f.replace(db,source); f.sameRows(source,f.snapshot(db))
        assertEquals(listOf("NONE","DEADLINE","TIMED","ALL_DAY"),f.snapshot(db).tables.getValue("plans").map { (it.fields.getValue("timeMode") as BackupValue.Text).value })
    }
    @Test fun `all reminder day combinations write exact sqlite mask with initially absent sequences`() = f.withDatabase { db ->
        val days=listOf(emptySet(),setOf(1),setOf(3),setOf(1,3),setOf(7),setOf(1,7),setOf(3,7),setOf(1,3,7))
        for((mask, selected) in days.withIndex()) {
            val source=V2BackupFixtures.empty().copy(settings=V2BackupSettings(planReminders=false,subscriptionReminders=true,defaultReminderDays=selected))
            assertEquals(DataGeneration(6+mask.toLong()),f.replace(db,source,5+mask.toLong()))
            assertEquals(mask.toLong(),f.scalar(db,"SELECT defaultReminderMask FROM app_settings")); assertEquals(source,f.snapshot(db))
        }
    }
    @Test fun `negative legacy primary identities and zero high water sequences remain lossless`() = f.withDatabase { db ->
        var source=f.full()
        val changes=mapOf("tags" to Long.MIN_VALUE,"habits" to -5L,"habit_records" to -9L,"subscriptions" to -8L,"subscription_payments" to -3L)
        for((name,id) in changes) source=source.table(name,listOf(source.tables.getValue(name).single().with("id",BackupValue.Integer(id))))
        source=source.table("plan_tag_cross_ref",listOf(source.tables.getValue("plan_tag_cross_ref").single().with("tagId",BackupValue.Integer(Long.MIN_VALUE))))
            .table("habit_records",listOf(source.tables.getValue("habit_records").single().with("habitId",BackupValue.Integer(-5))))
            .table("subscription_payments",listOf(source.tables.getValue("subscription_payments").single().with("subscriptionId",BackupValue.Integer(-8))))
            .table("subscription_reminders",listOf(source.tables.getValue("subscription_reminders").single().with("subscriptionId",BackupValue.Integer(-8))))
        f.replace(db,source); f.sameRows(source,f.snapshot(db))
        assertEquals(0L,f.scalar(db,"SELECT seq FROM sqlite_sequence WHERE name='tags'"))
        assertEquals(Long.MIN_VALUE,f.scalar(db,"SELECT id FROM tags"))
    }
    @Test fun `clear preserves absent sequence rows as absent and existing zero as a stored zero`() = f.withDatabase { db ->
        assertEquals(0L,f.scalar(db,"SELECT count(*) FROM sqlite_sequence"))
        assertEquals(DataGeneration(6),f.clear(db))
        assertEquals(0L,f.scalar(db,"SELECT count(*) FROM sqlite_sequence"))
        db.openHelper.writableDatabase.execSQL("INSERT INTO tags(id,name,color,createdAt) VALUES (-1,'synthetic',0,0)")
        assertEquals(1L,f.scalar(db,"SELECT count(*) FROM sqlite_sequence WHERE name='tags'"))
        assertEquals(0L,f.scalar(db,"SELECT seq FROM sqlite_sequence WHERE name='tags'"))
        assertEquals(DataGeneration(7),f.clear(db,6))
        assertEquals(1L,f.scalar(db,"SELECT count(*) FROM sqlite_sequence WHERE name='tags'"))
        assertEquals(0L,f.scalar(db,"SELECT seq FROM sqlite_sequence WHERE name='tags'"))
        assertEquals(0L,f.scalar(db,"SELECT count(*) FROM sqlite_sequence WHERE name='plans'"))
    }
}
