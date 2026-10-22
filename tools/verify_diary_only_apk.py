"""Verify the installed-package boundary for the diary-only release, not source spelling."""
import argparse
from pathlib import Path
import subprocess
import sys
import zipfile


def verify(apk, aapt):
    permissions = subprocess.check_output([str(aapt), "dump", "permissions", str(apk)], text=True, encoding="utf-8")
    manifest = subprocess.check_output([str(aapt), "dump", "xmltree", str(apk), "AndroidManifest.xml"], text=True, encoding="utf-8")
    violations = []
    if "android.permission.INTERNET" in permissions:
        violations.append("The APK still requests Internet access")
    if "LocalInferenceService" in manifest or ":diary_inference" in manifest:
        violations.append("The APK still declares an AI inference process/service")
    with zipfile.ZipFile(apk) as archive:
        if any("libdiary_llama" in n or "libdiary_cpu_features" in n for n in archive.namelist()):
            violations.append("The APK still packages AI inference native libraries")
        if "assets/offline-ai-licenses.txt" in archive.namelist():
            violations.append("The APK still packages removed AI model/runtime assets")
        retired = [b"Lcom/example/lifemanager/data/diary/ai/DiaryAiEngine;",
                   b"Lcom/example/lifemanager/data/diary/ai/ChatCompletionsClient;",
                   b"Lcom/example/lifemanager/data/diary/ai/local/LocalModelStore;",
                   b"Lcom/example/lifemanager/ui/diary/ai/DiaryAiViewModel;"]
        for name in archive.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                data = archive.read(name)
                if any(symbol in data for symbol in retired):
                    violations.append("The APK still contains active AI code in " + name)
    if violations:
        raise AssertionError("\n".join(violations))
    return "Diary-only APK boundary verified: no Internet permission or detected legacy AI service, library, asset or active class"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt", type=Path, required=True)
    args = parser.parse_args()
    try:
        print(verify(args.apk, args.aapt))
    except AssertionError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
