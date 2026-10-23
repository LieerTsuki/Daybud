"""Negative fixtures for the architecture guardrails; no Android/Gradle needed."""
from pathlib import Path
import shutil
import tempfile
import unittest

from check_project_structure import check_backup_policy, check_database, check_dependencies, check_retirement, check_wrapper


PREFIX = "app/src/main/java/com/example/lifemanager/"


class StructureChecksTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def write(self, path, content):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    def source(self, path, content):
        self.write(PREFIX + path, content)

    def test_domain_can_use_jvm_media_port(self):
        self.source("domain/diary/Media.kt", "import java.io.File\nimport java.io.Closeable\n")
        self.assertEqual([], check_dependencies(self.root))

    def test_domain_rejects_platform_and_outer_layer_imports(self):
        for dependency in ("android.net.Uri", "androidx.room.Room", "com.example.lifemanager.data.diary.DiaryDao", "com.example.lifemanager.ui.diary.DiaryScreen"):
            with self.subTest(dependency=dependency):
                self.source("domain/diary/Media.kt", "import " + dependency + " as Hidden\n")
                self.assertTrue(check_dependencies(self.root))

    def test_ui_rejects_database_and_storage_imports(self):
        for dependency in ("com.example.lifemanager.data.diary.FileDiaryStorage", "com.example.lifemanager.data.local.*", "androidx.room.Room", "android.database.sqlite.SQLiteDatabase"):
            with self.subTest(dependency=dependency):
                self.source("ui/diary/Screen.kt", "import " + dependency + "\n")
                self.assertTrue(check_dependencies(self.root))

    def test_domain_rejects_notification_implementation(self):
        self.source("domain/plan/Reminder.kt", "import com.example.lifemanager.notification.PlanReminderScheduler\n")
        self.assertTrue(check_dependencies(self.root))

    def test_data_rejects_ui_dependencies(self):
        self.source("data/diary/Storage.kt", "import com.example.lifemanager.ui.diary.DiaryMediaAccess\n")
        self.assertTrue(check_dependencies(self.root))

    def test_ui_allows_domain_port_and_static_license_catalog(self):
        self.source("ui/diary/Screen.kt", "import com.example.lifemanager.domain.diary.DiaryMediaAccess\nimport com.example.lifemanager.data.about.OfflineLicenses\n")
        self.assertEqual([], check_dependencies(self.root))

    def database_fixture(self):
        self.source("data/local/LifeManagerDatabase.kt", """
            @Database(version = 8, exportSchema = true)
            class LifeManagerDatabase {
                val LEGACY_MIGRATIONS = arrayOf(Migration(1, 2), Migration(2, 3), Migration(3, 4), Migration(4, 5))
                val MIGRATIONS = LEGACY_MIGRATIONS + arrayOf(PlanMigration(), HabitForestMigration(), DiaryMigration())
            }
        """)
        for name, start in (("PlanMigration", 5), ("HabitForestMigration", 6), ("DiaryMigration", 7)):
            self.source(f"data/local/migration/{name}.kt", f"class {name} : Migration({start}, {start + 1})")
        self.source("data/local/LifeManagerDatabaseFactory.kt", "Room.databaseBuilder(context, LifeManagerDatabase::class.java, name).addMigrations(*LifeManagerDatabase.MIGRATIONS).build()")
        for version in range(2, 9):
            self.write(f"app/schemas/com.example.lifemanager.data.local.LifeManagerDatabase/{version}.json", '{"database":{"version":' + str(version) + '}}')

    def test_current_database_is_accepted(self):
        self.database_fixture()
        self.assertEqual([], check_database(self.root))

    def test_unregistered_migration_is_rejected(self):
        self.database_fixture()
        self.source("data/local/LifeManagerDatabaseFactory.kt", "Room.databaseBuilder(context, LifeManagerDatabase::class.java, name).build()")
        self.assertTrue(any("register" in error for error in check_database(self.root)))

    def test_missing_migration_edge_is_rejected(self):
        self.database_fixture()
        self.source("data/local/migration/DiaryMigration.kt", "class DiaryMigration : Migration(6, 8)")
        self.assertTrue(any("7 -> 8" in error for error in check_database(self.root)))

    def test_destructive_fallback_in_another_production_file_is_rejected(self):
        self.database_fixture()
        self.source("di/OtherFactory.kt", "builder.fallbackToDestructiveMigrationOnDowngrade()")
        self.assertTrue(any("destructive" in error for error in check_database(self.root)))

    def test_schema_version_mismatch_is_rejected(self):
        self.database_fixture()
        self.write("app/schemas/com.example.lifemanager.data.local.LifeManagerDatabase/8.json", '{"database":{"version":7}}')
        self.assertTrue(any("schema" in error for error in check_database(self.root)))

    def retirement_fixture(self):
        self.write("app/src/main/AndroidManifest.xml", '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application /></manifest>')
        self.write("app/build.gradle.kts", "plugins {}")
        self.source("data/diary/ai/DiaryAiWorker.kt", 'override suspend fun doWork(): Result = Result.success()\nfun retire(context: Context) { work.cancelUniqueWork("legacy") }')
        self.source("data/diary/ai/LegacyCloudRetirement.kt", "class LegacyCloudRetirement")
        self.source("LifeManagerApp.kt", "DiaryAiWorker.retire(this)\nbusiness.retiredCloud().retire()")

    def test_retired_worker_and_cleanup_are_accepted(self):
        self.retirement_fixture()
        self.assertEqual([], check_retirement(self.root))

    def test_internet_permission_is_rejected(self):
        self.retirement_fixture()
        self.write("app/src/main/AndroidManifest.xml", '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.INTERNET"/><application /></manifest>')
        self.assertTrue(any("INTERNET" in error for error in check_retirement(self.root)))

    def test_active_ai_class_is_rejected(self):
        self.retirement_fixture()
        self.source("data/diary/ai/DiaryAiEngine.kt", "class DiaryAiEngine")
        self.assertTrue(any("active AI" in error for error in check_retirement(self.root)))

    def test_worker_with_additional_execution_is_rejected(self):
        self.retirement_fixture()
        self.source("data/diary/ai/DiaryAiWorker.kt", "override suspend fun doWork(): Result { send(); return Result.success() }")
        self.assertTrue(any("no-op" in error for error in check_retirement(self.root)))

    def test_native_inference_build_is_rejected(self):
        self.retirement_fixture()
        self.write("app/build.gradle.kts", 'externalNativeBuild { cmake { path = file("CMakeLists.txt") } }')
        self.assertTrue(any("native" in error for error in check_retirement(self.root)))

    def backup_fixture(self):
        self.write("app/src/main/AndroidManifest.xml", '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:allowBackup="false" android:fullBackupContent="@xml/backup_rules" android:dataExtractionRules="@xml/data_extraction_rules" /></manifest>')
        domains = ("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
        exclusions = ''.join(f'<exclude domain="{domain}" path="."/>' for domain in domains)
        self.write("app/src/main/res/xml/backup_rules.xml", '<full-backup-content>' + exclusions + '</full-backup-content>')
        self.write("app/src/main/res/xml/data_extraction_rules.xml", '<data-extraction-rules><cloud-backup>' + exclusions + '</cloud-backup><device-transfer>' + exclusions + '</device-transfer></data-extraction-rules>')

    def test_fully_wired_backup_exclusions_are_accepted(self):
        self.backup_fixture()
        self.assertEqual([], check_backup_policy(self.root))

    def test_allow_backup_or_disconnected_rules_are_rejected(self):
        for original, replacement in (("allowBackup=\"false\"", "allowBackup=\"true\""), ("@xml/backup_rules", "@xml/unused"), ("@xml/data_extraction_rules", "@xml/unused")):
            with self.subTest(original=original):
                self.backup_fixture()
                path = "app/src/main/AndroidManifest.xml"
                self.write(path, (self.root / path).read_text().replace(original, replacement))
                self.assertTrue(check_backup_policy(self.root))

    def test_every_group_requires_all_nine_whole_domain_exclusions(self):
        import xml.etree.ElementTree as ET
        for filename, group in (("backup_rules.xml", None), ("data_extraction_rules.xml", "cloud-backup"), ("data_extraction_rules.xml", "device-transfer")):
            for domain in ("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref"):
                with self.subTest(filename=filename, group=group, domain=domain):
                    self.backup_fixture()
                    path = "app/src/main/res/xml/" + filename
                    document = ET.fromstring((self.root / path).read_text())
                    section = document if group is None else document.find(group)
                    section.remove(section.find(f"exclude[@domain='{domain}']"))
                    self.write(path, ET.tostring(document, encoding="unicode"))
                    self.assertTrue(check_backup_policy(self.root))

    def test_backup_include_or_partial_path_is_rejected(self):
        for replacement in ('<include domain="file" path="."/>', '<exclude domain="file" path="subdir"/>'):
            self.backup_fixture()
            path = "app/src/main/res/xml/backup_rules.xml"
            self.write(path, (self.root / path).read_text().replace('<exclude domain="file" path="."/>', replacement))
            self.assertTrue(check_backup_policy(self.root))

    def wrapper_fixture(self):
        repository = Path(__file__).resolve().parent.parent
        for path in ("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"):
            target = self.root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(repository / path, target)

    def test_official_wrapper_files_are_accepted(self):
        self.wrapper_fixture()
        self.assertEqual([], check_wrapper(self.root))

    def test_tampered_wrapper_jar_is_rejected(self):
        self.wrapper_fixture()
        with (self.root / "gradle/wrapper/gradle-wrapper.jar").open("ab") as stream:
            stream.write(b"tampered")
        self.assertTrue(any("JAR" in error for error in check_wrapper(self.root)))

    def test_windows_line_endings_in_unix_wrapper_are_rejected(self):
        self.wrapper_fixture()
        path = self.root / "gradlew"
        path.write_bytes(path.read_bytes().replace(b"\n", b"\r\n"))
        self.assertTrue(any("LF line endings" in error for error in check_wrapper(self.root)))

    def test_missing_distribution_digest_or_changed_origin_is_rejected(self):
        for original, replacement in (("distributionSha256Sum=", "ignoredSha256Sum="), ("services.gradle.org", "example.invalid")):
            self.wrapper_fixture()
            path = "gradle/wrapper/gradle-wrapper.properties"
            self.write(path, (self.root / path).read_text().replace(original, replacement))
            self.assertTrue(check_wrapper(self.root))


if __name__ == "__main__":
    unittest.main()
