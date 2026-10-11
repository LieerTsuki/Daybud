# Room schema snapshots

Room schema JSON snapshots are generated here by KAPT. Future schema changes must add an explicit `Migration` to `LifeManagerDatabase.MIGRATIONS`; destructive migration is not allowed.

Version 5 adds only `app_settings` (persisted preferences) and `app_maintenance`
(local data generation). The ten v4 business table definitions and fields remain
unchanged. `4 -> 5` creates these two tables and seeds id=1 defaults; earlier
migrations remain registered. No generation is imported from a user backup.

Phase 5 backup/restore now uses the fixed v5 business schema in a single Room
transaction, together with settings replacement and local generation advancement.
Clear removes business rows but preserves settings. No schema v6 or migration is
needed: the schema itself has not changed. Functional verification is recorded
separately in docs/phase5-acceptance.md; a schema snapshot is not acceptance proof.

## V1 unified plans: isolated B3 target

Production `LifeManagerDatabase` remains version 5 and the historical snapshots
2–5 are unchanged. `com.example.lifemanager.data.plan.PlanMigrationTestDatabase/6.json`
is KAPT's exported **complete target fixture**, not an activated production schema.
It includes the four plan tables, merged plan reminder settings, maintenance,
unchanged tags, habits and subscriptions. The Android fixture uses the same entity
set and the same `PlanMigration(5,6)` implementation without exporting a second file.

Real Room file-open tests validate this target, historical 1→6 through 5→6 chains,
new-install defaults and failed-upgrade rollback. Version 1 has no exported asset;
tests reconstruct its three unchanged todo/tag entities from v2, as existing
historical tests do. This is not a recovered original v1 snapshot. Results and
limits are recorded in `docs/v1-unified-plans-acceptance.md`.

Only the later atomic activation batch E may register the production v6 entities,
migration and initialization callback together with all consumers. No destructive
fallback is allowed, and this fixture does not authorize upgrading personal data.
