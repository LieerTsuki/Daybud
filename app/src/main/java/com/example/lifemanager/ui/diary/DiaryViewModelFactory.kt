package com.example.lifemanager.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.lifemanager.data.diary.ai.AiModeStore
import com.example.lifemanager.data.diary.ai.local.LocalModelStore
import com.example.lifemanager.data.diary.ai.local.LocalDeviceCapability
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.example.lifemanager.data.diary.ai.DiaryAiEngine
import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.diary.DiaryRepository
import com.example.lifemanager.domain.diary.ai.DiaryAiDataSource
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.diary.ai.DiaryAiViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher

/** Both diary owners belong to the Activity's ViewModelStore, including during navigation. */
class DiaryViewModelFactory @Inject constructor(
    private val repository:DiaryRepository,private val access:GenerationAccess,
    @param:IoDispatcher private val dispatcher:CoroutineDispatcher,private val clock:Clock,
    private val media:AndroidDiaryMediaAccess,private val engine:DiaryAiEngine,
    private val source:DiaryAiDataSource,private val store:AiModeStore,private val models:LocalModelStore,
    @param:ApplicationContext private val context:Context,
):ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T:ViewModel> create(modelClass:Class<T>):T=when(modelClass) {
        DiaryViewModel::class.java->DiaryViewModel(repository,access,dispatcher,clock,media)
        DiaryAiViewModel::class.java->DiaryAiViewModel(engine,source,store,models) { LocalDeviceCapability.snapshot(context) }
        else->throw IllegalArgumentException("Unsupported diary owner: ${modelClass.name}")
    } as T
}
