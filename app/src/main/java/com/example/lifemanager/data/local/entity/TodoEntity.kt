package com.example.lifemanager.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.lifemanager.domain.model.TodoPriority

/** Historical v1–v5 wire/migration DTO only; not a production database entity or DAO. */
@Entity(
    tableName = "todos",
    indices = [Index("dueAt"), Index("isCompleted")],
)
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String?,
    val priority: TodoPriority,
    val dueAt: Long?,
    val isCompleted: Boolean,
    val completedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val parentId: Long?,
    val sortOrder: Int,
)
