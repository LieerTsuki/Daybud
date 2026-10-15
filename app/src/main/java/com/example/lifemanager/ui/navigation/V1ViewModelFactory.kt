package com.example.lifemanager.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.backup.V2BackupFileUseCases
import com.example.lifemanager.domain.backup.V2BackupMaintenanceWorkflow
import com.example.lifemanager.domain.backup.V2BackupRepository
import com.example.lifemanager.domain.maintenance.MaintenanceReminderEffects
import com.example.lifemanager.domain.plan.PlanReminderSources
import com.example.lifemanager.domain.plan.PlanRepository
import com.example.lifemanager.domain.plan.PlanUseCases
import com.example.lifemanager.domain.repository.V1SettingsRepository
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.plan.*
import com.example.lifemanager.ui.settings.V1BackupSettingsViewModel
import com.example.lifemanager.ui.settings.V1SettingsViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher

/** Activity ViewModelStore retains unified owners; ports come from the live production graph. */
class V1ViewModelFactory @Inject constructor(
    private val plans: PlanRepository,
    private val cases: PlanUseCases,
    private val sources: PlanReminderSources,
    private val access: GenerationAccess,
    private val settings: V1SettingsRepository,
    private val files: V2BackupFileUseCases,
    private val workflow: V2BackupMaintenanceWorkflow,
    private val snapshots: V2BackupRepository,
    private val reminders: MaintenanceReminderEffects,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        PlanNavigationViewModel::class.java -> PlanNavigationViewModel(access, dispatcher)
        PlanViewModel::class.java -> PlanViewModel(plans, cases, access, dispatcher)
        PlanEditorViewModel::class.java -> PlanEditorViewModel(plans, cases, access, sources, dispatcher)
        PlanCalendarViewModel::class.java -> PlanCalendarViewModel(plans, access, dispatcher)
        PlanStatisticsViewModel::class.java -> PlanStatisticsViewModel(plans, access, dispatcher)
        V1SettingsViewModel::class.java -> V1SettingsViewModel(settings, access, reminders, dispatcher)
        V1BackupSettingsViewModel::class.java -> V1BackupSettingsViewModel(files, workflow, snapshots, access, dispatcher)
        else -> throw IllegalArgumentException("Unsupported V1 owner: ${modelClass.name}")
    } as T
}
