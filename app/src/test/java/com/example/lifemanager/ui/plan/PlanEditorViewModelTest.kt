package com.example.lifemanager.ui.plan

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.plan.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanEditorViewModelTest {
    private fun p()=Plan(id=7,title="原始目标",timeZone="Asia/Shanghai",createdAt=Instant.EPOCH,updatedAt=Instant.EPOCH)
    private val EditorTestWorld.stamp get()=model.uiState.value.stamp
    private fun EditorTestWorld.change(field:PlanField,text:String)=model.change(field,text,stamp)
    @Test fun `new draft is blank and user save creates one real identity`()=scenario { f ->
        f.model.openNew(f.token);advanceUntilIdle()
        assertEquals("",f.model.uiState.value.draft!!.title)
        f.change(PlanField.TITLE,"新目标");f.model.save(f.stamp);advanceUntilIdle()
        assertEquals(listOf("新目标"),f.repo.rows.value.map { it.title })
        assertNull(f.model.uiState.value.draft);assertEquals(PlanPane.DETAIL,f.model.uiState.value.pane)
        assertEquals(41,f.model.uiState.value.detail!!.id)
    }
    @Test fun `detail load and edit retain original identity and classification`()=scenario { f ->
        f.repo.rows.value=listOf(p());f.model.open(7,f.token);advanceUntilIdle()
        assertEquals(p(),f.model.uiState.value.detail)
        f.model.beginEdit(f.stamp);f.change(PlanField.TITLE,"修改")
        f.model.save(f.stamp);advanceUntilIdle()
        assertEquals(7,f.repo.rows.value.single().id);assertEquals("修改",f.repo.rows.value.single().title)
        assertEquals(Instant.EPOCH,f.repo.rows.value.single().createdAt)
    }
    @Test fun `deadline conversion preserves completion and then undates same plan`()=scenario { f ->
        f.repo.rows.value=listOf(p().copy(isCompleted=true,completedAt=f.now));f.model.open(7,f.token);advanceUntilIdle()
        f.model.beginEdit(f.stamp);f.model.setMode(PlanTimeMode.DEADLINE,f.stamp);f.model.setDateOnly(true,f.stamp)
        f.change(PlanField.DUE_DATE,"2026-10-20");f.model.save(f.stamp);advanceUntilIdle()
        assertEquals(PlanTimeMode.DEADLINE,f.repo.rows.value.single().timeMode);assertTrue(f.repo.rows.value.single().isCompleted)
        f.model.beginEdit(f.stamp);f.model.setMode(PlanTimeMode.NONE,f.stamp);f.model.confirmMode(f.stamp)
        f.model.save(f.stamp);advanceUntilIdle()
        assertEquals(PlanTimeMode.NONE,f.repo.rows.value.single().timeMode)
        assertEquals(LocalDate.parse("2026-10-20"),f.repo.rows.value.single().dueDate)
    }
    @Test fun `mode change waits for explicit confirmation and cancellation keeps draft`()=scenario { f ->
        f.repo.rows.value=listOf(p().copy(timeMode=PlanTimeMode.TIMED,startAt=f.now,endAt=f.now.plusSeconds(3600)))
        f.model.open(7,f.token);advanceUntilIdle();f.model.beginEdit(f.stamp)
        f.change(PlanField.DESCRIPTION,"草稿备注")
        f.model.setMode(PlanTimeMode.NONE,f.stamp)
        assertEquals(PlanTimeMode.TIMED,f.model.uiState.value.draft!!.mode)
        assertEquals(PlanTimeMode.NONE,f.model.uiState.value.pendingMode)
        f.model.cancelMode(f.stamp);assertEquals("草稿备注",f.model.uiState.value.draft!!.description)
        f.model.setMode(PlanTimeMode.NONE,f.stamp);f.model.confirmMode(f.stamp)
        assertEquals(PlanTimeMode.NONE,f.model.uiState.value.draft!!.mode)
        assertEquals(f.now,f.model.uiState.value.draft!!.base.startAt)
    }
    @Test fun `invalid save preserves all user input`()=scenario { f ->
        f.model.openNew(f.token);advanceUntilIdle();f.change(PlanField.DESCRIPTION,"未保存")
        f.model.save(f.stamp);advanceUntilIdle()
        assertEquals(0,f.repo.writes);assertEquals("未保存",f.model.uiState.value.draft!!.description)
        assertNotNull(f.model.uiState.value.error)
    }
    @Test fun `dirty close waits and cancellation retains fields`()=scenario { f ->
        f.model.openNew(f.token);advanceUntilIdle();f.change(PlanField.TITLE,"草稿")
        f.model.requestClose(f.stamp);assertTrue(f.model.uiState.value.confirmClose)
        f.model.cancelClose(f.stamp);assertEquals("草稿",f.model.uiState.value.draft!!.title)
        f.model.requestClose(f.stamp);f.model.confirmClose(f.stamp)
        assertEquals(PlanPane.LIST,f.model.uiState.value.pane);assertNull(f.model.uiState.value.draft)
    }
    @Test fun `opening another item cannot silently discard an unsaved draft`()=scenario { f ->
        f.repo.rows.value=listOf(p());f.model.openNew(f.token);advanceUntilIdle();f.change(PlanField.TITLE,"草稿")
        f.model.open(7,f.token);advanceUntilIdle()
        assertTrue(f.model.uiState.value.confirmClose);assertEquals("草稿",f.model.uiState.value.draft!!.title)
        f.model.confirmClose(f.stamp);advanceUntilIdle();assertEquals(7,f.model.uiState.value.detail!!.id)
    }
    @Test fun `maintenance rejects saving but preserves draft after cancellation`()=scenario { f ->
        f.model.openNew(f.token);advanceUntilIdle();f.change(PlanField.TITLE,"保留")
        val release=CompletableDeferred<Unit>();val job=launch { f.coordinator.withSession { release.await() } };runCurrent()
        f.model.save(f.stamp);runCurrent();assertEquals(0,f.repo.writes)
        release.complete(Unit);job.join();advanceUntilIdle()
        assertEquals("保留",f.model.uiState.value.draft!!.title)
        assertFalse(f.model.uiState.value.isMaintaining)
    }
    @Test fun `generation replacement invalidates draft and old callbacks`()=scenario { f ->
        f.model.openNew(f.token);advanceUntilIdle();f.change(PlanField.TITLE,"旧草稿");val old=f.stamp
        f.generations.commit(f.token) {};advanceUntilIdle()
        assertNull(f.model.uiState.value.draft);assertEquals(PlanPane.LIST,f.model.uiState.value.pane)
        f.model.save(old);advanceUntilIdle();assertEquals(0,f.repo.writes)
    }
    @Test fun `old same generation form cannot modify a newly opened draft`()=scenario { f ->
        f.model.openNew(f.token);advanceUntilIdle();val old=f.stamp
        f.model.requestClose(f.stamp);f.model.openNew(f.token);advanceUntilIdle()
        f.model.change(PlanField.TITLE,"过期回调",old)
        assertEquals("",f.model.uiState.value.draft!!.title)
    }
    @Test fun `deleted record cannot be recreated by an old form`()=scenario { f ->
        f.repo.rows.value=listOf(p());f.model.open(7,f.token);advanceUntilIdle();f.model.beginEdit(f.stamp)
        f.change(PlanField.TITLE,"不复活");f.repo.rows.value=emptyList();advanceUntilIdle()
        f.model.save(f.stamp);advanceUntilIdle();assertTrue(f.repo.rows.value.isEmpty());assertEquals(0,f.repo.writes)
    }
    @Test fun `committed new draft is consumed before reminder failure and retry`()=scenario { f ->
        f.effects={_,_->error("alarm failure")};f.model.openNew(f.token);advanceUntilIdle();f.change(PlanField.TITLE,"只建一次")
        f.model.save(f.stamp);advanceUntilIdle()
        assertNull(f.model.uiState.value.draft);assertNotNull(f.model.uiState.value.reminderReceipt)
        f.model.save(f.stamp);advanceUntilIdle();assertEquals(1,f.repo.writes)
        f.effects={_,_->};f.model.retryReminder(f.stamp,f.model.uiState.value.reminderReceipt!!.planId);advanceUntilIdle()
        assertNull(f.model.uiState.value.reminderReceipt);assertEquals(1,f.repo.writes)
    }
    @Test fun `committed cancellation retains saved identity not an insertable draft`()=scenario { f ->
        f.effects={_,_->throw CancellationException("cancelled after commit")}
        f.model.openNew(f.token);advanceUntilIdle();f.change(PlanField.TITLE,"已提交")
        f.model.save(f.stamp);advanceUntilIdle()
        assertNull(f.model.uiState.value.draft);assertNotNull(f.model.uiState.value.reminderReceipt);assertEquals(1,f.repo.writes)
        assertFalse(f.model.uiState.value.isBusy)
    }
    @Test fun `duplicate save remains blocked through reminder side effects`()=scenario { f ->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();f.effects={_,_->entered.complete(Unit);release.await()}
        f.model.openNew(f.token);advanceUntilIdle();f.change(PlanField.TITLE,"一次")
        f.model.save(f.stamp);runCurrent()
        assertTrue(entered.isCompleted);assertNull(f.model.uiState.value.draft)
        f.model.save(f.stamp);runCurrent();assertEquals(1,f.repo.writes)
        release.complete(Unit);advanceUntilIdle();assertFalse(f.model.uiState.value.isBusy)
    }
    @Test fun `ordinary write failure retains draft and does not claim saved`()=scenario { f ->
        f.repo.writesFail=IllegalStateException("disk failure");f.model.openNew(f.token);advanceUntilIdle();f.change(PlanField.TITLE,"保留")
        f.model.save(f.stamp);advanceUntilIdle();assertEquals("保留",f.model.uiState.value.draft!!.title)
        assertNotNull(f.model.uiState.value.error);assertNull(f.model.uiState.value.reminderReceipt)
    }
    @Test fun `closed global reminders is displayed without blocking offline CRUD`()=scenario { f ->
        f.enabled=false;f.model.retryRead();advanceUntilIdle()
        f.model.openNew(f.token);advanceUntilIdle();assertEquals(false,f.model.uiState.value.remindersEnabled)
        f.change(PlanField.TITLE,"离线");f.model.save(f.stamp);advanceUntilIdle();assertEquals(1,f.repo.writes)
    }
    @Test fun `failed settings read is not assumed enabled and retry recovers`()=scenario { f ->
        f.settingsFailure=true;f.model.retryRead();advanceUntilIdle();assertFalse(f.model.uiState.value.isAvailable)
        f.model.openNew(f.token);advanceUntilIdle();assertNull(f.model.uiState.value.draft)
        f.settingsFailure=false;f.model.retryRead();advanceUntilIdle();assertTrue(f.model.uiState.value.isAvailable)
    }
    @Test fun `detail completion and deletion require real confirmed actions`()=scenario { f ->
        f.repo.rows.value=listOf(p());f.model.open(7,f.token);advanceUntilIdle()
        f.model.setCompleted(true,f.stamp);advanceUntilIdle();assertTrue(f.repo.rows.value.single().isCompleted)
        f.model.setCompleted(false,f.stamp);advanceUntilIdle();assertFalse(f.repo.rows.value.single().isCompleted)
        f.model.requestDelete(f.stamp);assertEquals(1,f.repo.rows.value.size)
        f.model.confirmDelete(f.stamp);advanceUntilIdle();assertTrue(f.repo.rows.value.isEmpty());assertEquals(PlanPane.LIST,f.model.uiState.value.pane)
    }
    @Test fun `callbacks from saved form cannot edit a later form for the same ID`()=scenario { f ->
        f.repo.rows.value=listOf(p());f.model.open(7,f.token);advanceUntilIdle();f.model.beginEdit(f.stamp)
        val old=f.stamp;f.change(PlanField.TITLE,"第一次");f.model.save(f.stamp);advanceUntilIdle()
        f.model.beginEdit(f.stamp);f.model.change(PlanField.TITLE,"第二次",f.stamp)
        f.model.change(PlanField.DESCRIPTION,"过期回调",old)
        assertEquals("",f.model.uiState.value.draft!!.description)
        assertEquals("第二次",f.model.uiState.value.draft!!.title)
        // Rapid field events in the SAME form remain accepted even before recomposition.
        val current=f.stamp;f.model.change(PlanField.TITLE,"快输入",current);f.model.change(PlanField.DESCRIPTION,"同表单",current)
        assertEquals("同表单",f.model.uiState.value.draft!!.description)
    }
    @Test fun `reactivating current unsaved interval or all day fields requires confirmation`()=scenario { f ->
        for(mode in listOf(PlanTimeMode.TIMED,PlanTimeMode.ALL_DAY)) {
            f.model.openNew(f.token);advanceUntilIdle();f.model.setMode(mode,f.stamp)
            if(mode==PlanTimeMode.TIMED) { f.change(PlanField.START_AT,"2026-10-20T12:00+08:00");f.change(PlanField.END_AT,"2026-10-20T13:00+08:00") }
            else { f.change(PlanField.ALL_DAY_START,"2026-10-20");f.change(PlanField.ALL_DAY_END,"2026-10-21") }
            f.model.setMode(PlanTimeMode.NONE,f.stamp);f.model.confirmMode(f.stamp);f.model.setMode(mode,f.stamp)
            assertEquals(PlanTimeMode.NONE,f.model.uiState.value.draft!!.mode);assertEquals(mode,f.model.uiState.value.pendingMode)
            f.model.cancelMode(f.stamp);f.model.requestClose(f.stamp);f.model.confirmClose(f.stamp);advanceUntilIdle()
        }
    }
    @Test fun `detail change invalidates the old deletion confirmation`()=scenario { f ->
        f.repo.rows.value=listOf(p());f.model.open(7,f.token);advanceUntilIdle();f.model.requestDelete(f.stamp);val old=f.stamp
        f.repo.rows.value=listOf(p().copy(title="另一处修改"));advanceUntilIdle()
        assertFalse(f.model.uiState.value.confirmDelete)
        f.model.confirmDelete(old);advanceUntilIdle();assertEquals("另一处修改",f.repo.rows.value.single().title)
    }
    @Test fun `old conflict confirmation cannot approve its replacement proof`()=scenario { f ->
        val conflict=p().copy(timeMode=PlanTimeMode.TIMED,startAt=Instant.parse("2026-10-20T04:00:00Z"),endAt=Instant.parse("2026-10-20T05:00:00Z"))
        f.repo.rows.value=listOf(conflict);f.model.openNew(f.token);advanceUntilIdle()
        f.change(PlanField.TITLE,"冲突草稿");f.model.setMode(PlanTimeMode.TIMED,f.stamp)
        f.change(PlanField.START_AT,"2026-10-20T12:00+08:00");f.change(PlanField.END_AT,"2026-10-20T13:00+08:00")
        f.model.save(f.stamp);advanceUntilIdle();val old=f.stamp
        f.repo.rows.value=listOf(conflict.copy(title="新的冲突内容"));advanceUntilIdle()
        f.model.save(old,true);advanceUntilIdle();assertEquals("新的冲突内容",f.model.uiState.value.conflicts!!.conflicts.single().title)
        f.model.save(old,true);advanceUntilIdle();assertEquals(0,f.repo.writes)
        f.model.save(f.stamp,true);advanceUntilIdle();assertEquals(1,f.repo.writes)
    }
    @Test fun `cancelled close callback cannot discard for a different pending target`()=scenario { f ->
        f.repo.rows.value=listOf(p(),p().copy(id=8,title="下一个目标"));f.model.openNew(f.token);advanceUntilIdle();f.change(PlanField.TITLE,"保留草稿")
        f.model.open(7,f.token);val old=f.stamp;f.model.cancelClose(f.stamp)
        f.model.open(8,f.token);f.model.confirmClose(old);advanceUntilIdle()
        assertEquals("保留草稿",f.model.uiState.value.draft?.title);assertTrue(f.model.uiState.value.confirmClose)
        f.model.confirmClose(f.stamp);advanceUntilIdle();assertEquals(8L,f.model.uiState.value.detail!!.id)
    }
    @Test fun `queued confirmed deletion rechecks the row after a preceding write releases the lock`()=scenario { f ->
        val original=p();f.repo.rows.value=listOf(original);f.model.open(7,f.token);advanceUntilIdle();f.model.requestDelete(f.stamp);val confirmed=f.stamp
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        f.repo.beforeSave={ entered.complete(Unit);release.await() }
        val writer=launch { f.cases.save(original.copy(title="锁内新内容"),emptyList(),f.token) {} };runCurrent();assertTrue(entered.isCompleted)
        f.model.confirmDelete(confirmed);runCurrent();assertTrue(f.model.uiState.value.isBusy)
        release.complete(Unit);writer.join();advanceUntilIdle()
        assertEquals(listOf("锁内新内容"),f.repo.rows.value.map { it.title });assertNotNull(f.model.uiState.value.error)
    }
    @Test fun `multiple committed reminder failures survive navigation and retry by explicit identity`()=scenario { f ->
        f.effects={_,_->error("alarm failure")}
        f.model.openNew(f.token);advanceUntilIdle();f.change(PlanField.TITLE,"第一项");f.model.save(f.stamp);advanceUntilIdle()
        f.model.openNew(f.token);advanceUntilIdle();f.change(PlanField.TITLE,"第二项");f.model.save(f.stamp);advanceUntilIdle()
        assertEquals(setOf(41L,42L),f.model.uiState.value.reminderFailures.keys)
        f.effects={_,_->};f.model.retryReminder(f.stamp,41);advanceUntilIdle()
        assertEquals(setOf(42L),f.model.uiState.value.reminderFailures.keys)
        f.model.requestClose(f.stamp);advanceUntilIdle();f.model.retryReminder(f.stamp,42);advanceUntilIdle()
        assertTrue(f.model.uiState.value.reminderFailures.isEmpty());assertEquals(2,f.repo.writes)
        assertEquals(listOf("第一项","第二项"),f.repo.rows.value.map { it.title })
    }
    private fun scenario(block:suspend TestScope.(EditorTestWorld)->Unit)=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);Dispatchers.setMain(dispatcher);val f=EditorTestWorld(dispatcher)
        try { advanceUntilIdle();block(f) } finally { f.model.viewModelScope.cancel();advanceUntilIdle();Dispatchers.resetMain() }
    }
}
