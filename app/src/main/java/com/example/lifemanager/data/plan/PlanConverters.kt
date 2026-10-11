package com.example.lifemanager.data.plan

import androidx.room.TypeConverter
import com.example.lifemanager.domain.plan.PlanTimeMode

class PlanConverters {
    @TypeConverter fun fromTimeMode(value: PlanTimeMode): String = value.name
    @TypeConverter fun toTimeMode(value: String): PlanTimeMode = PlanTimeMode.valueOf(value)
    @TypeConverter fun fromLegacySource(value: LegacyPlanSource): String = value.name
    @TypeConverter fun toLegacySource(value: String): LegacyPlanSource = LegacyPlanSource.valueOf(value)
}
