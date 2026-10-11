package com.example.lifemanager.domain.plan

import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.model.Tag
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

data class PlanException(val planId: Long, val occurrenceDate: LocalDate, val isCancelled: Boolean)
enum class PlanLegacySource { TODO, SCHEDULE }

/** Mutations must be admitted by the shared maintenance coordinator before invoking this boundary.
 * Each mutation additionally checks the original token atomically with its Room write.
 */
interface PlanRepository {
    fun observeAll(): Flow<List<Plan>>
    fun observeTags(): Flow<List<Tag>>
    suspend fun get(id: Long): Plan?
    suspend fun getExceptions(id: Long): List<PlanException>
    suspend fun findLegacyPlan(source: PlanLegacySource, legacyId: Long): Long?
    /** tagNames is the desired selection. Exact existing raw names retain their linked identity;
     * only newly added names are trimmed. Completion is a separate operation, not overwritten by edit.
     */
    suspend fun save(plan: Plan, tagNames: List<String>, expected: DataGeneration): Long
    suspend fun setCompleted(id: Long, completed: Boolean, expected: DataGeneration)
    suspend fun delete(id: Long, expected: DataGeneration)
    suspend fun saveException(value: PlanException, expected: DataGeneration)
}
