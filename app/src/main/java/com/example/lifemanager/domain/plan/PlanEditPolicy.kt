package com.example.lifemanager.domain.plan

import java.time.ZoneId
import kotlinx.coroutines.flow.first

data class PlanConflictProof(val candidate: Plan, val window: PlanWindow, val conflicts: List<Plan>, val exceptions: List<PlanException>)
data class PlanEditPolicy(val original: Plan?, val confirmed: PlanConflictProof? = null)
class PlanConflictConfirmationRequired(val proof: PlanConflictProof): IllegalStateException("存在时间冲突，请确认")
class PlanEditChangedException: IllegalStateException("记录已更改，请保留未保存输入并重新读取后操作")

internal fun Plan.ownedEditSnapshot()=copy(tagIds=tagIds.toSet(),tagNames=tagNames.toList(),repeatDaysOfWeek=repeatDaysOfWeek.toSet())
internal fun PlanConflictProof.ownedEditSnapshot()=copy(candidate=candidate.ownedEditSnapshot(),
    conflicts=conflicts.map { it.ownedEditSnapshot() },exceptions=exceptions.toList())

/** Caller owns global admission then Plan lock. No dialog/user interaction or nested admission here. */
internal suspend fun verifyPlanEdit(repository:PlanRepository,plan:Plan,policy:PlanEditPolicy):Plan {
    require(plan.id==(policy.original?.id ?: 0L)) { "编辑身份不匹配" }
    val current=if(plan.id==0L)null else repository.get(plan.id) ?: throw PlanEditChangedException()
    if(current!=null) {
        val original=checkNotNull(policy.original)
        if(original.copy(isCompleted=current.isCompleted,completedAt=current.completedAt,updatedAt=current.updatedAt)!=current)
            throw PlanEditChangedException()
    }
    require(PlanRules.validateEdit(plan,current)==null) { "计划表单无效" }
    val candidate=plan.copy(isCompleted=current?.isCompleted ?: false,completedAt=current?.completedAt)
    if(candidate.isCompleted || candidate.timeMode !in listOf(PlanTimeMode.TIMED,PlanTimeMode.ALL_DAY)) return candidate
    val zone=ZoneId.of(candidate.timeZone)
    val first=when(candidate.timeMode) {
        PlanTimeMode.TIMED->candidate.startAt!!.atZone(zone).toLocalDate()
        else->candidate.allDayStartDate!!
    }
    val last=when(candidate.timeMode) {
        PlanTimeMode.TIMED->candidate.endAt!!.atZone(zone).toLocalDate().plusDays(1)
        else->candidate.allDayEndDate!!.plusDays(1)
    }
    val window=PlanWindow(first.atStartOfDay(zone).toInstant(),maxOf(first.plusDays(30),last).atStartOfDay(zone).toInstant())
    val all=repository.observeAll().first()
    // An undated persisted candidate may retain cancellation exceptions when reactivated.
    val exceptionIds=all.filter { it.isScheduled }.map { it.id }.toSet()+listOfNotNull(candidate.id.takeIf { it>0 })
    val exceptions=exceptionIds.flatMap { repository.getExceptions(it) }
    val conflicts=PlanConflicts.find(candidate,all,window,exceptions).sortedBy { it.id }
    if(conflicts.isNotEmpty()) {
        val relevant=conflicts.map { it.id }.toSet()+candidate.id
        val proof=PlanConflictProof(candidate.ownedEditSnapshot(),window,conflicts.map { it.ownedEditSnapshot() },
            exceptions.filter { it.planId in relevant }.sortedWith(compareBy<PlanException> { it.planId }.thenBy { it.occurrenceDate }))
        if(policy.confirmed!=proof) throw PlanConflictConfirmationRequired(proof)
    }
    return candidate
}
