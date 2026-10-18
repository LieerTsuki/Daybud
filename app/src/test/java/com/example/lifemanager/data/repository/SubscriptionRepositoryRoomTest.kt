package com.example.lifemanager.data.repository

import android.app.Application
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.local.V6RoomFixture
import com.example.lifemanager.data.local.entity.SubscriptionEntity
import com.example.lifemanager.domain.model.BillingCycle
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubscriptionRepositoryRoomTest {
    private lateinit var fixture: V6RoomFixture
    private lateinit var database: LifeManagerDatabase
    private lateinit var repository: SubscriptionRepositoryImpl

    @Before fun setUp() {
        fixture = V6RoomFixture()
        database = fixture.database
        repository = SubscriptionRepositoryImpl(database)
    }

    @After fun tearDown() = fixture.close()

    @Test fun nameEditPreservesExactHiddenHistoricalFieldsAndForeignCurrency(): Unit = runBlocking {
        val before = seed("  旧分类\n ", " \t旧备注\n保留尾空格  ")
        val loaded = assertNotNull(repository.getSubscription(before.id))

        repository.saveSubscription(loaded.copy(appName = "修改后的名称"), setOf(1, 3, 7))

        val after = assertNotNull(database.subscriptionDao().getById(before.id))
        assertEquals(before.copy(appName = "修改后的名称", updatedAt = after.updatedAt), after)
        assertEquals(setOf(1, 3, 7), repository.getReminderDays(before.id))
    }

    @Test fun nullAndEmptyHiddenFieldsRemainDistinctWhenSaving(): Unit = runBlocking {
        for ((category, note) in listOf(null to "", "" to null)) {
            val before = seed(category, note)
            val loaded = assertNotNull(repository.getSubscription(before.id))

            repository.saveSubscription(loaded.copy(appName = "改名"), emptySet())

            val after = assertNotNull(database.subscriptionDao().getById(before.id))
            assertEquals(before.copy(appName = "改名", updatedAt = after.updatedAt), after)
        }
    }

    @Test fun retiredFieldsComeFromTheLatestPersistedRowInsideTheWriteTransaction(): Unit = runBlocking {
        val before = seed("旧分类", "旧备注")
        val stale = assertNotNull(repository.getSubscription(before.id))
        // Simulate a newer imported/updated row after an editor captured its original snapshot.
        val current = before.copy(category = null, note = "  当前备注\n ")
        database.subscriptionDao().updateSubscription(current)

        repository.saveSubscription(stale.copy(appName = "改名", category = "被隐藏的旧草稿", note = "旧草稿备注"), emptySet())

        val after = assertNotNull(database.subscriptionDao().getById(before.id))
        assertEquals(current.copy(appName = "改名", updatedAt = after.updatedAt), after)
    }

    @Test fun cancellationAndRestorePreserveHiddenFieldsAndCurrency(): Unit = runBlocking {
        val before = seed(" 旧分类 ", " 旧备注\n ")
        val loaded = assertNotNull(repository.getSubscription(before.id))
        val cancellation = LocalDate.of(2026, 10, 18)

        repository.saveSubscription(loaded.copy(isActive = false, cancelDate = cancellation), setOf(3))
        val cancelled = assertNotNull(database.subscriptionDao().getById(before.id))
        assertEquals(before.copy(isActive = false, cancelDate = cancellation.toEpochDay(), updatedAt = cancelled.updatedAt), cancelled)

        repository.saveSubscription(assertNotNull(repository.getSubscription(before.id)).copy(isActive = true, cancelDate = null), setOf(3))
        val restored = assertNotNull(database.subscriptionDao().getById(before.id))
        assertEquals(before.copy(updatedAt = restored.updatedAt), restored)
        assertEquals(setOf(3), repository.getReminderDays(before.id))
    }

    private suspend fun seed(category: String?, note: String?): SubscriptionEntity {
        val value = SubscriptionEntity(appName = "旧外币订阅", amountMinor = 1250, currency = "JPY",
            billingCycle = BillingCycle.MONTHLY, nextBillingDate = LocalDate.of(2026, 10, 22).toEpochDay(),
            startDate = LocalDate.of(2026, 9, 22).toEpochDay(), category = category, note = note,
            isActive = true, cancelDate = null, createdAt = 1234, updatedAt = 5678)
        val id = database.subscriptionDao().insertSubscription(value)
        return value.copy(id = id)
    }
}
