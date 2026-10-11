package com.example.lifemanager.domain.plan

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared by all plan writes, deliveries and retries; global admission must be acquired first. */
object PlanOperationCoordinator {
    private val mutex = Mutex()
    suspend fun <T> run(operation: suspend () -> T): T = mutex.withLock { operation() }
}
