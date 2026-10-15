package com.example.lifemanager.notification

import com.example.lifemanager.domain.maintenance.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Test-only admission fixture; business/settings fixtures use actual v6 Room. */
internal class ReminderAdmissionFixture {
    val generations = ReminderAdmissionGenerations()
    val coordinator = MaintenanceCoordinator(generations)
}

internal class ReminderAdmissionGenerations : DataGenerationRepository {
    private val values = MutableStateFlow(DataGeneration(37))
    override fun observe() = values
    override suspend fun current() = values.value
    override suspend fun commit(expected: DataGeneration, mutation: suspend () -> Unit): DataGeneration {
        if (values.value != expected) throw StaleGenerationException()
        mutation()
        return DataGeneration(expected.value + 1).also { values.value = it }
    }
}
