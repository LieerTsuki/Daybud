package com.example.lifemanager.data.plan

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.data.local.V6DeviceFixture
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import com.example.lifemanager.domain.plan.Plan
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

/** Production v6 factory and real Android SQLite. Execution is parent-owned. */
@RunWith(AndroidJUnit4::class)
class PlanDaoDeviceTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC)

    @Test fun editAndCompletionPreserveIdentityCreationTagsAndCancelledException() = fixture { db, repository ->
        val id = repository.save(plan("original"), listOf("tag"), DataGeneration(0))
        db.planDao().insertException(PlanExceptionEntity(id, 21000, true))
        val before = repository.get(id)!!
        val links = before.tagIds
        repository.save(before.copy(title = "edited", createdAt = Instant.MAX), listOf("tag"), DataGeneration(0))
        repository.setCompleted(id, true, DataGeneration(0))
        val completed = repository.get(id)!!
        assertEquals(id, completed.id)
        assertEquals(before.createdAt, completed.createdAt)
        assertEquals("edited", completed.title)
        assertEquals(links, completed.tagIds)
        assertTrue(completed.isCompleted)
        assertEquals(clock.instant(), completed.completedAt)
        assertEquals(listOf(PlanExceptionEntity(id, 21000, true)), db.planDao().exceptions(id))
        repository.setCompleted(id, true, DataGeneration(0))
        assertEquals(completed, repository.get(id))
        repository.setCompleted(id, false, DataGeneration(0))
        assertFalse(repository.get(id)!!.isCompleted)
        assertNull(repository.get(id)!!.completedAt)
    }

    @Test fun deletingParentDetachesChildAndCascadesOnlyParentRelations() = fixture { db, repository ->
        val parent = repository.save(plan("parent"), listOf("shared"), DataGeneration(0))
        val child = repository.save(plan("child").copy(parentId = parent), listOf("shared"), DataGeneration(0))
        db.planDao().insertException(PlanExceptionEntity(parent, 21000, true))
        db.planDao().insertException(PlanExceptionEntity(child, 21001, false))
        db.planDao().insertLegacyRef(PlanLegacyRefEntity(LegacyPlanSource.TODO, -41, parent))
        repository.delete(parent, DataGeneration(0))
        assertNull(repository.get(parent))
        assertNull(repository.get(child)!!.parentId)
        assertEquals(listOf("shared"), repository.get(child)!!.tagNames)
        assertTrue(db.planDao().exceptions(parent).isEmpty())
        assertEquals(listOf(PlanExceptionEntity(child, 21001, false)), db.planDao().exceptions(child))
        assertNull(db.planDao().findLegacy(LegacyPlanSource.TODO, -41))
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }

    @Test fun duplicateLinksAndDanglingOwnersAreRejectedWithoutLosingExistingRelations() = fixture { db, repository ->
        val id = repository.save(plan("linked"), listOf("tag"), DataGeneration(0))
        val tag = repository.get(id)!!.tagIds.single()
        assertFails { db.planDao().insertLinks(listOf(PlanTagCrossRef(id, tag))) }
        assertFails { db.planDao().insertLinks(listOf(PlanTagCrossRef(id, 999999))) }
        assertFails { db.planDao().insertException(PlanExceptionEntity(999999, 21000, true)) }
        assertEquals(setOf(tag), repository.get(id)!!.tagIds)
        assertTrue(db.planDao().exceptions(id).isEmpty())
        assertEquals(DataGeneration(0), DataGenerationRepositoryImpl(db).current())
    }

    @Test fun staleGenerationCannotSaveCompleteOrDeleteRestoredSameIdentity() = fixture { db, repository ->
        val id = repository.save(plan("original"), emptyList(), DataGeneration(0))
        val old = repository.get(id)!!
        val generations = DataGenerationRepositoryImpl(db)
        generations.commit(DataGeneration(0)) {
            db.planDao().delete(id)
            db.planDao().insert(PlanEntity(id = id, title = "restored", timeZone = "UTC", createdAt = 7, updatedAt = 8))
        }
        assertFailsWith<StaleGenerationException> { repository.save(old.copy(title = "stale"), listOf("unwanted"), DataGeneration(0)) }
        assertFailsWith<StaleGenerationException> { repository.setCompleted(id, true, DataGeneration(0)) }
        assertFailsWith<StaleGenerationException> { repository.delete(id, DataGeneration(0)) }
        val restored = repository.get(id)!!
        assertEquals("restored", restored.title)
        assertFalse(restored.isCompleted)
        assertTrue(restored.tagIds.isEmpty())
        assertEquals(DataGeneration(1), generations.current())
    }

    @Test fun deletedEditorCannotRecreateRowOrCreateTags() = fixture { _, repository ->
        val id = repository.save(plan("original"), emptyList(), DataGeneration(0))
        val old = repository.get(id)!!
        repository.delete(id, DataGeneration(0))
        assertFailsWith<IllegalStateException> { repository.save(old.copy(title = "stale"), listOf("unwanted"), DataGeneration(0)) }
        assertNull(repository.get(id))
    }

    private fun plan(title: String) = Plan(title = title, timeZone = "UTC", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)
    private fun fixture(block: suspend (LifeManagerDatabase, PlanRepositoryImpl) -> Unit): Unit = runBlocking {
        val fixture = V6DeviceFixture()
        val db = fixture.database
        try { block(db, PlanRepositoryImpl(db, db.planDao(), clock)) } finally { fixture.close() }
    }
}
