package com.example.lifemanager.data.plan

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.lifemanager.data.local.migration.PlanV6Schema
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.plan.*
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.plan.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.DayOfWeek
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class PlanEditorRoomTest {
    private val now=Instant.parse("2026-10-14T04:00:00Z")
    private val clock=Clock.fixed(now,ZoneId.of("Asia/Shanghai"))
    private val token=DataGeneration(0)
    private fun p()=Plan(title="原始目标",timeZone="Asia/Shanghai",createdAt=Instant.EPOCH,updatedAt=Instant.EPOCH)

    @Test fun `same row date conversion preserves completion dormant fields and raw weekday text`()=fixture { f ->
        val id=f.cases.save(p().copy(startAt=now,endAt=now.plusSeconds(3600),repeatRule=ScheduleRepeatRule.CUSTOM,
            repeatDaysOfWeek=setOf(DayOfWeek.MONDAY,DayOfWeek.WEDNESDAY)),listOf("标签"),token) {}.receipt.planId
        val raw=f.db.plans().findEntity(id)!!
        f.db.plans().update(raw.copy(repeatDaysOfWeek="1,3,1"))
        f.cases.setCompleted(id,true,token) {}
        f.start();f.wait { it.isAvailable };f.onMain { f.model.open(id,token) };f.wait { it.detail!=null }
        f.onMain { f.model.beginEdit(f.stamp);f.model.setMode(PlanTimeMode.DEADLINE,f.stamp);f.model.setDateOnly(true,f.stamp)
            f.model.change(PlanField.DUE_DATE,"2026-10-20",f.stamp);f.model.save(f.stamp) }
        f.wait { it.pane==PlanPane.DETAIL && !it.isBusy && it.detail?.timeMode==PlanTimeMode.DEADLINE }
        val saved=f.db.plans().findEntity(id)!!
        assertEquals(raw.createdAt,saved.createdAt);assertTrue(saved.isCompleted);assertEquals(now.toEpochMilli(),saved.completedAt)
        assertEquals("1,3,1",saved.repeatDaysOfWeek);assertEquals(raw.startAt,saved.startAt);assertEquals(raw.endAt,saved.endAt)
        assertEquals(listOf("标签"),f.repo.get(id)!!.tagNames)
        f.onMain { f.model.beginEdit(f.stamp);f.model.setMode(PlanTimeMode.NONE,f.stamp);f.model.confirmMode(f.stamp);f.model.save(f.stamp) }
        f.wait { !it.isBusy && it.detail?.timeMode==PlanTimeMode.NONE }
        assertEquals(LocalDate.parse("2026-10-20"),f.repo.get(id)!!.dueDate);assertEquals(listOf(id),f.db.plans().allEntities().map { it.id })
    }
    @Test fun `new Room draft is consumed before alarm failure and retry writes no second record`()=fixture { f ->
        f.start();f.wait { it.isAvailable };f.failEffects=true
        f.onMain { f.model.openNew(token) };f.wait { it.draft!=null && it.isAvailable }
        f.onMain { f.model.change(PlanField.TITLE,"只建一次",f.stamp);f.model.save(f.stamp) }
        f.wait { !it.isBusy && it.reminderReceipt!=null }
        assertNull(f.model.uiState.value.draft);assertEquals(1,f.db.plans().allEntities().size)
        val saved=f.db.plans().allEntities().single();f.failEffects=false
        f.onMain { f.model.retryReminder(f.stamp,saved.id) };f.wait { !it.isBusy && it.reminderReceipt==null }
        assertEquals(listOf(saved),f.db.plans().allEntities())
    }
    @Test fun `confirmed parent deletion unlinks children without deleting their content`()=fixture { f ->
        val parent=f.cases.save(p(),emptyList(),token) {}.receipt.planId
        val child=f.cases.save(p().copy(title="保留子项",parentId=parent),emptyList(),token) {}.receipt.planId
        f.start();f.wait { it.isAvailable };f.onMain { f.model.open(parent,token) };f.wait { it.detail!=null }
        f.onMain { f.model.requestDelete(f.stamp) };assertEquals(2,f.db.plans().allEntities().size)
        f.onMain { f.model.confirmDelete(f.stamp) };f.wait { it.pane==PlanPane.LIST && !it.isBusy }
        assertNull(f.repo.get(parent));assertEquals("保留子项",f.repo.get(child)!!.title);assertNull(f.repo.get(child)!!.parentId)
    }
    @Test fun `changed Room conflict is rechecked before the confirmed insert`()=fixture { f ->
        val other=f.cases.save(p().copy(timeMode=PlanTimeMode.TIMED,startAt=Instant.parse("2026-10-20T04:00:00Z"),endAt=Instant.parse("2026-10-20T05:00:00Z")),emptyList(),token) {}.receipt.planId
        f.start();f.wait { it.isAvailable };f.onMain { f.model.openNew(token) };f.wait { it.draft!=null && it.isAvailable }
        f.onMain { f.model.change(PlanField.TITLE,"新日程",f.stamp);f.model.setMode(PlanTimeMode.TIMED,f.stamp)
            f.model.change(PlanField.START_AT,"2026-10-20T12:00+08:00",f.stamp);f.model.change(PlanField.END_AT,"2026-10-20T13:00+08:00",f.stamp);f.model.save(f.stamp) }
        f.wait { !it.isBusy && it.conflicts!=null };assertEquals(1,f.db.plans().allEntities().size)
        val changed=f.repo.get(other)!!.copy(title="冲突记录已更新")
        f.cases.save(changed,changed.tagNames,token) {}
        f.onMain { f.model.save(f.stamp,true) }
        f.wait { !it.isBusy && it.conflicts?.conflicts?.singleOrNull()?.title=="冲突记录已更新" }
        assertEquals(1,f.db.plans().allEntities().size)
        f.onMain { f.model.save(f.stamp,true) };f.wait { !it.isBusy && it.pane==PlanPane.DETAIL && it.detail?.title=="新日程" }
        assertEquals(2,f.db.plans().allEntities().size)
    }
    @Test fun `real SQL generation replacement invalidates the draft before reused ID can save`()=fixture { f ->
        val id=f.cases.save(p(),emptyList(),token) {}.receipt.planId
        f.start();f.wait { it.isAvailable };f.onMain { f.model.open(id,token) };f.wait { it.detail!=null }
        lateinit var old:PlanEditorStamp
        f.onMain { f.model.beginEdit(f.stamp);f.model.change(PlanField.TITLE,"旧草稿",f.stamp);old=f.stamp }
        f.coordinator.withSession { session -> f.coordinator.withMaintenance(session) { f.generations.commit(session.generation) {
            val row=f.db.plans().findEntity(id)!!;f.db.plans().delete(id);f.db.plans().insert(row.copy(title="替换记录"))
        } } }
        f.wait { it.isAvailable && it.stamp.generation==DataGeneration(1) && it.pane==PlanPane.LIST }
        f.onMain { f.model.save(old) };assertEquals("替换记录",f.repo.get(id)!!.title);assertNull(f.model.uiState.value.draft)
    }
    @Test fun `checked deletion rejects a changed SQL snapshot without removing current content`()=fixture { f ->
        val id=f.cases.save(p(),listOf("保留标签"),token) {}.receipt.planId
        val confirmed=f.repo.get(id)!!
        f.cases.save(confirmed.copy(description="删除确认后的新内容"),confirmed.tagNames,token) {}
        assertFailsWith<PlanEditChangedException> { f.cases.deleteChecked(confirmed,token) {} }
        assertEquals("删除确认后的新内容",f.repo.get(id)!!.description)
        assertEquals(listOf("保留标签"),f.repo.get(id)!!.tagNames);assertEquals(listOf(id),f.db.plans().allEntities().map { it.id })
    }

    private inner class Fixture(val db:PlanMigrationTestDatabase) {
        val repo=PlanRepositoryImpl(db,db.plans(),clock)
        val generations=object:DataGenerationRepository {
            override fun observe()=callbackFlow {
                val observer=object:InvalidationTracker.Observer("app_maintenance") {override fun onInvalidated(tables:Set<String>) { trySend(Unit) }}
                db.invalidationTracker.addObserver(observer);trySend(Unit);awaitClose { db.invalidationTracker.removeObserver(observer) }
            }.map { current() }.distinctUntilChanged()
            override suspend fun current()=db.withTransaction { db.plans().read(SimpleSQLiteQuery("SELECT generation FROM app_maintenance WHERE id=1")).use { check(it.moveToFirst());DataGeneration(it.getLong(0)) } }
            override suspend fun commit(expected:DataGeneration,mutation:suspend ()->Unit)=db.withTransaction {
                check(current()==expected);val next=DataGeneration(Math.addExact(expected.value,1));mutation();check(current()==expected)
                db.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=? WHERE id=1",arrayOf(next.value));next
            }
        }
        val coordinator=MaintenanceCoordinator(generations)
        @Volatile var failEffects=false
        val cases=PlanUseCases(repo,coordinator,object:PlanReminderEffects {override suspend fun reconcile(planId:Long,expected:DataGeneration) { check(!failEffects);repo.get(planId) }})
        lateinit var model:PlanEditorViewModel
        val stamp get()=model.uiState.value.stamp
        fun start() { model=PlanEditorViewModel(repo,cases,GenerationAccess(generations,coordinator),RoomPlanReminderSources(db.plans()),Dispatchers.IO,clock) }
        suspend fun onMain(operation:()->Unit)=withContext(Dispatchers.Main) { operation() }
        suspend fun wait(predicate:(PlanEditorState)->Boolean)=withTimeout(15_000) { model.uiState.first(predicate) }
        suspend fun close() { if(::model.isInitialized)model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    private fun fixture(block:suspend (Fixture)->Unit)=runBlocking {
        val ui=Executors.newSingleThreadExecutor().asCoroutineDispatcher();Dispatchers.setMain(ui)
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),PlanMigrationTestDatabase::class.java).addCallback(PlanV6Schema.INITIALIZE).build()
        val f=Fixture(db)
        try { block(f) } finally { f.close();db.close();Dispatchers.resetMain();ui.close() }
    }
}
