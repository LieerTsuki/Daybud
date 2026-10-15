package com.example.lifemanager.data.local

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import org.robolectric.RuntimeEnvironment

/** Host fixtures exercise the same factory, initialization and schema as production. */
internal class V6RoomFixture : Closeable {
    private val context = RuntimeEnvironment.getApplication()
    // Robolectric embeds long method names in its temp path; leave room for SQLite journal sidecars.
    // Test sandboxes have separate directories; the counter also separates fixtures within a sandbox.
    private val name = "e6-${sequence.incrementAndGet()}.db"
    val database = LifeManagerDatabaseFactory.open(context, name)
    override fun close() {
        try { database.close() } finally { context.deleteDatabase(name) }
    }

    private companion object {
        val sequence = AtomicLong()
    }
}
