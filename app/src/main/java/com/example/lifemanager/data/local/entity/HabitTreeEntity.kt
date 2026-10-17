package com.example.lifemanager.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "habit_trees", foreignKeys = [ForeignKey(entity = HabitEntity::class,
    parentColumns = ["id"], childColumns = ["habitId"], onDelete = ForeignKey.CASCADE)], indices = [Index("habitId")])
data class HabitTreeEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val habitId: Long,
    val species: String, val plantedAt: Long)
