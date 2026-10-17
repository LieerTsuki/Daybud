package com.example.lifemanager.data.repository

import android.app.Application
import com.example.lifemanager.data.local.V6RoomFixture
import com.example.lifemanager.domain.model.Habit
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import com.example.lifemanager.domain.model.TreeSpecies
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HabitForestRoomTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun midnightMakesFutureDropAvailableWithoutAnotherDatabaseWrite(): Unit = runBlocking {
        V6RoomFixture().use { f ->
            var time = Instant.parse("2026-10-17T15:59:00Z")
            val clock = object : Clock() {
                override fun getZone(): ZoneId = ZoneId.of("Asia/Shanghai")
                override fun withZone(zone: ZoneId): Clock = Clock.fixed(time, zone)
                override fun instant(): Instant = time
            }
            val repo = HabitRepositoryImpl(f.database, clock)
            val id = repo.saveHabit(Habit(name = "跨天", startDate = LocalDate.of(2026,10,17)))
            repo.toggleRecord(id, LocalDate.of(2026,10,18))
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(UnconfinedTestDispatcher(scheduler))
            val first = CompletableDeferred<Unit>()
            val changed = CompletableDeferred<Unit>()
            val reverted = CompletableDeferred<Unit>()
            scope.launch {
                repo.observeForest().collect {
                    if (it.availableDrops[id] == 0) first.complete(Unit)
                    if (it.availableDrops[id] == 1) changed.complete(Unit)
                    if (changed.isCompleted && it.availableDrops[id] == 0) reverted.complete(Unit)
                }
            }
            try {
                withTimeout(5000) { first.await() }
                time = Instant.parse("2026-10-17T16:00:01Z")
                scheduler.advanceTimeBy(60_001)
                scheduler.runCurrent()
                withTimeout(1000) { changed.await() }
                assertEquals(1, repo.observeForest().first().availableDrops[id])
                time = Instant.parse("2026-10-17T15:59:00Z")
                scheduler.advanceTimeBy(60_001)
                scheduler.runCurrent()
                withTimeout(1000) { reverted.await() }
                assertEquals(0, repo.observeForest().first().availableDrops[id])
            } finally { scope.cancel() }
        }
    }
    @Test fun twentyManualWateringsMatureTreeAndUndoStillTargetsOriginalTreeAfterReplant(): Unit = runBlocking {
        V6RoomFixture().use { f ->
            val repo = HabitRepositoryImpl(f.database)
            val today = LocalDate.now()
            val id = repo.saveHabit(Habit(name = "阅读", iconKey = "tree:MAPLE", startDate = today.minusDays(30)))
            repeat(21) { repo.toggleRecord(id, today.minusDays(it.toLong())) }
            val first = repo.observeForest().first().trees.single()
            assertEquals(21, repo.observeForest().first().availableDrops[id])
            assertEquals(0, first.wateringCount, "打卡只奖励水滴，不能自动成长")
            assertFailsWith<IllegalArgumentException> { repo.plantTree(id) }
            repeat(20) { repo.waterTree(first.id) }
            assertTrue(repo.observeForest().first().trees.single().isMature)
            assertEquals(1, repo.observeForest().first().availableDrops[id])
            assertFailsWith<IllegalArgumentException> { repo.waterTree(first.id) }
            repo.plantTree(id)
            val second = repo.observeForest().first().trees.last()
            assertEquals(0, second.wateringCount)
            repo.saveHabit(repo.observeHabits().first().single().copy(iconKey = "tree:CHERRY"))
            val editedTrees = repo.observeForest().first().trees
            assertEquals(TreeSpecies.MAPLE, editedTrees.first().species, "成树保留自己的树种")
            assertEquals(TreeSpecies.CHERRY, editedTrees.last().species, "新树使用当前选择的树种")
            repo.toggleRecord(id, today.minusDays(20)) // oldest record, already used on first tree
            val undone = repo.observeForest().first()
            assertEquals(19, undone.trees.first().wateringCount)
            assertEquals(second.id, undone.trees.last().id)
            assertEquals(1, undone.availableDrops[id])
            repo.waterTree(first.id)
            assertEquals(20, repo.observeForest().first().trees.first().wateringCount)
            assertEquals(0, repo.observeForest().first().availableDrops[id])
            assertFailsWith<IllegalArgumentException> { repo.waterTree(second.id) }
        }
    }

    @Test fun dropsCannotCrossTasksOrSpendFutureRecordsAndEditingDoesNotResetGrowth(): Unit = runBlocking {
        V6RoomFixture().use { f ->
            val repo = HabitRepositoryImpl(f.database)
            val today = LocalDate.now()
            val firstId = repo.saveHabit(Habit(name = "阅读", iconKey = "tree:OAK", startDate = today.minusDays(1)))
            val otherId = repo.saveHabit(Habit(name = "跑步", startDate = today.minusDays(1)))
            repo.toggleRecord(firstId, today)
            repo.toggleRecord(otherId, today.plusDays(1))
            val trees = repo.observeForest().first().trees
            val first = trees.single { it.habitId == firstId }; val other = trees.single { it.habitId == otherId }
            assertEquals(0, repo.observeForest().first().availableDrops[otherId])
            assertFailsWith<IllegalArgumentException> { repo.waterTree(other.id) }
            repo.waterTree(first.id)
            val habit = repo.observeHabits().first().single { it.id == firstId }
            repo.saveHabit(habit.copy(name = "精读", iconKey = "tree:CHERRY"))
            val edited = repo.observeForest().first().trees.single { it.id == first.id }
            assertEquals(1, edited.wateringCount); assertEquals(TreeSpecies.CHERRY, edited.species)
            assertEquals(1, repo.observeRecords(today, today).first().size)
            repo.deleteHabit(firstId)
            assertTrue(repo.observeForest().first().trees.none { it.habitId == firstId })
            assertEquals(0, f.database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM habit_waterings").use { it.moveToFirst(); it.getInt(0) })
            assertFailsWith<IllegalArgumentException> { repo.waterTree(first.id) }
        }
    }

    @Test fun simultaneousWateringCannotSpendOneRecordTwiceAndSqlFailureRollsBack(): Unit = runBlocking {
        V6RoomFixture().use { f ->
            val repo = HabitRepositoryImpl(f.database)
            val id = repo.saveHabit(Habit(name = "阅读", startDate = LocalDate.now()))
            repo.toggleRecord(id, LocalDate.now())
            val tree = repo.observeForest().first().trees.single()
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER forest_fail BEFORE INSERT ON habit_waterings BEGIN SELECT RAISE(ABORT,'water failure'); END")
            assertFails { repo.waterTree(tree.id) }
            assertEquals(1, repo.observeForest().first().availableDrops[id])
            f.database.openHelper.writableDatabase.execSQL("DROP TRIGGER forest_fail")
            val results = coroutineScope { listOf(async { runCatching { repo.waterTree(tree.id) } }, async { runCatching { repo.waterTree(tree.id) } }).map { it.await() } }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, repo.observeForest().first().trees.single().wateringCount)
            assertEquals(0, repo.observeForest().first().availableDrops[id])
        }
    }

    @Test fun creatingHabitPersistsItsFirstSeedInTheProductionDatabase(): Unit = runBlocking {
        V6RoomFixture().use { fixture ->
            val repository = HabitRepositoryImpl(fixture.database)
            val id = repository.saveHabit(Habit(name = "阅读", startDate = LocalDate.now()))
            val sqlite = fixture.database.openHelper.writableDatabase
            val tableCount = sqlite.query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='habit_trees'").use {
                it.moveToFirst(); it.getInt(0)
            }
            assertEquals(1, tableCount, "新建任务应持久化第一棵树，供林场浇水")
            sqlite.query("SELECT habitId,species FROM habit_trees").use {
                assertEquals(true, it.moveToFirst()); assertEquals(id, it.getLong(0))
            }
        }
    }
}
