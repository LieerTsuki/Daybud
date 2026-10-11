package com.example.lifemanager.data.plan

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.Relation
import com.example.lifemanager.data.local.entity.TagEntity

@Entity(tableName = "plan_tag_cross_ref", primaryKeys = ["planId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = PlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ], indices = [Index("tagId")])
data class PlanTagCrossRef(val planId: Long, val tagId: Long)

@Entity(tableName = "plan_exceptions", primaryKeys = ["planId", "occurrenceDate"],
    foreignKeys = [ForeignKey(entity = PlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.CASCADE)])
data class PlanExceptionEntity(val planId: Long, val occurrenceDate: Long, val isCancelled: Boolean)

@Entity(tableName = "plan_legacy_refs", primaryKeys = ["source", "legacyId"],
    foreignKeys = [ForeignKey(entity = PlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["planId"], unique = true)])
data class PlanLegacyRefEntity(val source: LegacyPlanSource, val legacyId: Long, val planId: Long)

data class PlanWithRelations(
    @Embedded val entity: PlanEntity,
    @Relation(parentColumn = "id", entityColumn = "id",
        associateBy = Junction(value = PlanTagCrossRef::class, parentColumn = "planId", entityColumn = "tagId"))
    val tags: List<TagEntity>,
    @Relation(parentColumn = "id", entityColumn = "planId") val exceptions: List<PlanExceptionEntity>,
)
