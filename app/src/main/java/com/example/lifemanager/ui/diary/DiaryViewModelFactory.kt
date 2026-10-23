package com.example.lifemanager.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.diary.DiaryRepository
import com.example.lifemanager.domain.diary.DiaryMediaAccess
import com.example.lifemanager.ui.common.GenerationAccess
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher

/** The ordinary diary owner belongs to the Activity ViewModelStore. */
class DiaryViewModelFactory @Inject constructor(
    private val repository:DiaryRepository,private val access:GenerationAccess,
    @param:IoDispatcher private val dispatcher:CoroutineDispatcher,private val clock:Clock,
    private val media:DiaryMediaAccess,
):ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T:ViewModel> create(modelClass:Class<T>):T=when(modelClass) {
        DiaryViewModel::class.java->DiaryViewModel(repository,access,dispatcher,clock,media)
        else->throw IllegalArgumentException("Unsupported diary owner: ${modelClass.name}")
    } as T
}
