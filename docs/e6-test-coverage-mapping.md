# E6 test coverage migration and retirement mapping

Date: 2026-10-15. Baseline: `3ddbef6`, branch `feat/v1-unified-plans`.

This is the test-owner disposition record for the approved E6 activation. The worker sections record authored tests and retained evidence, not worker-executed passing results. The test owner ran no Gradle commands, compiled no tests, and executed no device/emulator tests. Parent owns integration compilation, JVM execution and failure integration; final parent evidence is recorded below. Actual-device behavior remains unverified.

## Boundaries and semantics

Production moves to LifeManagerDatabase v6 and unified Plan/V1Settings/V2 backup consumers. Legacy TODO/SCHEDULE CRUD owners and old Settings/backup consumers are retired. Tests do not require production to retain those consumers.

Historical schema assets and legacy DTO/enums, v1 wire/hash validation and legacy conversion evidence remain. Historical migration endpoints use `LEGACY_MIGRATIONS`. Production factory fixtures use the full registered chain and v6 initialization. A fresh database starts at generation 0; a successful 5->6 conversion advances the existing generation once.

V1 changes some product semantics intentionally: `PlanTimeMode.NONE` is undated and is not implicitly a today item. Plan completion, recurrence, conflicts and filtering are governed by the unified contracts. Separate TODO and SCHEDULE owner behavior, old tab labels, old notification Activity APIs and the ten-v1-table/75-column export shape are not current UI/backup requirements. The original v1 wire evidence remains valid.

## Retired sources: this test owner

Paths in this section are relative to `app/src/test/java/com/example/lifemanager/` unless marked Android.

| Retired files | Retained guarantee / replacement |
| --- | --- |
| `data/repository/ScheduleRepositoryRoomTest.kt` | Existing `data/plan/PlanRepositoryRoomTest.kt`: editing preserves exception rows and creation identity; deleted editors cannot recreate records; intentional deletion cascades owned relations. New Android `data/plan/PlanDaoDeviceTest.kt` exercises those guarantees against the production factory. |
| `ui/todo/TodoViewModelTest.kt`; `ui/schedule/ScheduleViewModelTest.kt` | `ui/plan/PlanEditorViewModelTest.kt`, `PlanViewModelTest.kt`, `data/plan/PlanRepositoryRoomTest.kt`, `domain/plan/PlanUseCasesTest.kt`: validation, real writes/completion/deletion, duplicate-save prevention, retained failed drafts and committed-write receipts with effect-only retry. |
| `ui/todo/TodoScreenRegressionTest.kt`; `ui/schedule/ScheduleScreenRegressionTest.kt`; `ui/schedule/ScheduleEditorRegressionTest.kt` | `ui/plan/PlanScreenTest.kt`, `PlanEditorComposeTest.kt`, `PlanWorkspaceComposeTest.kt`, `PlanOverviewComposeTest.kt`, `PlanOverviewWorkspaceTest.kt`, `PlanDraftTest.kt`: actual controls, filtered/empty/error states, accessible completion, date-mode entry, identity-preserving edits, dirty-close decisions, calendar/statistics workspace. Shared DatePresentation evidence remains. |
| `ui/todo/TodoMaintenanceTest.kt`; `ui/schedule/ScheduleMaintenanceTest.kt` | `PlanViewModelTest.kt` and `PlanEditorViewModelTest.kt`: synchronous maintenance rejection without replay, cancellation retaining drafts, replacement invalidating old callbacks, duplicate writes blocked through effects, persisted receipts surviving cancellation. `data/plan/PlanRepositoryRoomTest.kt` rejects stale generations at the storage boundary. Device business-consumer port adds real-Room draft/reused-ID coverage. |
| `ui/settings/SettingsViewModelTest.kt`; `ReminderSettingsViewModelTest.kt`; `SettingsMaintenanceTest.kt` | Existing `ui/settings/V1SettingsViewModelTest.kt` and `data/plan/V1SettingsRoomTest.kt`; ported `data/repository/SettingsRepositoryRoomTest.kt`; Android `MaintenanceConsumerDeviceTest.kt`. Independent preference transforms, read/write errors, reminder-effect failure after commit, maintenance rejection, cancellation and retained-generation checks use the unified preferences. |
| `ui/settings/BackupSettingsViewModelTest.kt`; `BackupSettingsDialogsTest.kt` | Existing `V1BackupSettingsViewModelTest.kt`, `V1BackupSettingsDialogsTest.kt`, `V1BackupPickerTest.kt`, `data/backup/V1BackupSettingsRoomTest.kt`: consent, preview provenance/counts, picker/operation identity, independent final confirmation, stale requests, cancellation and committed/unknown outcome handling. |
| `ui/settings/TestSettingsOwner.kt` | Helper had only retired TODO/SCHEDULE Compose consumers. Unified suites own their own V1Settings fixtures. No replacement production compatibility wrapper was introduced. |
| `data/backup/BackupRepositoryRoomTest.kt` | Existing `V2BackupRepositoryRoomTest.kt`: complete SQL snapshot, empty defaults, raw field fidelity, strict stored types/settings, row/byte limits and consistent transaction snapshots. Device snapshot/file ports compare every independently seeded v6 field. V1 wire tests remain separately. |
| `data/backup/BackupMutationRepositoryRoomTest.kt` | Existing `V2BackupMutationRepositoryRoomTest.kt`: full replacement, clear versus restore preferences, stale/missing/exhausted metadata, transaction rollback, cancellation, identity/sequence integrity. Device maintenance port adds native SQL-failure rollback of rows/settings/generation/inventory/sequences. |
| `domain/backup/BackupFileUseCasesTest.kt` | Existing `V2BackupFileUseCasesTest.kt`: output close and independent readback, different/truncated output rejection, permission/limit failures, bounded staging cleanup, admission release before slow provider IO, cancellation, and strict legacy import conversion. |
| `domain/backup/BackupMaintenanceWorkflowTest.kt` | Existing `data/backup/V2BackupMaintenanceWorkflowRoomTest.kt`: verified protection, separate confirmation, source overwrite refusal, picker/coroutine cancellation, SQL rollback, stale requests, clear preferences, captured identities, committed versus pending/unknown outcome. Android maintenance port uses real V2 adapters on one factory DB. |
| Android `data/local/TodoDaoTest.kt`; `data/local/ScheduleDaoTest.kt` | New Android `data/plan/PlanDaoDeviceTest.kt`: completion/undo, tag uniqueness, FK rejection, exception/creation preservation, deleted drafts, parent detach and own-relation cascade, stale-token protection of reused identities. Legacy separate DAOs are intentionally retired. |
| Android `ui/todo/TodoNotificationActivityTest.kt`; `ui/schedule/ScheduleNotificationActivityTest.kt` | Parent/UI-owned JVM `ui/navigation/V1ActivityActivationTest.kt` exercises the activated Activity/graph, generation, dirty drafts, save/complete/reopen and nine retained owners. `V1NavigationComposeTest.kt`, `PlanNavigationViewModelTest.kt` and `PlanNotificationClickTest.kt` are the actual unified suite names. The ported Android `TopLevelNavigationActivityTest.kt` covers unified routes/notification guards; this mapping does not establish equivalent actual-device execution. |

Retirement follows removal of the old owners. It does not preserve historical test counts or claim a one-to-one equivalent for every coroutine interleaving formerly exercised inside those owners. Existing Plan tests cover the named surviving contracts; exhaustive scheduling/interleaving parity has not been established.

## Ported and added sources: this test owner

| Files | Actual disposition and assertions |
| --- | --- |
| JVM `data/repository/DataGenerationRepositoryRoomTest.kt` | Production factory fixture; real Plan rows replace TODO rows. Keeps initialization/reopen, atomic data+generation commit, failures/cancellation, CAS competition, stale rejection, overflow and accidental generation mutation rollback. Retains negative/fractional/text/missing generation failures; adds real extra-ID and wrong-singleton-ID fixtures for finite read, observation and commit rejection without repair. No initialize/fallback API. |
| JVM `data/repository/HabitRepositoryRoomTest.kt` | Production factory fixture; all existing eligibility, historical-record preservation, stale-edit and cascade cases retained. |
| JVM `data/repository/SettingsRepositoryRoomTest.kt` | RoomV1SettingsRepository on production factory. Real defaults; no insertion on finite observations; missing singleton read/update rejection before transform; all eight reminder masks and independent fields; invalid input rollback; file reopen; concurrent independent transforms; sequential day transforms preserve prior commits. The old finite getSettings API is retired; finite reads are observe().first(). |
| JVM `data/local/SettingsMigrationTest.kt` | Fresh production v6 defaults/generation 0; historical v4 file through actual factory to v6/generation 1. Checks mapped Plan identity/text/creation via legacy refs, transferred tag/exception rows, retained Habit/Subscription tables and large money value. Historical ten-table cell equality remains in the Android historical-endpoint suite. |
| JVM `ui/settings/SettingsScreenTest.kt` | Uses PreferenceContent/PreferenceState/PreferenceValues directly: selection callbacks, disabled saving/maintenance controls, reload, date/currency/reminder-day presentation, unified module identity, and currency draft cancellation versus generation reset. Removed old-owner delayed-observation integration case maps to V1 owner tests and the new real-Room sequential transform case; this is not claimed as the identical old scheduling test. |
| New JVM `data/local/V6RoomFixture.kt` | Unique named database from LifeManagerDatabaseFactory; closes and deletes only its exact fixture database. |
| Android `data/local/DataGenerationDeviceTest.kt` | Production factory, Plan rows, cancellation rollback, maintenance admission rejection, stale same-ID token rejection, and persistence across reopen. |
| Android `data/local/HabitDaoTest.kt`; `SubscriptionDaoTest.kt` | Production factory initialization; original DAO/repository semantics remain: unique habit-day records, preserved record/payment identities, edit timestamps, reminder replacement, rollback and cascades. |
| Android `data/local/MaintenanceConsumerDeviceTest.kt` | Real Habit and V1Settings consumers on factory DB. Retains Habit cancelled-freeze draft behavior and stale actions; ports settings replacement/old-theme rejection to V1SettingsViewModel and RoomV1SettingsRepository. |
| Android `data/local/BusinessMaintenanceConsumerDeviceTest.kt` | Replaces old TODO/SCHEDULE owners with PlanEditorViewModel + real repository/use cases/reminder settings. Cancelled maintenance preserves draft; committed same-ID replacement clears it and rejects old actions. Retains existing real Subscription consumer case, using RoomV1SettingsRepository. No UI subscription test file changed. |
| Android `data/local/HabitMigrationTest.kt`; `SubscriptionMigrationTest.kt` | Historical 2->3 and 3->4 assertions remain; arrays changed to LEGACY_MIGRATIONS. |
| Android `data/local/SettingsMigrationDeviceTest.kt` | Historical 1/2/3->5 and v4->5 all-cell evidence remains using LEGACY_MIGRATIONS. Adds historical v5 proof followed by actual factory v6 upgrade/reopen, unchanged-table cells, combined Plan reminder flag/per-item eligibility, exact source refs, preferences, generation advancement and FK integrity. |
| New Android `data/plan/PlanDaoDeviceTest.kt` | Real production-factory CRUD/relation/reused-ID tests described above. |
| New Android `data/local/V6DeviceFixture.kt` | Unique production-factory database; exact owned-file cleanup. |
| New Android `data/backup/V2BackupDeviceFixture.kt` | Independent SQL seeds all ten v6 business tables plus preferences/generation/reminder inventory. Snapshots, mutations, file use cases, generation, coordinator, capture and workflow share this exact DB. Only platform notification enumeration/effects are substituted; no real alarm/notification cleanup claim. |
| Android `data/local/BackupSnapshotDeviceTest.kt` | V2 encoding/decoding preserves every independently seeded raw business column and preferences, signed Long boundaries and above-double-precision money. Generation/inventory/sequences stay unchanged and are absent from the portable table set. |
| Android `data/backup/BackupFilesDeviceTest.kt` | Real Downloads ContentResolver IO, overwrite/truncation, close/reopen verification, V2 preview/counts, corrupt/deleted-source failure and private staging cleanup. SQL business and technical state unchanged. No picker/SAF selection claim from these tests. |
| Android `data/backup/BackupMaintenanceDeviceTest.kt` | Real Downloads protection and native V2 workflow; independent final confirmation, cancellation, restore/clear, settings distinction, single generation advance, captured source identities, preserved technical inventory/sequences, trigger-failure rollback and FK checks. Android 29+ for Downloads provider. |
| Android `ui/settings/SettingsActivityTest.kt` | V1Settings owner and unified Settings destination. Re-fetches the current owner after recreation; restores preferences using the live generation. Authored recreation/persistence coverage, not an executed-device result. |
| Android `ui/settings/BackupPickerActivityTest.kt` | V1Settings readiness and current V2 labels; retains real system source/destination picker cancellation and privacy-consent flow. No restore/export execution claim from picker cancellation. |
| Android `ui/settings/AppPagePerformanceDeviceTest.kt` | Unified Plan/Habit/Subscription/Settings usable-control smoke. Timings include instrumentation/idleness; no benchmark, frame-rate, offline-network verification or speed improvement claim. |
| Android `ui/todo/PlannerAccessibilityDeviceTest.kt` | File retained but presentation ported to PlanScreen with real retained PlanViewModel. Light/dark 320dp/200% font control/text-fit checks remain. Old TODO progress strip is retired; no equivalent native Plan-statistics accessibility result is claimed. |

## Preserved evidence and other owners

Unchanged by this owner:

- `data/backup/BackupCodecTest.kt`, `BackupFixtures.kt`, `CanonicalBackupJsonTest.kt`: original format-v1 field/types/hash/canonical evidence.
- `VersionedBackupCodecTest.kt`, `LegacyBackupConverterTest.kt`, V2 codec/validator and existing V2 Room suites: original hash verified before conversion, source-format routing and target validation. Existing V2 Room suites retain their dedicated test databases; the added device ports supply production-factory coverage.
- Schema assets/history and legacy conversion/reference tests.
- `ui/settings/SettingsThemeTest.kt`, `DatePresentationTest.kt`, `SettingsPlatformSectionBehaviorTest.kt`; standalone `SettingsPlatformSectionAccessibilityDeviceTest.kt`.
- All `ui/settings/V1*.kt` test files; all notification-package tests; all UI navigation/subscription test files.
- Parent-owned `data/local/V1ProductionDatabaseTest.kt`, JVM `data/plan/PlanMigrationTest.kt`, and Android `data/plan/PlanMigrationDeviceTest.kt`. Parent owns the full-chain/legacy-array integration there.

Parent additionally retires JVM `domain/usecase/TodoRulesTest.kt` (five tests) and `ScheduleRulesTest.kt` (three tests), plus their obsolete pure production models/rules. This owner did not edit/delete those files. Mapping: `domain/plan/PlanRulesTest.kt`, `PlanOccurrencesTest.kt`, `PlanConflictsTest.kt`, `PlanListQueryTest.kt`. Recurrence, overlaps, boundaries, completion and query behavior follow the unified V1 contract; NONE is undated, not today. Shared ScheduleRepeatRule and TodoPriority remain.

## Validation status and open limits at source freeze

### Parent integration: notification and navigation owners

The following maps the additional owner edits to concrete current suites. It is a
coverage disposition, not proof that all old interleavings are equivalent or that
device tests ran. The final command results live in the E6 acceptance record.

| Retired / ported sources | Current coverage boundary |
| --- | --- |
| `notification/TodoReminderAdmissionTest`, `TodoReminderDeliveryTest`, `TodoReminderRegressionTest`, `ScheduleReminderAdmissionTest`, `ScheduleReminderEligibilityTest`, `ScheduleNotificationRegressionTest` | Old successful TODO/SCHEDULE delivery is intentionally removed. `PlanReminderIntegrationTest`, `PlanReminderProtocolTest`, `UnifiedPlanCalibrationTest`, `UnifiedBroadcastRepairTest`, and `ReminderBroadcastRunnerAdmissionTest` exercise unified qualification, original admission, cancellation repair and state transitions. `E6ReminderActivationTest` checks shipped activation and rejects legacy receiver access even for colliding IDs. Historical component identities remain tombstones, not a second delivery path. |
| `notification/ReminderCalibrationQueueTest`, `ReminderWorkerMaintenanceTest` | `UnifiedWorkerRoutingTest`, `E6ReminderActivationTest`, and the ported Android `WorkerMaintenanceDeviceTest` cover reason-only durable routing, current reads, persisted legacy work drain, maintenance refusal and worker reflection. No Android execution is claimed. |
| `notification/ReminderTestSchedules`, `ReminderTestSettings` | Retired helpers for deleted repositories. Unified Room sources and V1 preferences replace them; no legacy production interface is retained for test compilation. |
| `notification/ReminderSafetyIntegrationTest`, `ReminderSourceGenerationTest`, `StartupCalibrationTest`, `SubscriptionReminderAdmissionTest` | Ported surviving subscription/generation/startup guarantees to current preferences and the unified queue. The related existing subscription rule/delivery/reconciliation and operation-coordinator suites remain. |
| `ui/navigation/TodoNavigationViewModelTest`, `ScheduleNavigationViewModelTest`, `SubscriptionNavigationViewModelTest` | Old per-module owners are removed. `PlanNavigationViewModelTest`, ported `NavigationMaintenanceTest` and `NotificationSourceNavigationTest` validate the active owner, original generations, latest tickets, maintenance and stale consent. |
| `ui/navigation/TopLevelNavigationTest` | Retired old five-route NavController fixture. Actual Activity owner retention and four destinations are covered by `V1ActivityActivationTest` and `V1NavigationComposeTest`; this is not a claim that an unused old back-stack implementation remains supported. |
| Android `ui/navigation/TopLevelNavigationActivityTest`, `ui/subscription/SubscriptionScreenTest` | Ported during integration to retained V1 owners/current preferences. Compilation and actual execution are separate gates. |

The originally proposed names `PlanNavigationComposeTest` and
`PlanNotificationNavigationTest` are not suite names in this repository. Their
intended boundaries refer to `V1NavigationComposeTest`,
`PlanNavigationViewModelTest`, `PlanNotificationClickTest`, and
`V1ActivityActivationTest`. No absent suite is counted.

- Source edits frozen for parent's first integration compile. No source writes after the freeze announcement; this mapping document was written afterward.
- Static inspection checked remaining owned tests for references to SettingsUiState, BackupPreview, old Settings owners and removed TODO/SCHEDULE/settings DAO calls; none were found. Historical SQL/DTO references deliberately remain.
- No Gradle, Kotlin compilation, JVM test run, device build, device/emulator execution, Git mutation, commit, push or subagent was performed by this owner.
- At worker freeze, parent still had to compile/run integrated JVM tests, compile Android tests, reconcile navigation suite names and integrate failures. The final parent result below supersedes this pending state; it does not turn the worker's static checks into runtime evidence.
- Real device coverage remains outstanding for native SQLite migrations/transactions, Downloads IO, system pickers, Activity recreation and accessibility/font layouts. JVM activation/navigation tests are not substitute evidence of device execution.
- Backup device fixtures substitute platform reminder ports. Actual alarm/notification delivery and cleanup remain the notification owner's responsibility.
- No claim of exhaustive parity for retired-owner coroutine interleavings or the removed TODO-specific progress UI. Parent can request targeted surviving-contract regressions after compilation.

### Final parent integration evidence (2026-10-15)

The final integrated source passed `:app:testDebugUnitTest --rerun`,
`:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:lintDebug`:
1122 JVM tests / 92 suites, zero failures/errors/skips, BUILD SUCCESSFUL in
8m47s (101 tasks: 23 executed / 78 up-to-date). Debug was repackaged; androidTest
had a KAPT recheck and cached Kotlin/Java/package checks, not device execution.
All three lint source analyses and the final report ran; fresh XML has 0 errors
and 47 warnings. Actual command, timestamps, failed attempts and limitations are
in `v1-unified-plans-acceptance.md`.

Production-factory tests (6), shipped reminder activation tests (12), actual
Activity tests (6) and PlanWorkspace tests (5) all passed in that full run.
The Activity JVM fixture directly drives Robolectric lifecycle/nonconfiguration
recreation while keeping real Application/Hilt/Room and nine retained owners;
Android ActivityScenario tests were not executed. The temporary native SQLite
path diagnostics and Activity state logs were removed after root-cause proof.
No production hooks or weakened stale-action assertions were introduced.

The 1400/120-suite baseline becomes 1122/92 after explicit old-owner retirement,
ports and additions. This is the discovered integrated count, not a claimed
one-to-one interleaving equivalence or a target to preserve obsolete APIs.
Independent source rereview found no actionable residual issues. F's installed
upgrade, device/provider, release/version and performance gates remain open.


## Exact worker file manifest

Repository-relative paths below identify only this worker's edits. Parent/other-owner changes are excluded.

### Added (5, including this document)

- `app/src/test/java/com/example/lifemanager/data/local/V6RoomFixture.kt`
- `app/src/androidTest/java/com/example/lifemanager/data/local/V6DeviceFixture.kt`
- `app/src/androidTest/java/com/example/lifemanager/data/plan/PlanDaoDeviceTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/data/backup/V2BackupDeviceFixture.kt`
- `docs/e6-test-coverage-mapping.md`

### Ported (20)

- `app/src/test/java/com/example/lifemanager/data/repository/DataGenerationRepositoryRoomTest.kt`
- `app/src/test/java/com/example/lifemanager/data/repository/HabitRepositoryRoomTest.kt`
- `app/src/test/java/com/example/lifemanager/data/repository/SettingsRepositoryRoomTest.kt`
- `app/src/test/java/com/example/lifemanager/data/local/SettingsMigrationTest.kt`
- `app/src/test/java/com/example/lifemanager/ui/settings/SettingsScreenTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/data/local/DataGenerationDeviceTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/data/local/HabitDaoTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/data/local/SubscriptionDaoTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/data/local/MaintenanceConsumerDeviceTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/data/local/BusinessMaintenanceConsumerDeviceTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/data/local/HabitMigrationTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/data/local/SubscriptionMigrationTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/data/local/SettingsMigrationDeviceTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/data/local/BackupSnapshotDeviceTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/data/backup/BackupFilesDeviceTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/data/backup/BackupMaintenanceDeviceTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/ui/settings/SettingsActivityTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/ui/settings/BackupPickerActivityTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/ui/settings/AppPagePerformanceDeviceTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/ui/todo/PlannerAccessibilityDeviceTest.kt`

### Retired (22)

- `app/src/test/java/com/example/lifemanager/data/repository/ScheduleRepositoryRoomTest.kt`
- `app/src/test/java/com/example/lifemanager/ui/todo/TodoViewModelTest.kt`
- `app/src/test/java/com/example/lifemanager/ui/todo/TodoScreenRegressionTest.kt`
- `app/src/test/java/com/example/lifemanager/ui/todo/TodoMaintenanceTest.kt`
- `app/src/test/java/com/example/lifemanager/ui/schedule/ScheduleViewModelTest.kt`
- `app/src/test/java/com/example/lifemanager/ui/schedule/ScheduleScreenRegressionTest.kt`
- `app/src/test/java/com/example/lifemanager/ui/schedule/ScheduleEditorRegressionTest.kt`
- `app/src/test/java/com/example/lifemanager/ui/schedule/ScheduleMaintenanceTest.kt`
- `app/src/test/java/com/example/lifemanager/ui/settings/SettingsViewModelTest.kt`
- `app/src/test/java/com/example/lifemanager/ui/settings/ReminderSettingsViewModelTest.kt`
- `app/src/test/java/com/example/lifemanager/ui/settings/SettingsMaintenanceTest.kt`
- `app/src/test/java/com/example/lifemanager/ui/settings/BackupSettingsViewModelTest.kt`
- `app/src/test/java/com/example/lifemanager/ui/settings/BackupSettingsDialogsTest.kt`
- `app/src/test/java/com/example/lifemanager/ui/settings/TestSettingsOwner.kt`
- `app/src/test/java/com/example/lifemanager/data/backup/BackupRepositoryRoomTest.kt`
- `app/src/test/java/com/example/lifemanager/data/backup/BackupMutationRepositoryRoomTest.kt`
- `app/src/test/java/com/example/lifemanager/domain/backup/BackupFileUseCasesTest.kt`
- `app/src/test/java/com/example/lifemanager/domain/backup/BackupMaintenanceWorkflowTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/data/local/TodoDaoTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/data/local/ScheduleDaoTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/ui/todo/TodoNotificationActivityTest.kt`
- `app/src/androidTest/java/com/example/lifemanager/ui/schedule/ScheduleNotificationActivityTest.kt`
