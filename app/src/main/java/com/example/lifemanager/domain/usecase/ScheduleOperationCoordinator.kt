package com.example.lifemanager.domain.usecase

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes cleanup of pre-v6 Schedule system identities; no legacy business writes remain. */
object ScheduleOperationCoordinator {
    private val mutex = Mutex()
    suspend fun <T> run(action: suspend () -> T): T = mutex.withLock { action() }
}
