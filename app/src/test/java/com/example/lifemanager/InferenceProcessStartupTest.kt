package com.example.lifemanager

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InferenceProcessStartupTest {
    @Test fun inferenceApplicationStartsWithoutWorkManagerDatabaseOrBusinessInjection() {
        val app = LifeManagerApp()
        val base = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = app
        }
        ReflectionHelpers.setField(app, "mBase", base)
        val before = base.databaseList().toSet()
        ShadowApplication.setProcessName("${base.packageName}:diary_inference")
        try {
            // No WorkManager delegate: the old business startup would throw while scheduling work.
            app.onCreate()
            assertEquals(before, base.databaseList().toSet())
            val business: Lazy<*> = ReflectionHelpers.getField(app, "business\$delegate")
            assertFalse("Business graph was initialized by inference startup", business.isInitialized())
            try { app.database; fail("private process acquired business database") }
            catch (_: IllegalStateException) { }
        } finally { ShadowApplication.setProcessName(base.packageName) }
    }
}
