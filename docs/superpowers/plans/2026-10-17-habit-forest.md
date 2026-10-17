# Habit Forest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace habit icon/color and heatmap with species illustrations and a persistent, manually watered forest.

**Architecture:** Keep existing HabitRepository and generation-guarded MVVM. Persist trees and one-to-one record consumption; derive growth and unused drops in a consistent transaction. Extend versioned backup with a separate current contract while preserving historical formats.

**Tech Stack:** Kotlin, Compose, Room, StateFlow, Robolectric, Android API35.

**Spec:** docs/superpowers/specs/2026-10-17-habit-forest-design.md

## Global Constraints

- Preserve all existing business rows, schema snapshots and strict historical backup verification.
- One successful record gives one drop usable only for its habit; manual watering; mature at20.
- No shared-branch push, public release, personal-device data changes or destructive migration.
- Gradle commands run serially under the controller.

### Task1: Persistent forest

Files: domain/model/HabitForest.kt; domain/repository/HabitRepository.kt; data/local/entity/HabitTreeEntity.kt, HabitWateringEntity.kt; data/local/dao/HabitForestDao.kt; data/local/migration/HabitForestMigration.kt; LifeManagerDatabase.kt; HabitRepositoryImpl.kt; corresponding Room tests.

Interfaces: TreeSpecies(PINE,GINKGO,CHERRY,MAPLE,OAK,BIRCH) with label,key and fromHabit(habit); ForestTree(id,habitId,species,plantedAt,wateringCount) with isMature; HabitForest(trees,availableDrops:Map<Long,Int>). HabitRepository.observeForest():Flow<HabitForest>, waterTree(treeId:Long), plantTree(habitId:Long).

- [x] RED: real production Room repository test calls20waterings, verifies count20, a21st fails, plant creates second count0, undo a used record restores corresponding first tree to19.
- [x] Run dedicated test, retain missing-feature failure evidence.
- [x] Implement additive migration and transactional repository using recordId PK consumption; use SQL COUNT and NOT EXISTS to avoid stored counter drift.
- [x] GREEN: run forest and existing habit Room tests, verify data preserved after edit/delete/failed transaction.

### Task2: Habit UI and tree artwork

Files: ui/habit/HabitScreen.kt, HabitUiState.kt, HabitViewModel.kt; new TreeIllustration.kt and ForestSection.kt; Compose/ViewModel tests.

Consumes Task1 interfaces. Keep HabitViewModel constructor unchanged; combine observeForest with existing invalidations and include forest in versioned snapshot. TreeSpecies.key is tree:ENUM; editor changes existing hidden iconKey.

- [x] RED: actual Compose new/edit screen has species choices and no icon/color input; forest watering button spends only matching task drop.
- [x] Implement native Compose/Canvas illustrations, stage-aware scaling and species silhouettes, forest grid, count/status/actions, maintenance/busy/error protection.
- [x] GREEN: run UI and maintenance regressions; verify light/dark, empty states and large text.

### Task3: Backup compatibility

Files: domain/backup and data/backup current contract, codec dispatch, raw SQL ordering; new tests and required fixtures.

Consumes exact schema habit_trees(id LONG PK auto,habitId LONG FK habits cascade,species TEXT enum,plantedAt MILLIS); habit_waterings(recordId LONG PK FK habit_records cascade,treeId LONG FK habit_trees cascade). No additional columns.

- [x] RED: roundtrip populated trees plus consumption, oldV2 strict hash conversion, reject watering cross-habit or over20, maintenance restore and clear include both tables.
- [x] Implement separate current format/schema7, preserving exact old formats. Validate reference identity and tree count, all-table row/byte budgets and autoincrement high water.
- [x] GREEN: run codec and real Room backup regressions; include production DB integration with actual forest rows.

### Task4: Review, verification and delivery

- [x] Review combined code for stale callbacks, transaction rollback, missing references and backup downgrade loss.
- [x] Run full JVM, Debug/androidTest/Release builds and lint serially; inspect XML totals and actual artifacts.
- [x] Run API35 dedicated device tests and screenshots when available; verify schema6→7 migration without clear/uninstall on an isolated synthetic database.
- [x] Update CHANGELOG, README, architecture/index, acceptance and evidence; report actual verified scope and remaining limitations.

Ruling: user approved design and implementation via“继续”; do not stop for a repeated design approval. Work on feat/habit-forest in the existing checkout because no separate worktree was requested. UI and backup are independently owned files; controller owns schema/repository and serial build execution.

Completion: 1187 JVM/103 suites, 72 API35 device tests, builds/lint and guarded same-certificate APK upgrade passed. See docs/habit-forest-acceptance.md. Initial persistence RED covered missing seed/backup features; watering boundary cases first executed GREEN and are not claimed as independent RED evidence. Date/first-tree/raw-key regression failures were observed before their fixes. Subsequent user authorization: push the validated source to GitHub main. Fast-forward integration and source push are authorized; no APK Release publication was requested.
