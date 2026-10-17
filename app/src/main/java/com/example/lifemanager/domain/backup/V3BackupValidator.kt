package com.example.lifemanager.domain.backup

/** The shared DTO is storage-neutral; only this validator admits the current forest contract. */
object V3BackupValidator {
    fun validate(document: V2BackupDocument, limits: BackupLimits = BackupLimits()) {
        BackupValidator.validateMetadata(document.appVersion, document.exportedAt)
        validate(document.payload, limits)
    }
    fun validate(payload: V2BackupPayload, limits: BackupLimits = BackupLimits()) {
        checkBackup(payload.tables.keys == V3BackupSchema.tables.map { it.name }.toSet(), "v3备份业务表缺失或包含未知表")
        checkBackup(payload.tables.values.sumOf { it.size.toLong() } <= limits.maxRows, "备份超过 ${limits.maxRows} 行限制")
        // Preserve every historical raw field and semantic rule without relaxing the V2 entry point.
        V2BackupValidator.validate(payload.copy(tables = payload.tables.filterKeys { name -> V2BackupSchema.tables.any { it.name == name } }), limits)
        BackupValidator.validateTables(payload.tables, V3BackupSchema.tables, "plans")
        val trees = payload.tables.getValue("habit_trees").associateBy { it.fields.getValue("id") }
        val records = payload.tables.getValue("habit_records").associateBy { it.fields.getValue("id") }
        trees.values.forEach { checkBackup((it.fields.getValue("id") as BackupValue.Integer).value > 0, "树木主键必须为正整数") }
        val counts = HashMap<BackupValue, Int>()
        for (watering in payload.tables.getValue("habit_waterings")) {
            val treeId = watering.fields.getValue("treeId")
            val tree = trees.getValue(treeId)
            val record = records.getValue(watering.fields.getValue("recordId"))
            checkBackup(tree.fields.getValue("habitId") == record.fields.getValue("habitId"), "浇水记录不能跨任务使用")
            val count = (counts[treeId] ?: 0) + 1
            checkBackup(count <= 20, "每棵树最多浇水20次")
            counts[treeId] = count
        }
    }
}
