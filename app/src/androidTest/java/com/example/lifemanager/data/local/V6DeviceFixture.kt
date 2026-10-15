package com.example.lifemanager.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.Closeable
import java.util.UUID

/** Every collaborator uses this production-factory database; only this fixture's file is removed. */
internal class V6DeviceFixture : Closeable {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "e6-device-${UUID.randomUUID()}.db"
    val database = LifeManagerDatabaseFactory.open(context, name)
    override fun close() {
        try { database.close() } finally { context.deleteDatabase(name) }
    }
}
