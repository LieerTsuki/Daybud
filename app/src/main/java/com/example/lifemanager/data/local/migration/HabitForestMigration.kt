package com.example.lifemanager.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive only: every historical record and hidden icon/color value remains unchanged. */
class HabitForestMigration : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS habit_trees (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, habitId INTEGER NOT NULL, species TEXT NOT NULL, plantedAt INTEGER NOT NULL, FOREIGN KEY(habitId) REFERENCES habits(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_habit_trees_habitId ON habit_trees (habitId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS habit_waterings (recordId INTEGER NOT NULL, treeId INTEGER NOT NULL, PRIMARY KEY(recordId), FOREIGN KEY(recordId) REFERENCES habit_records(id) ON DELETE CASCADE, FOREIGN KEY(treeId) REFERENCES habit_trees(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_habit_waterings_treeId ON habit_waterings (treeId)")
        db.execSQL("""INSERT INTO habit_trees(habitId,species,plantedAt)
            SELECT id,CASE WHEN iconKey IN ('tree:PINE','tree:GINKGO','tree:CHERRY','tree:MAPLE','tree:OAK','tree:BIRCH')
            THEN substr(iconKey,6) ELSE CASE ((id%6+6)%6) WHEN 0 THEN 'PINE' WHEN 1 THEN 'GINKGO'
            WHEN 2 THEN 'CHERRY' WHEN 3 THEN 'MAPLE' WHEN 4 THEN 'OAK' ELSE 'BIRCH' END END,createdAt
            FROM habits ORDER BY id""")
    }
}
