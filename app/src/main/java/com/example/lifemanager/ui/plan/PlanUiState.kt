package com.example.lifemanager.ui.plan

import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.model.Tag
import com.example.lifemanager.domain.plan.PlanFilter
import com.example.lifemanager.domain.plan.PlanListRow
import com.example.lifemanager.domain.plan.PlanWriteReceipt
import java.time.ZoneId

data class PlanUiState(
    val generation: DataGeneration? = null,
    val rows: List<PlanListRow> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val filter: PlanFilter = PlanFilter(),
    val isLoading: Boolean = true,
    val isAvailable: Boolean = false,
    val isMaintaining: Boolean = false,
    val pendingIds: Set<Long> = emptySet(),
    val reminderFailures: Map<Long, PlanWriteReceipt> = emptyMap(),
    val readError: String? = null,
    val message: String? = null,
    val displayZone: ZoneId = ZoneId.systemDefault(),
)
