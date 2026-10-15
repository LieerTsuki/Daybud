package com.example.lifemanager.ui.settings

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.*
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class V1BackupPickerTest {
    @get:Rule val compose=createComposeRule()
    private val scheduler=TestCoroutineScheduler()
    private val world=V1BackupSettingsViewModelTest.World(StandardTestDispatcher(scheduler))
    private val registry=Registry()
    @After fun cleanup() { world.model.viewModelScope.cancel();scheduler.runCurrent();compose.waitForIdle() }
    @Test fun `recreation after source result does not launch same picker twice while worker waits`() {
        world.model.start(BackupAction.RESTORE,world.token);scheduler.runCurrent()
        val restoration=show()
        assertEquals(1,registry.launches)
        compose.runOnIdle { registry.respond("content://test/source") }
        assertEquals(BackupStep.PICK_SOURCE,world.model.uiState.value.step)
        restoration.emulateSavedInstanceStateRestore();compose.waitForIdle()
        assertEquals(1,registry.launches)
        scheduler.runCurrent()
        assertEquals(BackupStep.RESTORE_PREVIEW,world.model.uiState.value.step)
    }
    @Test fun `recreation after output result does not create another output document`() {
        world.model.start(BackupAction.EXPORT,world.token);scheduler.runCurrent()
        world.model.answerFirst(world.id(),true);scheduler.runCurrent()
        val restoration=show();assertEquals(1,registry.launches)
        compose.runOnIdle { registry.respond("content://test/export") }
        assertEquals(BackupStep.PICK_OUTPUT,world.model.uiState.value.step)
        restoration.emulateSavedInstanceStateRestore();compose.waitForIdle()
        assertEquals(1,registry.launches)
        scheduler.runCurrent();compose.waitForIdle()
        assertEquals(1,world.saved.size)
    }
    private fun show():StateRestorationTester {
        val restoration=StateRestorationTester(compose)
        restoration.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
            MaterialTheme { Column { V1BackupSettingsSection(world.model,true) {} } }
        } }
        compose.waitForIdle();return restoration
    }
    private class Registry:ActivityResultRegistry(),ActivityResultRegistryOwner {
        override val activityResultRegistry get()=this
        var launches=0;private var request=0
        override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
            request=requestCode;launches++
        }
        fun respond(uri:String) { dispatchResult(request,Activity.RESULT_OK,Intent().setData(Uri.parse(uri))) }
    }
}
