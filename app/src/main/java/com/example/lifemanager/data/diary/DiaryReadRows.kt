package com.example.lifemanager.data.diary

import androidx.room.Embedded

/** LEFT JOIN keeps dangling references visible so reads still reject corrupt attachments. */
data class DiaryEntryMediaRow(
    @Embedded val reference: DiaryEntryMediaEntity,
    @Embedded(prefix = "medium_") val medium: DiaryMediaEntity?,
)

data class DiaryDraftMediaRow(
    @Embedded val reference: DiaryDraftMediaEntity,
    @Embedded(prefix = "medium_") val medium: DiaryMediaEntity?,
)
