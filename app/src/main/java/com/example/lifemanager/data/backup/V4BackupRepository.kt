package com.example.lifemanager.data.backup

import androidx.room.withTransaction
import com.example.lifemanager.data.diary.FileDiaryStorage
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.diary.DiaryRepository
import com.example.lifemanager.domain.maintenance.DataGeneration
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The legacy fifteen tables and diary graph are captured from the same Room transaction. */
class V4BackupRepository(private val db:LifeManagerDatabase, private val legacy:V2BackupRepository,
    private val diary:DiaryRepository):V2BackupRepository {
    override suspend fun snapshot():V2BackupPayload = db.withTransaction {
        legacy.snapshot().copy(diary=diary.backupSnapshotWithinExistingTransaction())
    }
}

/** Older portable files omit diary; omission preserves the current diary graph. */
class V4BackupMutationRepository(private val db:LifeManagerDatabase, private val legacy:V2BackupMutationRepository,
    private val diary:DiaryRepository, private val storage:FileDiaryStorage?=null):V2BackupMutationRepository {
    override suspend fun replace(expected:DataGeneration,payload:V2BackupPayload):DataGeneration = mutate {
        payload.diary?.let(com.example.lifemanager.domain.diary.DiaryBackupValidator::validate)
        val next=legacy.replace(expected,payload.copy(diary=null))
        checkWriter()
        payload.diary?.let { diary.replaceWithinExistingTransaction(it) }
        next
    }
    override suspend fun clearBusinessData(expected:DataGeneration):DataGeneration = mutate {
        val next=legacy.clearBusinessData(expected)
        checkWriter()
        diary.clearWithinExistingTransaction()
        next
    }
    private suspend fun <T> mutate(block:suspend ()->T):T {
        checkWriter()
        suspend fun write():T = db.withTransaction {
            checkWriter();val result=block();checkWriter();result
        }
        return if(storage==null)write() else storage.withGate {
            try { write() } finally {
                // Transaction outcome can be unknown to this caller. Only freshly read durable
                // references decide what can be removed, never the planned replacement graph.
                withContext(NonCancellable) {
                    try {
                        if(!db.inTransaction()) {
                            val referenced=db.diaryDao().referencedMediaIds().toSet()
                            storage.garbageCollect(referenced)
                        }
                    } catch(_:Exception) { /* Preserve transaction outcome; startup retries GC. */ }
                }
            }
        }
    }
    private suspend fun checkWriter() {
        val context=currentCoroutineContext()
        context.ensureActive()
        context[V2BackupWriteCancellation]?.writer?.ensureActive()
    }
}
