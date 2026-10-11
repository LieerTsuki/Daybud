package com.example.lifemanager.data.plan

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.lifemanager.data.local.entity.TagEntity
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.plan.Plan
import com.example.lifemanager.domain.plan.PlanException
import com.example.lifemanager.domain.plan.PlanLegacySource
import com.example.lifemanager.domain.plan.PlanTimeMode
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PlanRepositoryRoomTest {
    private val now = Instant.parse("2026-10-10T08:00:00Z")
    private val token = DataGeneration(0)

    @Test fun `new plan persists text dates tags and real audit time`() = withDatabase { db, repo ->
        val id = success { repo.save(plan().copy(description = "\n备注\n", timeMode = PlanTimeMode.DEADLINE,
            dueDate = LocalDate.of(2026, 10, 11)), listOf(" 工作 ", "工作", ""), token) }
        assertTrue(id > 0)
        val saved = assertNotNull(repo.get(id))
        assertEquals("  目标🌱  ", saved.title)
        assertEquals("\n备注\n", saved.description)
        assertEquals(LocalDate.of(2026, 10, 11), saved.dueDate)
        assertNull(saved.dueAt)
        assertEquals(listOf("工作"), saved.tagNames)
        assertEquals(now, saved.createdAt)
        assertEquals(now, saved.updatedAt)
        assertEquals(0L, generation(db))
    }

    @Test fun `deadline conversion keeps identity completion tags exceptions and inactive raw fields`() = withDatabase { db, repo ->
        seed(db, entity(7).copy(isCompleted = true, completedAt = 123, repeatRule = ScheduleRepeatRule.CUSTOM,
            repeatDaysOfWeek = "2,1,2", startAt = 100, endAt = 200, allDayStartDate = 1, allDayEndDate = 2))
        db.plans().insertTag(TagEntity(9, "已有", 1, 1))
        db.plans().insertLinks(listOf(PlanTagCrossRef(7, 9)))
        db.plans().insertException(PlanExceptionEntity(7, 2, true))
        val loaded = assertNotNull(success { repo.get(7) })
        assertEquals(7L, success { repo.save(loaded.copy(timeMode = PlanTimeMode.DEADLINE,
            dueAt = now, isCompleted = false, completedAt = null, createdAt = now), loaded.tagNames, token) })
        val stored = assertNotNull(db.plans().findEntity(7))
        assertEquals(123L, stored.completedAt)
        assertTrue(stored.isCompleted)
        assertEquals(1L, stored.createdAt)
        assertEquals("2,1,2", stored.repeatDaysOfWeek)
        assertEquals(100L, stored.startAt)
        assertEquals(2L, stored.allDayEndDate)
        assertEquals(listOf("已有"), repo.get(7)!!.tagNames)
        assertEquals(listOf(PlanException(7, LocalDate.ofEpochDay(2), true)), repo.getExceptions(7))
    }

    @Test fun `explicit semantic repeat edit replaces raw weekdays but ordinary edit preserves empty string`() = withDatabase { db, repo ->
        seed(db, entity(1).copy(repeatDaysOfWeek = ""))
        val loaded = assertNotNull(success { repo.get(1) })
        success { repo.save(loaded.copy(title = "改名"), emptyList(), token) }
        assertEquals("", db.plans().findEntity(1)!!.repeatDaysOfWeek)
        success { repo.save(loaded.copy(repeatDaysOfWeek = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY)), emptyList(), token) }
        assertEquals("1,5", db.plans().findEntity(1)!!.repeatDaysOfWeek)
    }

    @Test fun `ordinary edit preserves distinct historical raw tag identities and only normalizes new tags`() = withDatabase { db, repo ->
        seed(db, entity(1))
        for (tag in listOf(TagEntity(4, " 工作 ", 11, 1), TagEntity(5, "工作", 22, 2), TagEntity(6, " ", 33, 3))) {
            db.plans().insertTag(tag)
            db.plans().insertLinks(listOf(PlanTagCrossRef(1, tag.id)))
        }
        val draft = assertNotNull(success { repo.get(1) })
        success { repo.save(draft.copy(timeMode = PlanTimeMode.DEADLINE, dueAt = now), draft.tagNames, token) }
        val saved = assertNotNull(repo.get(1))
        assertEquals(setOf(4L, 5L, 6L), saved.tagIds)
        assertEquals(draft.tagNames, saved.tagNames)
        assertEquals(mapOf(4L to 11, 5L to 22, 6L to 33), repo.observeTags().first().associate { it.id to it.color })
        success { repo.save(saved, saved.tagNames + listOf(" 新标签 ", "新标签"), token) }
        val added = repo.get(1)!!
        assertEquals(4, added.tagIds.size)
        assertTrue(added.tagIds.containsAll(setOf(4L, 5L, 6L)))
        assertEquals(1, added.tagNames.count { it == "新标签" })
        success { repo.save(added, listOf("工作"), token) }
        assertEquals(setOf(5L), repo.get(1)!!.tagIds)
    }

    @Test fun `historical blank fixed offset record can be read and completed but edited only under strict rules`() = withDatabase { db, repo ->
        seed(db, entity(1).copy(title = " \n", timeZone = "+08:00"))
        val loaded = assertNotNull(success { repo.get(1) })
        assertEquals(" \n", loaded.title)
        assertEquals("+08:00", loaded.timeZone)
        success { repo.setCompleted(1, true, token) }
        assertTrue(repo.get(1)!!.isCompleted)
        assertFailsWith<IllegalArgumentException> { repo.save(loaded, emptyList(), token) }
        assertEquals(" \n", db.plans().findEntity(1)!!.title)
    }

    @Test fun `completion is idempotent and preserves series exceptions without completing child`() = withDatabase { db, repo ->
        seed(db, entity(1).copy(timeMode = PlanTimeMode.TIMED, startAt = 1, endAt = 2,
            repeatRule = ScheduleRepeatRule.DAILY, repeatDaysOfWeek = "2,1,2"))
        seed(db, entity(2).copy(parentId = 1))
        db.plans().insertException(PlanExceptionEntity(1, 1, true))
        success { repo.setCompleted(1, true, token) }
        val first = db.plans().findEntity(1)!!
        val later = PlanRepositoryImpl(db, db.plans(), Clock.fixed(now.plusSeconds(60), ZoneId.of("UTC")))
        success { later.setCompleted(1, true, token) }
        assertEquals(first, db.plans().findEntity(1))
        assertFalse(repo.get(2)!!.isCompleted)
        assertEquals("2,1,2", first.repeatDaysOfWeek)
        assertEquals(1, repo.getExceptions(1).size)
        success { later.setCompleted(1, false, token) }
        assertNull(db.plans().findEntity(1)!!.completedAt)
        assertFalse(repo.get(1)!!.isCompleted)
        assertEquals(ScheduleRepeatRule.DAILY, repo.get(1)!!.repeatRule)
    }

    @Test fun `undo clears historical pending timestamp`() = withDatabase { db, repo ->
        seed(db, entity(1).copy(completedAt = 8))
        success { repo.setCompleted(1, false, token) }
        assertNull(db.plans().findEntity(1)!!.completedAt)
    }

    @Test fun `complete accepts all historical completion combinations without repairing already completed row`() = withDatabase { db, repo ->
        for (completed in listOf(false, true)) for (at in listOf(null, 8L)) {
            db.plans().delete(1); seed(db, entity(1).copy(isCompleted = completed, completedAt = at))
            success { repo.setCompleted(1, true, token) }
            val saved = db.plans().findEntity(1)!!
            assertTrue(saved.isCompleted)
            assertEquals(if (completed) at else now.toEpochMilli(), saved.completedAt)
            assertEquals(if (completed) 2L else now.toEpochMilli(), saved.updatedAt)
            success { repo.setCompleted(1, false, token) }
            assertNull(db.plans().findEntity(1)!!.completedAt)
        }
    }

    @Test fun `old editor saves content without overwriting more recent completion`() = withDatabase { db, repo ->
        seed(db, entity(1))
        val draft = assertNotNull(success { repo.get(1) })
        success { repo.setCompleted(1, true, token) }
        success { repo.save(draft.copy(title = "旧草稿新内容"), emptyList(), token) }
        assertTrue(repo.get(1)!!.isCompleted)
        assertEquals(now, repo.get(1)!!.completedAt)
        assertEquals("旧草稿新内容", repo.get(1)!!.title)
    }

    @Test fun `deleted draft is never inserted back and failed edit creates no tags`() = withDatabase { db, repo ->
        seed(db, entity(1))
        val old = assertNotNull(success { repo.get(1) })
        success { repo.delete(1, token) }
        assertFailsWith<IllegalStateException> { repo.save(old, listOf("不能出现"), token) }
        assertNull(repo.get(1))
        assertTrue(repo.observeTags().first().isEmpty())
        assertFailsWith<IllegalStateException> { repo.setCompleted(1, true, token) }
    }

    @Test fun `delete parent detaches child and cascades only own relationships`() = withDatabase { db, repo ->
        seed(db, entity(1)); seed(db, entity(2).copy(parentId = 1))
        db.plans().insertTag(TagEntity(4, "共用", 1, 1))
        db.plans().insertLinks(listOf(PlanTagCrossRef(1, 4), PlanTagCrossRef(2, 4)))
        db.plans().insertException(PlanExceptionEntity(1, 2, true))
        db.plans().insertException(PlanExceptionEntity(2, 3, false))
        db.plans().insertLegacyRef(PlanLegacyRefEntity(LegacyPlanSource.TODO, Long.MAX_VALUE, 1))
        success { repo.delete(1, token) }
        assertNull(repo.get(1))
        assertNull(assertNotNull(repo.get(2)).parentId)
        assertEquals(listOf("共用"), repo.get(2)!!.tagNames)
        assertTrue(repo.getExceptions(1).isEmpty())
        assertEquals(1, repo.getExceptions(2).size)
        assertNull(repo.findLegacyPlan(PlanLegacySource.TODO, Long.MAX_VALUE))
        assertEquals(1, repo.observeTags().first().size)
    }

    @Test fun `save rejects missing self or descendant parent without partial changes`() = withDatabase { db, repo ->
        seed(db, entity(1)); seed(db, entity(2).copy(parentId = 1)); seed(db, entity(3).copy(parentId = 2))
        val parent = assertNotNull(success { repo.get(1) })
        for (candidate in listOf(99L, 1L, 3L)) {
            assertFailsWith<IllegalArgumentException> { repo.save(parent.copy(parentId = candidate), listOf("坏树"), token) }
            assertNull(db.plans().findEntity(1)!!.parentId)
        }
        assertTrue(repo.observeTags().first().isEmpty())
    }

    @Test fun `new plan rejects invalid title zone active dates and manufactured completion`() = withDatabase { db, repo ->
        for (bad in listOf(plan().copy(title = " "), plan().copy(timeZone = "+08:00"),
            plan().copy(timeMode = PlanTimeMode.DEADLINE), plan().copy(isCompleted = true, completedAt = now))) {
            assertFailsWith<IllegalArgumentException> { repo.save(bad, listOf("无效"), token) }
        }
        assertTrue(db.plans().allEntities().isEmpty())
        assertTrue(repo.observeTags().first().isEmpty())
    }

    @Test fun `stale generation rejects every business mutation and reused identity stays untouched`() = withDatabase { db, repo ->
        seed(db, entity(1).copy(title = "恢复后"))
        db.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=1 WHERE id=1")
        val restored = plan().copy(id = 1)
        assertFailsWith<StaleGenerationException> { repo.save(restored, listOf("旧草稿"), token) }
        assertFailsWith<StaleGenerationException> { repo.setCompleted(1, true, token) }
        assertFailsWith<StaleGenerationException> { repo.delete(1, token) }
        assertFailsWith<StaleGenerationException> { repo.saveException(PlanException(1, LocalDate.ofEpochDay(1), true), token) }
        assertEquals("恢复后", db.plans().findEntity(1)!!.title)
        assertTrue(db.plans().exceptions(1).isEmpty())
        assertEquals(1L, generation(db))
    }

    @Test fun `missing or noninteger generation fails closed and never reseeds metadata`() = withDatabase { db, repo ->
        for (bad in listOf("-1", "1.5", "'bad'")) {
            db.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=$bad WHERE id=1")
            assertFailsWith<IllegalStateException> { repo.save(plan(), emptyList(), token) }
        }
        db.openHelper.writableDatabase.execSQL("DELETE FROM app_maintenance")
        assertFailsWith<IllegalStateException> { repo.save(plan(), emptyList(), token) }
        assertTrue(db.plans().allEntities().isEmpty())
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM app_maintenance").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test fun `ordinary save at maximum generation does not advance or overflow it`() = withDatabase { db, repo ->
        db.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=${Long.MAX_VALUE} WHERE id=1")
        success { repo.save(plan(), emptyList(), DataGeneration(Long.MAX_VALUE)) }
        assertEquals(Long.MAX_VALUE, generation(db))
    }

    @Test fun `failed tag replacement rolls back record existing tags and newly inserted tag`() = withDatabase { db, repo ->
        seed(db, entity(1))
        db.plans().insertTag(TagEntity(2, "旧标签", 1, 1)); db.plans().insertLinks(listOf(PlanTagCrossRef(1, 2)))
        val before = assertNotNull(success { repo.get(1) })
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_link BEFORE INSERT ON plan_tag_cross_ref BEGIN SELECT RAISE(ABORT, 'synthetic link failure'); END")
        assertTrue(runCatching { repo.save(before.copy(title = "不能保存"), listOf("新标签"), token) }.isFailure)
        assertEquals("  目标🌱  ", db.plans().findEntity(1)!!.title)
        assertEquals(listOf("旧标签"), repo.get(1)!!.tagNames)
        assertEquals(listOf("旧标签"), repo.observeTags().first().map { it.name })
    }

    @Test fun `failed new relation insert rolls back new plan and generated tags`() = withDatabase { db, repo ->
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_link BEFORE INSERT ON plan_tag_cross_ref BEGIN SELECT RAISE(ABORT, 'synthetic link failure'); END")
        assertTrue(runCatching { repo.save(plan(), listOf("新标签"), token) }.isFailure)
        assertTrue(db.plans().allEntities().isEmpty())
        assertTrue(success { repo.observeTags().first() }.isEmpty())
    }

    @Test fun `failed update does not replace row or cascade its relations`() = withDatabase { db, repo ->
        seed(db, entity(1)); db.plans().insertException(PlanExceptionEntity(1, 2, true))
        val before = assertNotNull(success { repo.get(1) })
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_update BEFORE UPDATE ON plans BEGIN SELECT RAISE(ABORT, 'synthetic update failure'); END")
        assertTrue(runCatching { repo.save(before.copy(title = "不能保存"), emptyList(), token) }.isFailure)
        assertEquals("  目标🌱  ", db.plans().findEntity(1)!!.title)
        assertEquals(1, repo.getExceptions(1).size)
    }

    @Test fun `cancellation during tag replacement rolls back content tags and link deletion`() {
        val cancelOnDelete = AtomicReference<Job?>()
        withDatabase(onQuery = { sql ->
            if (sql.startsWith("DELETE FROM plan_tag_cross_ref")) cancelOnDelete.getAndSet(null)?.cancel()
        }) { db, repo ->
            seed(db, entity(1)); db.plans().insertTag(TagEntity(2, "旧标签", 1, 1))
            db.plans().insertLinks(listOf(PlanTagCrossRef(1, 2)))
            val draft = assertNotNull(success { repo.get(1) })
            coroutineScope {
                val mutation = async {
                    cancelOnDelete.set(currentCoroutineContext().job)
                    repo.save(draft.copy(title = "取消的内容"), listOf("取消的标签"), token)
                }
                assertFailsWith<CancellationException> { mutation.await() }
            }
            assertEquals("  目标🌱  ", db.plans().findEntity(1)!!.title)
            assertEquals(listOf("旧标签"), repo.get(1)!!.tagNames)
            assertEquals(listOf("旧标签"), repo.observeTags().first().map { it.name })
        }
    }

    @Test fun `exception save updates cancelled flag without replacing its owner`() = withDatabase { db, repo ->
        seed(db, entity(1).copy(timeMode = PlanTimeMode.TIMED, startAt = 1, endAt = 2, repeatRule = ScheduleRepeatRule.DAILY))
        val exception = PlanException(1, LocalDate.ofEpochDay(5), true)
        success { repo.saveException(exception, token) }; success { repo.saveException(exception.copy(isCancelled = false), token) }
        assertEquals(listOf(exception.copy(isCancelled = false)), repo.getExceptions(1))
        assertEquals("  目标🌱  ", repo.get(1)!!.title)
        assertFailsWith<IllegalStateException> { repo.saveException(exception.copy(planId = 99), token) }
    }

    @Test fun `two legacy sources sharing extreme id resolve separately and SQL prevents reused targets`() = withDatabase { db, repo ->
        seed(db, entity(1)); seed(db, entity(2))
        db.plans().insertLegacyRef(PlanLegacyRefEntity(LegacyPlanSource.TODO, Long.MAX_VALUE, 1))
        db.plans().insertLegacyRef(PlanLegacyRefEntity(LegacyPlanSource.SCHEDULE, Long.MAX_VALUE, 2))
        assertEquals(1L, success { repo.findLegacyPlan(PlanLegacySource.TODO, Long.MAX_VALUE) })
        assertEquals(2L, repo.findLegacyPlan(PlanLegacySource.SCHEDULE, Long.MAX_VALUE))
        assertTrue(runCatching { db.plans().insertLegacyRef(PlanLegacyRefEntity(LegacyPlanSource.TODO, 2, 1)) }.isFailure)
        assertTrue(runCatching { db.plans().insertLegacyRef(PlanLegacyRefEntity(LegacyPlanSource.TODO, Long.MAX_VALUE, 2)) }.isFailure)
    }

    @Test fun `missing foreign key targets are rejected at SQL boundary`() = withDatabase { db, _ ->
        seed(db, entity(1))
        assertTrue(runCatching { db.plans().insertLinks(listOf(PlanTagCrossRef(1, 999))) }.isFailure)
        assertTrue(runCatching { db.plans().insertException(PlanExceptionEntity(999, 1, true)) }.isFailure)
        assertTrue(runCatching { db.plans().insertLegacyRef(PlanLegacyRefEntity(LegacyPlanSource.TODO, 1, 999)) }.isFailure)
        assertTrue(runCatching { seed(db, entity(2).copy(parentId = 999)) }.isFailure)
    }

    @Test fun `flow emits real joined tag change even if plan row is unchanged`() = withDatabase { db, repo ->
        seed(db, entity(1)); db.plans().insertTag(TagEntity(2, "旧名", 1, 1)); db.plans().insertLinks(listOf(PlanTagCrossRef(1, 2)))
        coroutineScope {
            val firstValue = CompletableDeferred<Unit>()
            val changed = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5000) {
                    repo.observeAll().onEach { values ->
                        if (values.singleOrNull()?.tagNames == listOf("旧名")) firstValue.complete(Unit)
                    }.first { it.singleOrNull()?.tagNames == listOf("新名") }
                }
            }
            withTimeout(5000) { firstValue.await() }
            db.testTags().rename(2, "新名")
            assertEquals(listOf("新名"), changed.await().single().tagNames)
        }
    }

    @Test fun `invalid exception date surfaces through owner snapshot and flow`() = withDatabase { db, repo ->
        seed(db, entity(1))
        assertEquals(1, success { repo.observeAll().first() }.size)
        db.plans().insertException(PlanExceptionEntity(1, Long.MAX_VALUE, true))
        assertFailsWith<java.time.DateTimeException> { repo.get(1) }
        assertFailsWith<java.time.DateTimeException> { repo.getExceptions(1) }
        assertFailsWith<java.time.DateTimeException> { repo.observeAll().first() }
    }

    @Test fun `real relation SQL query failures reach snapshot and flow collectors`() {
        for (table in listOf("plan_tag_cross_ref", "plan_exceptions")) withDatabase { db, repo ->
            seed(db, entity(1))
            assertEquals(1, success { repo.observeAll().first() }.size)
            db.openHelper.writableDatabase.execSQL("DROP TABLE $table")
            assertFailsWith<android.database.sqlite.SQLiteException> { repo.get(1) }
            assertFailsWith<android.database.sqlite.SQLiteException> { repo.observeAll().first() }
        }
    }

    @Test fun `invalid active fields and zone surface read failure rather than empty success`() = withDatabase { db, repo ->
        for (bad in listOf(entity(1).copy(timeZone = "bad/zone"), entity(1).copy(timeMode = PlanTimeMode.DEADLINE))) {
            db.plans().delete(1); seed(db, bad)
            assertTrue(runCatching { repo.get(1) }.isFailure)
            assertTrue(runCatching { repo.observeAll().first() }.isFailure)
        }
    }

    @Test fun `invalid inactive date or weekday cannot be silently dropped on read`() = withDatabase { db, repo ->
        for (bad in listOf(entity(1).copy(dueDate = Long.MAX_VALUE), entity(1).copy(repeatDaysOfWeek = "1,bad"))) {
            db.plans().delete(1); seed(db, bad)
            assertTrue(runCatching { repo.get(1) }.isFailure)
        }
    }

    @Test fun `raw SQL parent cycle fails reads and cannot be extended by a normal edit`() = withDatabase { db, repo ->
        seed(db, entity(1)); seed(db, entity(2).copy(parentId = 1))
        db.openHelper.writableDatabase.execSQL("UPDATE plans SET parentId=2 WHERE id=1")
        assertTrue(runCatching { repo.observeAll().first() }.isFailure)
        assertFailsWith<IllegalArgumentException> { repo.save(plan().copy(parentId = 2), emptyList(), token) }
    }

    private suspend fun seed(db: PlanRepositoryTestDatabase, row: PlanEntity) { db.plans().insert(row) }
    private fun entity(id: Long) = PlanEntity(id = id, title = "  目标🌱  ", timeZone = "Asia/Shanghai", createdAt = 1, updatedAt = 2)
    private fun plan() = Plan(title = "  目标🌱  ", timeZone = "Asia/Shanghai", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)
    private fun generation(db: PlanRepositoryTestDatabase): Long = db.openHelper.writableDatabase.query("SELECT generation FROM app_maintenance WHERE id=1").use { it.moveToFirst(); it.getLong(0) }
    private suspend fun <T> success(block: suspend () -> T): T {
        val result = runCatching { block() }
        assertTrue(result.isSuccess, "Expected real successful repository operation: ${result.exceptionOrNull()}")
        return result.getOrThrow()
    }
    private fun withDatabase(onQuery: (String) -> Unit = {}, block: suspend (PlanRepositoryTestDatabase, PlanRepositoryImpl) -> Unit): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PlanRepositoryTestDatabase::class.java)
            .allowMainThreadQueries().setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) { onQuery(sqlQuery) }
            }, Executor { it.run() }).build()
        try {
            db.openHelper.writableDatabase.execSQL("INSERT INTO app_maintenance VALUES(1,0)")
            block(db, PlanRepositoryImpl(db, db.plans(), Clock.fixed(now, ZoneId.of("UTC"))))
        } finally { db.close() }
    }
}
