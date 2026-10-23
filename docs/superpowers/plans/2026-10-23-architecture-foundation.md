# Architecture Foundation Implementation Plan

> **For agentic workers:** Use the Buddy developer, verifier, reviewer and tester roles for each independently reviewable task. All Gradle invocations are serialized by the primary agent.

**Goal:** Improve backup privacy, diary query behavior, navigation boundaries and reproducible verification while preserving existing data contracts.

**Architecture:** Retain domain/data/ui and the shared Room database. Introduce read invalidations and navigation ports, move the platform media adapter behind the existing UI port, and enforce boundaries automatically.

**Tech Stack:** Kotlin, Compose, Room8, Hilt, coroutines, Gradle9.4.1, JDK25, Python stdlib and PowerShell.

**Spec:** `docs/superpowers/specs/2026-10-23-architecture-foundation-design.md`

## Global Constraints

- applicationId com.example.lifemanager; versionName 1.4.0; versionCode 16; Room8; backupV4.
- No destructive migrations, new AI runtime, release publication or device actions on personal-data devices.
- Existing generation/session/revision/ticket and lock-order protections remain mandatory.

## Task 1: System backup and media boundary

Files: `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`, `domain/diary/DiaryMediaAccess.kt`, `data/diary/AndroidDiaryMediaAccess.kt`, `platform/media/DiaryImageDecoder.kt`, `di/DiaryModule.kt`, `data/backup/SystemBackupPolicyTest.kt`.

- [x] Verify a regression asserting `applicationInfo.flags and FLAG_ALLOW_BACKUP == 0` fails against the existing merged manifest.
- [x] Set allowBackup=false and attach old/new exclusion rules for root/file/database/sharedpref/external domains; preserve manual archive paths.
- [x] Move AndroidDiaryMediaAccess implementation to data, DiaryMediaAccess to domain and decodeDiaryImage to platform; update Hilt construction and test references.
- [x] Run backup policy, diary media/storage and archive regression tests.

## Task 2: Diary read model

Files: `domain/diary/DiaryRepository.kt`, `domain/diary/DiaryModels.kt`, `data/diary/DiaryDao.kt`, `data/diary/RoomDiaryRepository.kt`, `ui/diary/DiaryViewModel.kt` and their tests.

- [x] Add and run real Room tests for media-reference-only invalidations and bounded SELECT count as entry count grows.
- [x] Expose lightweight invalidation plus content snapshot; register observer before initial signal and remove on cancellation.
- [x] Read entry/draft rows and ordered media joins in one transaction; preserve absent-reference failure and shared-media ordering.
- [x] Publish only inside GenerationAccess.read; keep compatibility adapters for existing repository users.
- [x] Run diary ViewModel, repository, archive, migration and maintenance regressions.

## Task 3: Navigation ports

Files: `ui/navigation/PlanNavigationViewModel.kt`, `ui/navigation/V1NavGraph.kt`, new navigation ports/adapters, navigation tests.

- [x] Establish baseline using existing real-owner navigation tests.
- [x] Introduce plan/subscription editor ports exposing immutable snapshots and existing commands; adapters alone depend on concrete business ViewModels.
- [x] Wire remembered adapters into root graph and preserve branch ordering and original notification generation.
- [x] Verify changed-draft confirmations, notification consumption, maintaining/busy states and old rendered callbacks.

## Task 4: Reproducible build and boundary checks

Files: `gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `.github/workflows/android.yml`, `tools/check-project-structure.ps1`, checker and checker tests, historical Phase scripts.

- [x] Generate Wrapper9.4.1 with official distribution SHA256 and verify wrapper JAR SHA256.
- [x] Check domain/UI import boundaries, Room8 migration registration and no destructive migration, AI retirement and no INTERNET permission.
- [x] Demonstrate negative fixtures are rejected; mark historical scripts and replace documented current commands.
- [x] Add CI for structure checks and `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`.

## Task 5: Documentation and integration verification

Files: `ARCHITECTURE.md`, `CODEBASE_MAP.md`, `CODING_STANDARDS.md`, `README.md`, `CHANGELOG.md`, `docs/architecture-foundation-acceptance.md`, archived architecture documents.

- [x] Preserve historical documents and document the current dependency graph, data flows and lock ownership.
- [x] Run structural checker, Python checker tests, full JVM suite, Lint and Debug/AndroidTest compilation; record fresh counts, logs and limitations.
- [x] Review all changes against compatibility and user-visible behavior requirements; resolve findings and rerun affected checks.
- [x] Deliver reviewable local changes and evidence without representing them as a published version.
