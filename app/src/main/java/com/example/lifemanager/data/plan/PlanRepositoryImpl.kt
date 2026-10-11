package com.example.lifemanager.data.plan

import android.database.Cursor
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.lifemanager.data.local.entity.TagEntity
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import com.example.lifemanager.domain.model.Tag
import com.example.lifemanager.domain.plan.Plan
import com.example.lifemanager.domain.plan.PlanException
import com.example.lifemanager.domain.plan.PlanLegacySource
import com.example.lifemanager.domain.plan.PlanRepository
import com.example.lifemanager.domain.plan.PlanRules
import com.example.lifemanager.domain.plan.cancellablePlanWriteBody
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PlanRepositoryImpl(
    private val database: RoomDatabase,
    private val dao: PlanDao,
    private val clock: Clock,
) : PlanRepository {
    override fun observeAll(): Flow<List<Plan>> = dao.observeAll().map { rows ->
        validateTree(rows.map { it.entity })
        rows.map { it.toPlan() }
    }

    override fun observeTags(): Flow<List<Tag>> = dao.observeTags().map { rows -> rows.map { it.toPlanTag() } }

    override suspend fun get(id: Long): Plan? = database.withTransaction {
        val row = dao.find(id) ?: return@withTransaction null
        validateParent(id, row.entity.parentId)
        row.toPlan()
    }

    override suspend fun getExceptions(id: Long): List<PlanException> = database.withTransaction {
        dao.exceptions(id).map { it.toDomain() }
    }

    override suspend fun findLegacyPlan(source: PlanLegacySource, legacyId: Long): Long? =
        dao.findLegacy(LegacyPlanSource.valueOf(source.name), legacyId)

    override suspend fun save(plan: Plan, tagNames: List<String>, expected: DataGeneration): Long = mutate(expected) {
        require(plan.id >= 0) { "Invalid plan identity" }
        val error = PlanRules.validate(plan)
        require(error == null) { "Invalid edited plan: $error" }
        val stored = if (plan.id == 0L) null else requireExisting(plan.id)
        val previous = stored?.entity
        if (previous == null) require(!plan.isCompleted && plan.completedAt == null) { "New plan must start pending" }
        validateParent(plan.id, plan.parentId)
        val now = clock.instant()
        val entity = plan.toEntity(now, previous)
        val id = if (previous == null) dao.insert(entity).also { check(it > 0) }
            else { check(dao.update(entity) == 1) { "Plan disappeared during edit" }; plan.id }
        val linkedTags = stored?.tags.orEmpty().associateBy { it.name }
        val tags = tagNames.mapNotNull { rawName ->
            // Historical raw names (including blank ones) identify existing linked tags exactly.
            linkedTags[rawName] ?: rawName.trim().takeIf(String::isNotEmpty)?.let { name ->
                dao.findTag(name) ?: run {
                    dao.insertTag(TagEntity(name = name, color = 0xFF000000.toInt() or (name.hashCode() and 0x00FFFFFF),
                        createdAt = now.toEpochMilli()))
                    checkNotNull(dao.findTag(name)) { "Tag could not be saved" }
                }
            }
        }.distinctBy { it.id }
        dao.deleteLinks(id)
        currentCoroutineContext().ensureActive()
        dao.insertLinks(tags.map { PlanTagCrossRef(id, it.id) })
        id
    }

    override suspend fun setCompleted(id: Long, completed: Boolean, expected: DataGeneration): Unit = mutate(expected) {
        val row = requireExisting(id).entity
        val current = row.toDomain()
        val updated = if (completed) PlanRules.complete(current, clock.instant())
            else PlanRules.undoCompletion(current, clock.instant())
        if (updated != current) {
            check(dao.update(row.copy(isCompleted = updated.isCompleted,
                completedAt = updated.completedAt?.toEpochMilli(), updatedAt = updated.updatedAt.toEpochMilli())) == 1)
        }
    }

    override suspend fun delete(id: Long, expected: DataGeneration): Unit = mutate(expected) {
        check(dao.delete(id) == 1) { "Plan no longer exists" }
    }

    override suspend fun saveException(value: PlanException, expected: DataGeneration): Unit = mutate(expected) {
        requireExisting(value.planId)
        val entity = PlanExceptionEntity(value.planId, value.occurrenceDate.toEpochDay(), value.isCancelled)
        if (dao.updateException(entity) == 0) dao.insertException(entity)
    }

    private suspend fun requireExisting(id: Long): PlanWithRelations {
        check(id > 0) { "Invalid plan identity" }
        val row = checkNotNull(dao.find(id)) { "Plan no longer exists" }
        validateParent(id, row.entity.parentId)
        row.toPlan()
        return row
    }

    private suspend fun validateParent(id: Long, parentId: Long?) {
        val path = HashSet<Long>()
        if (id > 0) path.add(id)
        var current = parentId
        while (current != null) {
            require(current > 0 && path.add(current)) { "Invalid parent or parent cycle" }
            current = requireNotNull(dao.findEntity(current)) { "Missing parent plan" }.parentId
        }
    }

    private fun validateTree(rows: List<PlanEntity>) {
        val parents = rows.associate { it.id to it.parentId }
        val done = HashSet<Long>()
        for (id in parents.keys) {
            val path = HashSet<Long>()
            var current: Long? = id
            while (current != null && current !in done) {
                require(current > 0 && current in parents && path.add(current)) { "Missing parent or parent cycle" }
                current = parents.getValue(current)
            }
            done.addAll(path)
        }
    }

    private fun PlanWithRelations.toPlan(): Plan {
        exceptions.forEach { it.toDomain() }
        return entity.toDomain(tags.sortedWith(compareBy<TagEntity> { it.name }.thenBy { it.id }))
    }

    private fun PlanExceptionEntity.toDomain(): PlanException =
        PlanException(planId, LocalDate.ofEpochDay(occurrenceDate), isCancelled)

    private suspend fun <T> mutate(expected: DataGeneration, block: suspend () -> T): T = database.withTransaction {
        cancellablePlanWriteBody {
            checkGeneration(expected)
            val result = block()
            currentCoroutineContext().ensureActive()
            // Detect unexpected metadata changes made by triggers before committing business writes.
            checkGeneration(expected)
            result
        }
    }

    private fun checkGeneration(expected: DataGeneration) {
        dao.read(SimpleSQLiteQuery("SELECT id, generation FROM app_maintenance")).use { cursor ->
            check(cursor.moveToFirst() && cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER &&
                cursor.getLong(0) == 1L && cursor.getType(1) == Cursor.FIELD_TYPE_INTEGER) { "Local generation missing or corrupt" }
            val generation = cursor.getLong(1)
            check(generation >= 0 && !cursor.moveToNext()) { "Invalid local generation" }
            if (generation != expected.value) throw StaleGenerationException()
        }
    }
}
