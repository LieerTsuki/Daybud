package com.example.lifemanager.data.backup

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.lifemanager.data.plan.PlanMigrationTestDatabase
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.settings.*
import java.io.*
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class V1BackupSettingsRoomTest {
    @get:Rule val temporary=TemporaryFolder()
    private val fixture=V2BackupRoomFixtures
    @Test fun `owner protects real complete target database and replaces once with local generation`()=scenario { f ->
        f.preview();assertEquals(0,f.model.uiState.value.rowCounts.getValue("plans"))
        f.protect();fixture.sameRows(fixture.full(),f.codec.decode(f.saved,ZoneId.of("UTC")).document.payload)
        val id=f.id();f.model.answerFinal(id,true);f.model.answerFinal(id,true);f.wait(BackupStep.FINISHED)
        assertEquals(V1BackupOutcome.COMMITTED,f.model.uiState.value.outcome)
        assertEquals(6,fixture.generation(f.db));fixture.sameRows(CurrentBackupFixtures.empty(),fixture.snapshot(f.db))
        f.model.answerFinal(id,true);assertEquals(6,fixture.generation(f.db))
    }
    @Test fun `legacy source strict conversion preview and owner restore preserve original values in real Room`()=scenario { f ->
        f.source=BackupJsonCodec().encode(BackupDocument("0.1.1","2026-10-14T00:00:00Z",BackupFixtures.fullPayload()))
        f.preview();assertEquals(BackupSourceFormat.V1,f.model.uiState.value.sourceFormat)
        assertEquals("Asia/Shanghai",f.model.uiState.value.legacyTodoTimeZone)
        assertEquals(2,f.model.uiState.value.rowCounts.getValue("plans"))
        f.protect();f.model.answerFinal(f.id(),true);f.wait(BackupStep.FINISHED)
        val actual=fixture.snapshot(f.db)
        assertEquals(BackupValue.Text("  旧待办\n\"🏠\"  "),actual.tables.getValue("plans").first().fields["title"])
        assertEquals(BackupValue.Text("Asia/Shanghai"),actual.tables.getValue("plans").first().fields["timeZone"])
        assertEquals(BackupValue.Integer(Long.MAX_VALUE),actual.tables.getValue("subscriptions").single().fields["amountMinor"])
        assertEquals(6,fixture.generation(f.db));assertTrue(f.staging.listFiles()!!.isEmpty())
    }
    @Test fun `owner cancel after verified protection keeps every original row generation and inventory`()=scenario { f ->
        fixture.inventory(f.db);f.preview();f.protect();f.model.answerFinal(f.id(),false);f.wait(BackupStep.FINISHED)
        fixture.sameRows(fixture.full(),fixture.snapshot(f.db));assertEquals(5,fixture.generation(f.db))
        assertEquals(1,fixture.technical(f.db).size)
        assertEquals(V1BackupOutcome.UNCHANGED,f.model.uiState.value.outcome)
        assertEquals(MaintenanceState.IDLE,f.coordinator.state.value)
    }
    private fun scenario(block:suspend (World)->Unit)=fixture.withDatabase { db ->
        fixture.seed(db);Dispatchers.setMain(Dispatchers.Unconfined)
        val f=World(db)
        try { block(f) } finally { f.model.viewModelScope.coroutineContext.job.cancelAndJoin();Dispatchers.resetMain() }
    }
    private inner class World(val db:PlanMigrationTestDatabase) {
        val generations=object:DataGenerationRepository {
            override fun observe()=flow { emit(current()) }
            override suspend fun current()=db.withTransaction { V2BackupSql(db.backup(),BackupLimits()).generation() }
            override suspend fun commit(expected:DataGeneration,mutation:suspend ()->Unit):DataGeneration=error("target mutation owns transaction")
        }
        val coordinator=MaintenanceCoordinator(generations)
        val codec=VersionedBackupJsonCodec()
        var source=codec.encode(V2BackupFixtures.document(CurrentBackupFixtures.empty()))
        var saved=byteArrayOf()
        val staging=temporary.newFolder()
        val store=AndroidBackupFileStore(object:BackupStreamAccess {
            override fun openWrite(location:BackupLocation):OutputStream {
                assertFalse(db.inTransaction())
                return object:ByteArrayOutputStream() { override fun close() { saved=toByteArray();super.close() } }
            }
            override fun openRead(location:BackupLocation):InputStream {
                assertFalse(db.inTransaction())
                return ByteArrayInputStream(if(location.value=="content://test/source")source else saved)
            }
        },staging,Dispatchers.Unconfined)
        val snapshots=V2BackupRepositoryImpl(db,db.backup())
        val files=V2BackupFileUseCases(snapshots,codec,store,coordinator,Dispatchers.Unconfined)
        val effects=object:MaintenanceReminderEffects {
            override suspend fun clearPrevious(identities:ReminderIdentities) {}
            override suspend fun reconcile() {}
            override fun requestReconciliation() {}
        }
        val workflow=V2BackupMaintenanceWorkflow(snapshots,V2BackupMutationRepositoryImpl(db,db.backup()),files,generations,coordinator,
            V2BackupReminderCaptureImpl(db,db.backup(),BackupPlanNotificationSource { emptyList() }),effects,Dispatchers.Unconfined)
        val model=V1BackupSettingsViewModel(files,workflow,snapshots,GenerationAccess(generations,coordinator),Dispatchers.Unconfined,{ ZoneId.of("Asia/Shanghai") })
        fun id()=checkNotNull(model.uiState.value.operationId)
        suspend fun wait(step:BackupStep) { withTimeout(10_000) { model.uiState.first { it.step==step } } }
        suspend fun preview() {
            model.start(BackupAction.RESTORE,DataGeneration(5));wait(BackupStep.PICK_SOURCE)
            model.sourceSelected(id(),"content://test/source");wait(BackupStep.RESTORE_PREVIEW)
        }
        suspend fun protect() {
            model.answerFirst(id(),true);wait(BackupStep.PICK_OUTPUT)
            model.outputSelected(id(),"content://test/protection");wait(BackupStep.FINAL_CONFIRM)
        }
    }
}
