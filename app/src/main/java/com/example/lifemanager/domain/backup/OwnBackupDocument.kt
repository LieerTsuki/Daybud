package com.example.lifemanager.domain.backup

/** Bound/validate before allocating a private graph; own all mutable collections before suspension. */
internal fun ownV2Document(document: V2BackupDocument, limits: BackupLimits): V2BackupDocument {
    V3BackupValidator.validate(document, limits)
    document.payload.diary?.let(com.example.lifemanager.domain.diary.DiaryBackupValidator::validate)
    return document.copy(payload = document.payload.copy(
        tables = document.payload.tables.mapValues { (_, rows) -> rows.map { BackupRow(it.fields.toMap()) } },
        settings = document.payload.settings.copy(defaultReminderDays = document.payload.settings.defaultReminderDays.toSet()),
        diary = document.payload.diary?.let { diary -> diary.copy(
            entries = diary.entries.map { it.copy(media = it.media.map { medium -> medium.copy() }) },
            drafts = diary.drafts.map { it.copy(media = it.media.map { medium -> medium.copy() }) },
            media = diary.media.map { it.copy() },
            reviews = diary.reviews.map { it.copy(sections = it.sections.map { section -> section.copy(sourceIds = section.sourceIds.toList()) }, sources = it.sources.map { source -> source.copy() }) },
        ) },
    )).also { V3BackupValidator.validate(it, limits) }
}
