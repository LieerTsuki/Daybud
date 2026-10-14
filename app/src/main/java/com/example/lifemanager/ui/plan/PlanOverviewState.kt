package com.example.lifemanager.ui.plan

import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.plan.*
import java.time.LocalDate
import java.time.ZoneId

data class PlanCalendarState(
    val generation:DataGeneration?=null,
    val selectedDate:LocalDate,
    val mode:PlanCalendarMode=PlanCalendarMode.MONTH,
    val includeCompleted:Boolean=false,
    val displayZone:ZoneId,
    val days:Map<LocalDate,List<PlanListRow>> = emptyMap(),
    val isAvailable:Boolean=false,
    val isLoading:Boolean=true,
    val isMaintaining:Boolean=false,
    val readError:String?=null,
    val message:String?=null,
)

data class PlanStatisticsState(
    val generation:DataGeneration?=null,
    val statistics:PlanStatistics?=null,
    val todayScheduledCount:Int?=null,
    val today:LocalDate,
    val displayZone:ZoneId,
    val isAvailable:Boolean=false,
    val isLoading:Boolean=true,
    val isMaintaining:Boolean=false,
    val readError:String?=null,
)
