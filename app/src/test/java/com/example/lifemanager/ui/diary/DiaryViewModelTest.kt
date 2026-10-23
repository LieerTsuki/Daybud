package com.example.lifemanager.ui.diary

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.diary.*
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.ui.common.GenerationAccess
import java.lang.reflect.Proxy
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModelTest {
    @Test fun `initial publication reads diary content only once`() = scenario { w ->
        assertFalse(w.model.uiState.value.loading)
        assertEquals(1,w.contentReads)
    }
    @Test fun `idle input persists after one second and explicit leave flushes latest text`() = scenario { w ->
        w.model.newEntry(w.model.uiState.value.stamp); runCurrent()
        w.model.changeBody("第一段", w.model.uiState.value.stamp)
        advanceTimeBy(999); runCurrent(); assertEquals("", w.persisted.body)
        advanceTimeBy(1); runCurrent(); assertEquals("第一段", w.persisted.body)
        w.model.changeBody("离开前最后一句", w.model.uiState.value.stamp)
        w.model.leave(w.model.uiState.value.stamp); runCurrent()
        assertEquals("离开前最后一句", w.persisted.body)
        assertNull(w.model.uiState.value.draft)
    }
    @Test fun `continuous input persists within five seconds without losing newer edits`() = scenario { w ->
        w.model.newEntry(w.model.uiState.value.stamp); runCurrent()
        repeat(11) { n ->
            w.model.changeBody("文字$n", w.model.uiState.value.stamp)
            advanceTimeBy(450); runCurrent()
        }
        assertEquals("", w.persisted.body)
        advanceTimeBy(50); runCurrent(); assertEquals("文字10", w.persisted.body)
    }
    @Test fun `stale picker cannot attach to a reopened draft`() = scenario { w ->
        w.model.newEntry(w.model.uiState.value.stamp); runCurrent()
        val picker = w.model.beginPicker(w.model.uiState.value.stamp)!!
        w.model.leave(w.model.uiState.value.stamp); runCurrent()
        w.model.resumeDraft("draft", w.model.uiState.value.stamp); runCurrent()
        w.model.importSelected(listOf("content://old"), picker); runCurrent()
        assertEquals(0, w.mediaReads)
        assertTrue(w.model.uiState.value.draft!!.media.isEmpty())
    }
    @Test fun `data replacement invalidates editor and late selection`() = scenario { w ->
        w.model.newEntry(w.model.uiState.value.stamp); runCurrent()
        val picker = w.model.beginPicker(w.model.uiState.value.stamp)!!
        w.generation.value = DataGeneration(5); runCurrent()
        w.model.importSelected(listOf("content://old"), picker); runCurrent()
        assertEquals(0, w.mediaReads); assertNull(w.model.uiState.value.draft)
    }
    @Test fun `failed autosave keeps input and retry persists same draft`() = scenario { w ->
        w.model.newEntry(w.model.uiState.value.stamp); runCurrent()
        w.failSave = true
        w.model.changeBody("保留这段", w.model.uiState.value.stamp)
        advanceTimeBy(1000); runCurrent()
        assertEquals("保留这段", w.model.uiState.value.draft!!.body)
        assertEquals(DiarySaveStatus.FAILED, w.model.uiState.value.saveStatus)
        w.failSave = false; w.model.retrySave(w.model.uiState.value.stamp); runCurrent()
        assertEquals("保留这段", w.persisted.body)
        assertEquals(DiarySaveStatus.SAVED, w.model.uiState.value.saveStatus)
    }
    @Test fun `recreated owner reopens durable draft with its saved text`() = scenario { w ->
        w.model.newEntry(w.model.uiState.value.stamp); runCurrent()
        w.model.changeBody("重开后继续写",w.model.uiState.value.stamp)
        advanceTimeBy(1000);runCurrent()
        val restored=DiaryViewModel(w.repository,GenerationAccess(w.generations,w.coordinator),StandardTestDispatcher(testScheduler),Clock.fixed(w.now,ZoneId.of("Asia/Shanghai")),w.media)
        try {
            runCurrent();restored.resumeDraft("draft",restored.uiState.value.stamp);runCurrent()
            assertEquals("重开后继续写",restored.uiState.value.draft!!.body)
        } finally { restored.viewModelScope.cancel() }
    }
    @Test fun `import already in progress releases its pin after data replacement`() = scenario { w ->
        val result=CompletableDeferred<DiaryImportedAttachment>()
        var released=0
        w.importMedia={ _,_->result.await() }
        w.model.newEntry(w.model.uiState.value.stamp);runCurrent()
        val ticket=w.model.beginPicker(w.model.uiState.value.stamp)!!
        w.model.importSelected(listOf("content://selected"),ticket);runCurrent()
        assertEquals(1,w.mediaReads)
        w.generation.value=DataGeneration(5);runCurrent()
        result.complete(DiaryImportedAttachment(DiaryMedia("11111111-1111-1111-1111-111111111111","image/jpeg",1,"a".repeat(64)),java.io.Closeable { released++ }))
        runCurrent()
        assertEquals(1,released)
        assertNull(w.model.uiState.value.draft)
        assertTrue(w.persisted.media.isEmpty())
    }
    @Test fun `completed import reaches disk while the next slow attachment is still copying`() = scenario { w ->
        val slow=CompletableDeferred<DiaryImportedAttachment>()
        var released=0
        w.importMedia={ source,_->
            if(source=="first")DiaryImportedAttachment(DiaryMedia("11111111-1111-1111-1111-111111111111","image/jpeg",1,"a".repeat(64)),java.io.Closeable { released++ })
            else slow.await()
        }
        w.model.newEntry(w.model.uiState.value.stamp);runCurrent()
        val ticket=w.model.beginPicker(w.model.uiState.value.stamp)!!
        w.model.importSelected(listOf("first","slow"),ticket);runCurrent()
        assertTrue(w.persisted.media.isEmpty())
        advanceTimeBy(1000);runCurrent()
        assertEquals(listOf("11111111-1111-1111-1111-111111111111"),w.persisted.media.map { it.id })
        assertEquals(1,released)
        assertTrue(w.model.uiState.value.busy)
    }
    @Test fun `cancellation at importer dispatcher return releases completed lease`() = scenario { w ->
        var released=0
        w.importMedia={ _,_->
            w.model.viewModelScope.cancel()
            DiaryImportedAttachment(DiaryMedia("11111111-1111-1111-1111-111111111111","image/jpeg",1,"a".repeat(64)),java.io.Closeable { released++ })
        }
        w.model.newEntry(w.model.uiState.value.stamp);runCurrent()
        val ticket=w.model.beginPicker(w.model.uiState.value.stamp)!!
        w.model.importSelected(listOf("content://selected"),ticket);runCurrent()
        assertEquals(1,released)
        assertTrue(w.persisted.media.isEmpty())
    }
    private fun scenario(block:suspend TestScope.(World)->Unit)=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val w=World(StandardTestDispatcher(testScheduler))
        try { runCurrent(); block(w) } finally { w.model.viewModelScope.cancel();runCurrent(); Dispatchers.resetMain() }
    }
    private class World(dispatcher:CoroutineDispatcher) {
        val generation=MutableStateFlow(DataGeneration(4))
        val generations=object:DataGenerationRepository {
            override fun observe()=generation
            override suspend fun current()=generation.value
            override suspend fun commit(expected:DataGeneration,mutation:suspend ()->Unit):DataGeneration { mutation();generation.value=DataGeneration(expected.value+1);return generation.value }
        }
        val now=Instant.parse("2026-10-19T04:00:00Z")
        var persisted=DiaryDraft("draft",null,null,LocalDate.parse("2026-10-19"),"","",null,emptyList(),now,now,0)
        var failSave=false
        var mediaReads=0
        var contentReads=0
        var importMedia:suspend (String,Long)->DiaryImportedAttachment={ _,_->error("stale selection must never import") }
        val drafts=MutableStateFlow<List<DiaryDraft>>(emptyList())
        // This boundary substitutes only disk persistence; the ViewModel, timers and generation admission are real.
        val repository=Proxy.newProxyInstance(DiaryRepository::class.java.classLoader,arrayOf(DiaryRepository::class.java)) { _, method,args ->
            when(method.name.substringBefore('-')) {
                "observeEntries" -> flow { contentReads++;emit(emptyList<DiaryEntry>()) }
                "observeDrafts" -> drafts
                "observeChanges" -> drafts.map { Unit }
                "readContent" -> { contentReads++;DiaryContentSnapshot(emptyList(),drafts.value) }
                "createDraft" -> persisted.also { drafts.value=listOf(it) }
                "readDraft" -> persisted
                "saveDraft" -> {
                    check(!failSave) { "disk full" }
                    check(args[1] == persisted.revision) { "stale revision" }
                    (args[0] as DiaryDraft).copy(revision=persisted.revision+1).also { persisted=it;drafts.value=listOf(it) }
                }
                else -> error("Unexpected persistence operation ${method.name}")
            }
        } as DiaryRepository
        val media=object:DiaryMediaAccess {
            override suspend fun importSource(source:String,remainingBytes:Long):DiaryImportedAttachment { mediaReads++;return importMedia(source,remainingBytes) }
            override fun file(media:DiaryMedia)=java.io.File("missing")
        }
        val coordinator=MaintenanceCoordinator(generations)
        val model=DiaryViewModel(repository,GenerationAccess(generations,coordinator),dispatcher,Clock.fixed(now,ZoneId.of("Asia/Shanghai")),media)
    }
}

