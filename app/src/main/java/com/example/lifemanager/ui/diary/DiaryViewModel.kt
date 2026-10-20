package com.example.lifemanager.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.diary.*
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.ui.common.GenerationAccess
import java.io.Closeable
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** UI callbacks retain the data generation and editor session that produced them. */
data class DiaryStamp(val generation:DataGeneration?=null,val session:Long=0,val revision:Long=0)
data class DiaryPickerTicket(val stamp:DiaryStamp,val draftId:String)
enum class DiarySaveStatus { SAVED, SAVING, UNSAVED, FAILED }
data class DiaryUiState(
    val stamp:DiaryStamp=DiaryStamp(),val entries:List<DiaryEntry> = emptyList(),val drafts:List<DiaryDraft> = emptyList(),
    val draft:DiaryDraft?=null,val detail:DiaryEntry?=null,val saveStatus:DiarySaveStatus=DiarySaveStatus.SAVED,
    val busy:Boolean=false,val maintaining:Boolean=false,val loading:Boolean=true,val readFailed:Boolean=false,val error:String?=null,
    val calendar:Boolean=false,val month:YearMonth=YearMonth.now(),val selectedDate:LocalDate?=null,
    val today:LocalDate=LocalDate.now(),val memoryId:Long?=null,val confirmDelete:Boolean=false,val confirmDiscard:Boolean=false,
    val importProgress:String?=null,
) {
    val statistics get()=DiaryRules.statistics(entries,today)
    val memories get()=DiaryRules.onThisDay(entries,today)
    val memory get()=memories.find { it.id==memoryId }
    val visibleEntries get()=if(calendar && selectedDate!=null)entries.filter { it.date==selectedDate }else entries
}

class DiaryViewModel(
    private val repository:DiaryRepository,private val access:GenerationAccess,private val dispatcher:CoroutineDispatcher,
    private val clock:Clock=Clock.systemDefaultZone(),private val mediaAccess:DiaryMediaAccess?=null,
):ViewModel() {
    private val state=MutableStateFlow(DiaryUiState(today=LocalDate.now(clock),month=YearMonth.now(clock)))
    val uiState=state.asStateFlow()
    private val writes=Mutex()
    private var sequence=0L
    private var dirty=false
    private var debounce:Job?=null
    private var maximum:Job?=null
    private var pendingPicker:DiaryPickerTicket?=null
    private val pins=mutableMapOf<String,Closeable>()
    private var observer:Job?=null
    init { retryRead() }
    fun retryRead() {
        observer?.cancel()
        observer=viewModelScope.launch {
            try {
                combine(access.generations,access.maintenance,repository.observeEntries(),repository.observeDrafts()) { generation,phase,_,_ -> generation to phase }
                    .collectLatest { (generation,phase) ->
                        if(state.value.stamp.generation!=generation) invalidate(generation)
                        state.update { it.copy(maintaining=phase!=MaintenanceState.IDLE) }
                        if(phase!=MaintenanceState.IDLE)return@collectLatest
                        access.read({ repository.observeEntries().first() to repository.observeDrafts().first() }) { token,data ->
                            if(state.value.stamp.generation!=token)invalidate(token)
                            val today=LocalDate.now(clock)
                            val entries=DiaryRules.ordered(data.first)
                            val memories=DiaryRules.onThisDay(entries,today)
                            state.update { current ->
                                val detail=current.detail?.let { old->entries.find { it.id==old.id } }
                                val changed=current.detail!=detail
                                current.copy(entries=entries,drafts=data.second,loading=false,readFailed=false,today=today,detail=detail,
                                    error=if(current.readFailed)null else current.error,
                                    stamp=if(changed)current.stamp.copy(revision=current.stamp.revision+1)else current.stamp,
                                    confirmDelete=if(changed)false else current.confirmDelete,
                                    memoryId=current.memoryId?.takeIf { id->memories.any { it.id==id } } ?: memories.randomOrNull()?.id)
                            }
                        }
                    }
            } catch(error:Exception) { if(error is CancellationException)throw error;state.update { it.copy(loading=false,readFailed=true,error="读取日记失败，请重试") } }
        }
    }
    private fun invalidate(generation:DataGeneration) {
        cancelTimers();dirty=false;releasePins()
        state.value=DiaryUiState(stamp=DiaryStamp(generation,++sequence),today=LocalDate.now(clock),month=state.value.month,
            calendar=state.value.calendar,selectedDate=state.value.selectedDate,error=if(state.value.draft!=null)"数据已更换，请从草稿重新打开"else null)
    }
    private fun matches(stamp:DiaryStamp,exact:Boolean=false)=stamp.generation!=null && state.value.stamp.let {
        it.generation==stamp.generation && it.session==stamp.session && (!exact || it.revision==stamp.revision)
    }
    private fun allowed(stamp:DiaryStamp,exact:Boolean=false):Boolean {
        if(!matches(stamp,exact) || state.value.busy)return false
        return try { access.eventToken(stamp.generation);true } catch(_:Exception) { false }
    }
    fun newEntry(stamp:DiaryStamp)=openDraft(stamp) { repository.createDraft(checkNotNull(stamp.generation),LocalDate.now(clock)) }
    fun editEntry(stamp:DiaryStamp) {
        if(!allowed(stamp,true))return
        val entry=state.value.detail ?: return
        openDraft(stamp) { repository.createDraft(checkNotNull(stamp.generation),entry.date,entry.id) }
    }
    fun resumeDraft(id:String,stamp:DiaryStamp)=openDraft(stamp) { repository.readDraft(id) ?: error("草稿已不存在") }
    private fun openDraft(stamp:DiaryStamp,load:suspend ()->DiaryDraft) {
        if(!allowed(stamp) || state.value.draft!=null)return
        state.update { it.copy(busy=true,error=null) }
        viewModelScope.launch {
            try {
                val draft=withContext(dispatcher) { load() }
                access.publishResult(checkNotNull(stamp.generation)) {
                    if(matches(stamp)) { dirty=false;state.update { it.copy(draft=draft,detail=null,busy=false,stamp=DiaryStamp(stamp.generation,++sequence),saveStatus=DiarySaveStatus.SAVED) } }
                }
            } catch(error:Exception) { if(error is CancellationException)throw error;failure(stamp,"无法打开草稿") }
            finally { if(matches(stamp))state.update { it.copy(busy=false) } }
        }
    }
    fun openEntry(id:Long,stamp:DiaryStamp) {
        if(!allowed(stamp) || state.value.draft!=null)return
        val entry=state.value.entries.find { it.id==id } ?: return
        state.update { it.copy(detail=entry,error=null,stamp=DiaryStamp(stamp.generation,++sequence)) }
    }
    private fun change(stamp:DiaryStamp,transform:(DiaryDraft)->DiaryDraft) {
        if(!allowed(stamp))return
        val draft=state.value.draft ?: return
        state.update { it.copy(draft=transform(draft),stamp=it.stamp.copy(revision=it.stamp.revision+1),saveStatus=DiarySaveStatus.UNSAVED,error=null) }
        dirty=true;schedule()
    }
    fun changeTitle(value:String,stamp:DiaryStamp)=change(stamp) { it.copy(title=value) }
    fun changeBody(value:String,stamp:DiaryStamp)=change(stamp) { it.copy(body=value) }
    fun changeMood(value:DiaryMood?,stamp:DiaryStamp)=change(stamp) { it.copy(mood=value) }
    fun changeDate(value:LocalDate,stamp:DiaryStamp) {
        if(value.isAfter(LocalDate.now(clock)))return
        change(stamp) { it.copy(date=value) }
    }
    fun removeMedia(id:String,stamp:DiaryStamp)=change(stamp) { it.copy(media=it.media.filterNot { media->media.id==id }.mapIndexed { index,media->media.copy(position=index) }) }
    private fun schedule() {
        debounce?.cancel()
        debounce=viewModelScope.launch { delay(1_000);debounce=null;persist(state.value.stamp) }
        if(maximum==null)maximum=viewModelScope.launch { delay(5_000);maximum=null;persist(state.value.stamp) }
    }
    private fun cancelTimers() { debounce?.cancel();maximum?.cancel();debounce=null;maximum=null }
    /** Serialized CAS saves merge only the acknowledged revision into still-newer local input. */
    private suspend fun persist(stamp:DiaryStamp):Boolean=writes.withLock {
        if(!matches(stamp))return@withLock false
        val draft=state.value.draft ?: return@withLock true
        if(!dirty)return@withLock true
        cancelTimers()
        val editRevision=state.value.stamp.revision
        state.update { it.copy(saveStatus=DiarySaveStatus.SAVING) }
        try {
            access.eventToken(stamp.generation)
            val saved=withContext(dispatcher) { repository.saveDraft(draft,draft.revision,checkNotNull(stamp.generation)) }
            var published=false
            access.publishResult(checkNotNull(stamp.generation)) {
                if(matches(stamp) && state.value.draft?.id==draft.id) {
                    dirty=state.value.stamp.revision!=editRevision
                    state.update { it.copy(draft=it.draft?.copy(revision=saved.revision,updatedAt=saved.updatedAt),saveStatus=if(dirty)DiarySaveStatus.UNSAVED else DiarySaveStatus.SAVED,error=null) }
                    saved.media.forEach { pins.remove(it.id)?.close() };published=true
                    if(dirty)schedule()
                }
            }
            published
        } catch(error:Exception) {
            if(error is CancellationException)throw error
            if(matches(stamp))state.update { it.copy(saveStatus=DiarySaveStatus.FAILED,error=if(error is DiaryRevisionException)error.message else "草稿保存失败，输入已保留，请重试") }
            false
        }
    }
    fun retrySave(stamp:DiaryStamp) { if(matches(stamp))viewModelScope.launch { persist(stamp) } }
    fun flush() { val stamp=state.value.stamp;if(state.value.draft!=null)viewModelScope.launch { persist(stamp) } }
    fun leave(stamp:DiaryStamp) {
        if(!matches(stamp) || state.value.busy)return
        if(state.value.draft==null) { state.update { it.copy(detail=null,stamp=DiaryStamp(stamp.generation,++sequence)) };return }
        state.update { it.copy(busy=true) }
        viewModelScope.launch {
            if(persist(stamp) && matches(stamp)) { dirty=false;cancelTimers();releasePins();state.update { it.copy(draft=null,detail=null,busy=false,stamp=DiaryStamp(stamp.generation,++sequence)) } }
            else if(matches(stamp))state.update { it.copy(busy=false) }
        }
    }
    fun complete(stamp:DiaryStamp) {
        if(!allowed(stamp,true))return
        val draft=state.value.draft ?: return
        try { DiaryRules.validateContent(draft.date,draft.body,draft.media,LocalDate.now(clock)) }
        catch(error:Exception) { state.update { it.copy(error=error.message) };return }
        state.update { it.copy(busy=true) }
        viewModelScope.launch {
            try {
                if(!persist(stamp) || !matches(stamp))return@launch
                val latest=state.value.draft ?: return@launch
                val entry=writes.withLock { withContext(dispatcher) { repository.commitDraft(latest.id,latest.revision,checkNotNull(stamp.generation)) } }
                access.publishResult(checkNotNull(stamp.generation)) {
                    if(matches(stamp)) { cancelTimers();dirty=false;releasePins();state.update { it.copy(draft=null,detail=entry,busy=false,stamp=DiaryStamp(stamp.generation,++sequence),error=null) } }
                }
            } catch(error:Exception) { if(error is CancellationException)throw error;failure(stamp,error.message ?: "日记保存失败，草稿已保留") }
            finally { if(matches(stamp))state.update { it.copy(busy=false) } }
        }
    }
    fun askDelete(stamp:DiaryStamp) { if(allowed(stamp,true))state.update { it.copy(confirmDelete=true) } }
    fun askDiscard(stamp:DiaryStamp) { if(allowed(stamp,true))state.update { it.copy(confirmDiscard=true) } }
    fun cancelConfirmation(stamp:DiaryStamp) { if(matches(stamp,true))state.update { it.copy(confirmDelete=false,confirmDiscard=false) } }
    fun confirmDelete(stamp:DiaryStamp) {
        if(!allowed(stamp,true) || !state.value.confirmDelete)return
        val entry=state.value.detail ?: return
        operation(stamp) { repository.deleteEntry(entry.id,entry.revision,checkNotNull(stamp.generation)) }
    }
    fun confirmDiscard(stamp:DiaryStamp) {
        if(!allowed(stamp,true) || !state.value.confirmDiscard)return
        val draft=state.value.draft ?: return
        cancelTimers()
        operation(stamp) { repository.discardDraft(draft.id,draft.revision,checkNotNull(stamp.generation)) }
    }
    private fun operation(stamp:DiaryStamp,action:suspend ()->Unit) {
        state.update { it.copy(busy=true) }
        viewModelScope.launch {
            try {
                writes.withLock { withContext(dispatcher) { action() } }
                access.publishResult(checkNotNull(stamp.generation)) { if(matches(stamp)) { dirty=false;releasePins();state.update { it.copy(draft=null,detail=null,busy=false,confirmDelete=false,confirmDiscard=false,stamp=DiaryStamp(stamp.generation,++sequence),error=null) } } }
            } catch(error:Exception) { if(error is CancellationException)throw error;failure(stamp,error.message ?: "操作失败，请重试") }
            finally { if(matches(stamp))state.update { it.copy(busy=false) } }
        }
    }
    private fun failure(stamp:DiaryStamp,message:String) { if(matches(stamp))state.update { it.copy(busy=false,error=message) } }
    fun beginPicker(stamp:DiaryStamp):DiaryPickerTicket? {
        if(!allowed(stamp) || state.value.draft==null || pendingPicker!=null)return null
        if(state.value.draft!!.media.size>=DiaryRules.MAX_ATTACHMENTS) { state.update { it.copy(error="每篇最多20个附件") };return null }
        return DiaryPickerTicket(stamp,state.value.draft!!.id).also { pendingPicker=it }
    }
    fun pickerResult(sources:List<String>) { val ticket=pendingPicker ?: return;pendingPicker=null;importSelected(sources,ticket) }
    fun importSelected(sources:List<String>,ticket:DiaryPickerTicket) {
        if(!allowed(ticket.stamp) || state.value.draft?.id!=ticket.draftId || sources.isEmpty())return
        val importer=mediaAccess ?: return
        state.update { it.copy(busy=true,error=null) }
        viewModelScope.launch {
            var failures=0
            var reason:String?=null
            try {
                for((index,source) in sources.withIndex()) {
                    if(!matches(ticket.stamp) || state.value.draft?.id!=ticket.draftId)break
                    val draft=state.value.draft ?: break
                    if(draft.media.size>=DiaryRules.MAX_ATTACHMENTS) { failures+=sources.size-index;reason="每篇最多20个附件";break }
                    state.update { it.copy(importProgress="正在导入 ${index+1}/${sources.size}") }
                    try {
                        access.eventToken(ticket.stamp.generation)
                        var allocated:DiaryImportedAttachment?=null
                        var attached=false
                        try {
                            val imported=withContext(dispatcher) {
                                importer.importSource(source,DiaryRules.MAX_ENTRY_BYTES-draft.media.sumOf { it.bytes }).also { allocated=it }
                            }
                            access.publishResult(checkNotNull(ticket.stamp.generation)) {
                                if(matches(ticket.stamp) && state.value.draft?.id==ticket.draftId) {
                                    pins[imported.media.id]=imported.pin
                                    state.update { it.copy(draft=it.draft!!.let { current->current.copy(media=current.media+imported.media.copy(position=current.media.size)) },stamp=it.stamp.copy(revision=it.stamp.revision+1),saveStatus=DiarySaveStatus.UNSAVED) }
                                    dirty=true;attached=true;schedule()
                                }
                            }
                        } finally { if(!attached)allocated?.pin?.close() }
                    } catch(error:Exception) { if(error is CancellationException)throw error;failures++;reason=error.message?.take(80) ?: "附件无法读取" }
                }
                if(matches(ticket.stamp)) {
                    if(dirty)persist(ticket.stamp)
                    if(failures>0)state.update { it.copy(error="$failures 个附件未导入：${reason.orEmpty()}。已导入内容已保留") }
                }
            } finally { if(matches(ticket.stamp))state.update { it.copy(busy=false,importProgress=null) } }
        }
    }
    fun mediaFile(media:DiaryMedia)=mediaAccess?.file(media)
    fun refreshToday() {
        val today=LocalDate.now(clock)
        if(today!=state.value.today)state.update { current ->
            current.copy(today=today,memoryId=DiaryRules.onThisDay(current.entries,today).randomOrNull()?.id)
        }
    }
    fun toggleCalendar(value:Boolean,stamp:DiaryStamp) { if(allowed(stamp))state.update { it.copy(calendar=value) } }
    fun changeMonth(delta:Long,stamp:DiaryStamp) { if(allowed(stamp))state.update { it.copy(month=it.month.plusMonths(delta)) } }
    fun selectDate(date:LocalDate,stamp:DiaryStamp) { if(allowed(stamp))state.update { it.copy(selectedDate=date) } }
    fun anotherMemory(stamp:DiaryStamp) { if(allowed(stamp))state.update { current->current.copy(memoryId=current.memories.filterNot { it.id==current.memoryId }.randomOrNull()?.id ?: current.memoryId) } }
    fun dismissError(stamp:DiaryStamp) { if(matches(stamp))state.update { it.copy(error=null) } }
    private fun releasePins() { pins.values.forEach { it.close() };pins.clear() }
    override fun onCleared() { cancelTimers();releasePins();super.onCleared() }
}



