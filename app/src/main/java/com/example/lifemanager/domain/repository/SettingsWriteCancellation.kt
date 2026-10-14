package com.example.lifemanager.domain.repository

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*

/** Original writer controls SQL body; commit return and short receipt handoff survive cancellation. */
internal class SettingsWriteCancellation(val writer:Job):AbstractCoroutineContextElement(Key) {
    companion object Key:CoroutineContext.Key<SettingsWriteCancellation>
}
internal suspend fun <T> cancellableSettingsWriteBody(block:suspend ()->T):T {
    val writer=currentCoroutineContext()[SettingsWriteCancellation]?.writer
    return if(writer==null)block()else withContext(writer) { block() }
}
