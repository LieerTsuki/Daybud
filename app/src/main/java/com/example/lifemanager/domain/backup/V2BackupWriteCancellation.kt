package com.example.lifemanager.domain.backup

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job

/** Original writer governs SQL-body guards; transaction completion/receipt handoff is protected. */
internal class V2BackupWriteCancellation(val writer: Job) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<V2BackupWriteCancellation>
}
