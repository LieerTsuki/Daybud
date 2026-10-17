package com.example.lifemanager.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "habit_waterings", foreignKeys = [
    ForeignKey(entity = HabitRecordEntity::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = HabitTreeEntity::class, parentColumns = ["id"], childColumns = ["treeId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("treeId")])
data class HabitWateringEntity(@PrimaryKey val recordId: Long, val treeId: Long)
