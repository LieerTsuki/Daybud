package com.example.lifemanager.domain.usecase

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes cleanup of pre-v6 Todo system identities; no legacy business writes remain. */
object TodoOperationCoordinator {
    private val mutex = Mutex()
    suspend fun <T> run(operation: suspend () -> T): T = mutex.withLock { operation() }
}
