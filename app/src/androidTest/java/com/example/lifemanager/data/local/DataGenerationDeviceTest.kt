package com.example.lifemanager.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.data.plan.PlanEntity
import com.example.lifemanager.data.plan.RoomV1SettingsRepository
import kotlinx.coroutines.flow.first
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceBusyException
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(AndroidJUnit4::class)
class DataGenerationDeviceTest {
    @Test fun backgroundFirstInitializationAndGenerationPersistAcrossReopen(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "maintenance-device-${System.nanoTime()}.db"
        try {
            val first = LifeManagerDatabaseFactory.open(context, name)
            try {
                val generations = DataGenerationRepositoryImpl(first)
                assertEquals(DataGeneration(0), generations.current())
                assertEquals("CNY", RoomV1SettingsRepository(first).observe().first().defaultCurrency)
                generations.commit(DataGeneration(0)) { insertPlan(first, "synthetic original") }
            } finally { first.close() }
            val reopened = LifeManagerDatabaseFactory.open(context, name)
            try {
                assertEquals(DataGeneration(1), DataGenerationRepositoryImpl(reopened).current())
                assertEquals("synthetic original", reopened.planDao().allEntities().single().title)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun cancellationRollsBackAndCommittedReplacementRejectsOldSameIdDraft(): Unit = runBlocking {
        val fixture = V6DeviceFixture()
        val database = fixture.database
        try {
            val generations = DataGenerationRepositoryImpl(database)
            val coordinator = MaintenanceCoordinator(generations)
            val original = coordinator.capture()
            coordinator.run(original) { insertPlan(database, "synthetic original") }
            coordinator.withSession { session ->
                assertFailsWith<MaintenanceBusyException> { coordinator.run(original) { error("must not run") } }
                assertFailsWith<CancellationException> {
                    coordinator.withMaintenance(session) {
                        generations.commit(session.generation) {
                            database.planDao().delete(41)
                            throw CancellationException("cancel before commit")
                        }
                    }
                }
                assertEquals(original, generations.current())
                assertEquals("synthetic original", database.planDao().allEntities().single().title)
                coordinator.withMaintenance(session) {
                    generations.commit(session.generation) {
                        database.planDao().delete(41)
                        insertPlan(database, "synthetic restored same ID")
                    }
                }
            }
            assertFailsWith<StaleGenerationException> { coordinator.run(original) { database.planDao().delete(41) } }
            assertEquals("synthetic restored same ID", database.planDao().allEntities().single().title)
            assertEquals(DataGeneration(1), generations.current())
        } finally { fixture.close() }
    }

    private suspend fun insertPlan(database: LifeManagerDatabase, title: String) {
        database.planDao().insert(PlanEntity(id = 41, title = title, timeZone = "UTC", createdAt = 1, updatedAt = 1))
    }
}
