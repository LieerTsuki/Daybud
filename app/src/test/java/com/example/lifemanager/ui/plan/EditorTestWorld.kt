package com.example.lifemanager.ui.plan

import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.model.Tag
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.common.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Only external persistence/settings/alarm boundaries are substituted; use cases/admission are real. */
class EditorTestWorld(dispatcher:CoroutineDispatcher) {
    val now=Instant.parse("2026-10-14T04:00:00Z")
    val token=DataGeneration(4)
    val generations=TestGenerations(4)
    val coordinator=MaintenanceCoordinator(generations)
    val repo=EditorMemoryPlans(now)
    var effects:suspend (Long,DataGeneration)->Unit={_,_->}
    var settingsFailure=false
    var enabled=true
    val sources=object:PlanReminderSources {
        override suspend fun configuration():PlanReminderConfiguration { check(!settingsFailure);return PlanReminderConfiguration(enabled,true) }
        override suspend fun legacyIds()=LegacyPlanReminderIds()
    }
    val cases=PlanUseCases(repo,coordinator,object:PlanReminderEffects {
        override suspend fun reconcile(planId:Long,expected:DataGeneration)=effects(planId,expected)
    })
    val model=PlanEditorViewModel(repo,cases,GenerationAccess(generations,coordinator),sources,dispatcher,Clock.fixed(now,ZoneId.of("Asia/Shanghai")))
}

class EditorMemoryPlans(private val now:Instant):PlanRepository {
    val rows=MutableStateFlow<List<Plan>>(emptyList())
    val exceptions=MutableStateFlow<List<PlanException>>(emptyList())
    var readsFail=false
    var writesFail:Exception?=null
    var writes=0
    var beforeSave:suspend ()->Unit={}
    private var nextId=40L
    override fun observeAll()=rows.map { check(!readsFail);it }
    override fun observeTags()=MutableStateFlow(emptyList<Tag>())
    override suspend fun get(id:Long):Plan? { check(!readsFail);return rows.value.find { it.id==id } }
    override suspend fun getExceptions(id:Long)=exceptions.value.filter { it.planId==id }
    override suspend fun findLegacyPlan(source:PlanLegacySource,legacyId:Long):Long?=null
    override suspend fun save(plan:Plan,tagNames:List<String>,expected:DataGeneration):Long {
        beforeSave()
        writesFail?.let { throw it };val old=if(plan.id==0L)null else checkNotNull(get(plan.id))
        val id=old?.id ?: ++nextId;writes++
        val saved=plan.copy(id=id,tagNames=tagNames.toList(),createdAt=old?.createdAt ?: now,updatedAt=now,
            isCompleted=old?.isCompleted ?: false,completedAt=old?.completedAt)
        rows.value=rows.value.filterNot { it.id==id }+saved;return id
    }
    override suspend fun setCompleted(id:Long,completed:Boolean,expected:DataGeneration) {
        writesFail?.let { throw it };val old=checkNotNull(get(id));writes++
        val saved=if(completed)PlanRules.complete(old,now)else PlanRules.undoCompletion(old,now)
        rows.value=rows.value.map { if(it.id==id)saved else it }
    }
    override suspend fun delete(id:Long,expected:DataGeneration) { writesFail?.let { throw it };checkNotNull(get(id));writes++;rows.value=rows.value.filterNot { it.id==id } }
    override suspend fun saveException(value:PlanException,expected:DataGeneration) {
        exceptions.value=exceptions.value.filterNot { it.planId==value.planId && it.occurrenceDate==value.occurrenceDate }+value
        rows.value=rows.value.toList()
    }
}
