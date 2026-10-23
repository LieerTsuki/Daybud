"""Current source guardrails. Static checks do not replace Room or device tests."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


PACKAGE = "com.example.lifemanager"
SOURCE = Path("app/src/main/java/com/example/lifemanager")
SCHEMAS = Path("app/schemas/com.example.lifemanager.data.local.LifeManagerDatabase")
GRADLE_SHA256 = "2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb"
WRAPPER_SHA256 = "55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c"


def read(root, relative):
    return (root / relative).read_text(encoding="utf-8-sig")


def check_dependencies(root):
    errors = []
    forbidden_prefixes = {
        "domain": ("android.", "androidx.", PACKAGE + ".data.", PACKAGE + ".ui.", PACKAGE + ".notification."),
        "data": (PACKAGE + ".ui.",),
        "ui": (PACKAGE + ".data.", "androidx.room.", "android.database.", "androidx.sqlite."),
    }
    for layer, prefixes in forbidden_prefixes.items():
        for path in sorted((root / SOURCE / layer).rglob("*.kt")):
            for line_number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
                match = re.match(r"\s*import\s+([\w.*]+)", line)
                if not match:
                    continue
                dependency = match[1]
                forbidden = dependency.startswith(prefixes)
                # This immutable resource catalog has no database/file-storage access.
                if layer == "ui" and dependency == PACKAGE + ".data.about.OfflineLicenses":
                    forbidden = False
                if forbidden:
                    errors.append(f"{path.relative_to(root)}:{line_number}: {layer} must not import {dependency}")
    return errors


def check_backup_policy(root):
    errors = []
    manifest = ET.fromstring(read(root, "app/src/main/AndroidManifest.xml"))
    application = manifest.find("application")
    android = "{http://schemas.android.com/apk/res/android}"
    required = {
        "allowBackup": "false",
        "fullBackupContent": "@xml/backup_rules",
        "dataExtractionRules": "@xml/data_extraction_rules",
    }
    for attribute, expected in required.items():
        if application is None or application.get(android + attribute) != expected:
            errors.append(f"Manifest must wire {attribute}={expected}")
    domains = {"root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref"}

    def check_group(group, label):
        excluded = {element.get("domain") for element in group.findall("exclude") if element.get("path") == "."}
        missing = domains - excluded
        if missing:
            errors.append(f"{label} must exclude each whole backup domain: {', '.join(sorted(missing))}")

    legacy = ET.fromstring(read(root, "app/src/main/res/xml/backup_rules.xml"))
    extraction = ET.fromstring(read(root, "app/src/main/res/xml/data_extraction_rules.xml"))
    for document, expected_tag in ((legacy, "full-backup-content"), (extraction, "data-extraction-rules")):
        if document.tag != expected_tag:
            errors.append(f"Backup XML must have {expected_tag} root")
        if document.findall(".//include"):
            errors.append(f"{expected_tag} must not include personal data")
    check_group(legacy, "Legacy backup rules")
    for name in ("cloud-backup", "device-transfer"):
        groups = extraction.findall(name)
        if len(groups) != 1:
            errors.append(f"Extraction rules must define exactly one {name} group")
        for group in groups:
            check_group(group, name)
    return errors


def check_database(root):
    errors = []
    database = read(root, SOURCE / "data/local/LifeManagerDatabase.kt")
    if not re.search(r"\bversion\s*=\s*8\b", database) or not re.search(r"\bexportSchema\s*=\s*true\b", database):
        errors.append("Room must remain schema version 8 with exported schemas")
    migrations = database
    for name in ("PlanMigration", "HabitForestMigration", "DiaryMigration"):
        migrations += read(root, SOURCE / f"data/local/migration/{name}.kt")
    edges = {(int(a), int(b)) for a, b in re.findall(r"Migration\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)", migrations)}
    for start in range(1, 8):
        if (start, start + 1) not in edges:
            errors.append(f"Missing explicit migration {start} -> {start + 1}")
    if not re.search(r"val\s+MIGRATIONS\s*:[^=]+?=\s*LEGACY_MIGRATIONS\s*\+\s*arrayOf\(\s*PlanMigration\(\),\s*HabitForestMigration\(\),\s*DiaryMigration\(\)\s*\)", database, re.S):
        # Also allow Kotlin's inferred type in fixtures/future equivalent declarations.
        if not re.search(r"val\s+MIGRATIONS\s*=\s*LEGACY_MIGRATIONS\s*\+\s*arrayOf\(\s*PlanMigration\(\),\s*HabitForestMigration\(\),\s*DiaryMigration\(\)\s*\)", database, re.S):
            errors.append("Database must register legacy and current migration arrays")
    factory = read(root, SOURCE / "data/local/LifeManagerDatabaseFactory.kt")
    if not re.search(r"\.addMigrations\s*\(\s*\*LifeManagerDatabase\.MIGRATIONS\s*\)", factory):
        errors.append("Production database factory must register all explicit migrations")
    for path in sorted((root / SOURCE).rglob("*.kt")):
        if "fallbackToDestructiveMigration" in path.read_text(encoding="utf-8-sig"):
            errors.append(f"Forbidden destructive migration fallback: {path.relative_to(root)}")
    # Version 1 predates exported JSON; the explicit 1->2 migration is checked above.
    for version in range(2, 9):
        schema = root / SCHEMAS / f"{version}.json"
        if not schema.is_file() or json.loads(schema.read_text(encoding="utf-8"))["database"]["version"] != version:
            errors.append(f"Missing or mismatched exported schema {version}")
    return errors


def check_retirement(root):
    errors = []
    manifest = ET.fromstring(read(root, "app/src/main/AndroidManifest.xml"))
    android_name = "{http://schemas.android.com/apk/res/android}name"
    for element in manifest.iter():
        name = element.attrib.get(android_name, "")
        if name == "android.permission.INTERNET":
            errors.append("Diary-only manifest must not request INTERNET")
        if "LocalInferenceService" in name or ":diary_inference" in element.attrib.values():
            errors.append("Diary-only manifest must not declare an inference service/process")
    build = read(root, "app/build.gradle.kts")
    if re.search(r"externalNativeBuild|CMakeLists|llama\.cpp|diary_llama|diary_cpu_features", build):
        errors.append("Diary-only build must not enable the retired native AI runtime")
    retired_names = {"DiaryAiEngine.kt", "ChatCompletionsClient.kt", "LocalModelStore.kt", "DiaryAiViewModel.kt", "LocalInferenceService.kt"}
    for path in sorted((root / SOURCE).rglob("*.kt")):
        if path.name in retired_names or "/ui/diary/ai/" in path.as_posix() or "/data/diary/ai/local/" in path.as_posix():
            errors.append(f"Retired active AI source reintroduced: {path.relative_to(root)}")
    worker = read(root, SOURCE / "data/diary/ai/DiaryAiWorker.kt")
    if not re.search(r"override\s+suspend\s+fun\s+doWork\(\)\s*:\s*Result\s*=\s*Result\.success\(\)\s*(?:\n|$)", worker):
        errors.append("Historical DiaryAiWorker must remain a no-op success")
    if "cancelUniqueWork" not in worker:
        errors.append("Historical DiaryAiWorker must cancel its old unique work")
    cleanup = root / SOURCE / "data/diary/ai/LegacyCloudRetirement.kt"
    startup = read(root, SOURCE / "LifeManagerApp.kt")
    if not cleanup.is_file() or "DiaryAiWorker.retire(this)" not in startup or "retiredCloud().retire()" not in startup:
        errors.append("Startup must retain legacy work and credential retirement")
    return errors


def check_wrapper(root):
    errors = []
    for relative in ("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"):
        if not (root / relative).is_file():
            errors.append(f"Missing Gradle Wrapper file: {relative}")
    if errors:
        return errors
    if b"\r" in (root / "gradlew").read_bytes():
        errors.append("Unix Gradle Wrapper must use LF line endings")
    properties = dict(line.strip().split("=", 1) for line in read(root, "gradle/wrapper/gradle-wrapper.properties").splitlines() if "=" in line and not line.startswith("#"))
    url = properties.get("distributionUrl", "").replace("\\:", ":")
    if url != "https://services.gradle.org/distributions/gradle-9.4.1-bin.zip":
        errors.append("Wrapper must pin the official Gradle 9.4.1 binary distribution")
    if properties.get("distributionSha256Sum") != GRADLE_SHA256:
        errors.append("Wrapper must verify the official Gradle distribution SHA256")
    actual = hashlib.sha256((root / "gradle/wrapper/gradle-wrapper.jar").read_bytes()).hexdigest()
    if actual != WRAPPER_SHA256:
        errors.append("Wrapper JAR differs from the official Gradle 9.4.1 SHA256")
    return errors


def check_project(root):
    errors = []
    for check in (check_dependencies, check_backup_policy, check_database, check_retirement, check_wrapper):
        try:
            errors.extend(check(root))
        except (OSError, ValueError, KeyError, ET.ParseError) as error:
            errors.append(f"{check.__name__}: {error}")
    return errors


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    violations = check_project(args.root.resolve())
    if violations:
        print("\n".join(violations), file=sys.stderr)
        sys.exit(1)
    print("Current project structure passed: dependencies, system backup exclusions, Room8 migrations, AI retirement and verified Gradle Wrapper (static checks only).")
