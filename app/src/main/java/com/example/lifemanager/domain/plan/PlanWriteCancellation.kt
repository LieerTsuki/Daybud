package com.example.lifemanager.domain.plan

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Opt-in commit handoff: original writer controls the transaction BODY, not the committed return. */
internal class PlanWriteCancellation(val writer: Job) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PlanWriteCancellation>
}

internal suspend fun <T> cancellablePlanWriteBody(operation: suspend () -> T): T {
    val writer = currentCoroutineContext()[PlanWriteCancellation]?.writer
    return if (writer == null) operation() else withContext(writer) { operation() }
}
