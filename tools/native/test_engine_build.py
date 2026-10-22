"""Exercise the real Android CMake configuration without running Gradle.

Run with --sdk PATH. Build directories and evidence remain in .tools.
This validates compiler requirements, not device performance.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import unittest
import uuid


ROOT = Path(__file__).resolve().parents[2]
PARSER = argparse.ArgumentParser()
PARSER.add_argument("--sdk", type=Path, default=ROOT / ".tools/android-sdk")
ARGS, TEST_ARGS = PARSER.parse_known_args()
SDK = ARGS.sdk.resolve()
SUFFIX = ".exe" if sys.platform == "win32" else ""
CMAKE = SDK / ("cmake/3.22.1/bin/cmake" + SUFFIX)
NINJA = SDK / ("cmake/3.22.1/bin/ninja" + SUFFIX)
NDK = SDK / "ndk/27.2.12479018"
EVIDENCE = ROOT / ".tools" / ("engine-build-contract-" + uuid.uuid4().hex)


def configure(variant, abi="arm64-v8a"):
    directory = EVIDENCE / (variant + "-" + abi)
    command = [str(CMAKE), "-S", str(ROOT / "app/src/main/cpp"), "-B", str(directory),
               "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
               "-DCMAKE_MAKE_PROGRAM=" + str(NINJA),
               "-DCMAKE_TOOLCHAIN_FILE=" + str(NDK / "build/cmake/android.toolchain.cmake"),
               "-DANDROID_ABI=" + abi, "-DANDROID_PLATFORM=android-26",
               "-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
               "-DDIARY_ENGINE_VARIANT=" + variant]
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "configure.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    return result, directory


class EngineBuildContractTest(unittest.TestCase):
    def test_dotprod_engine_requests_only_the_declared_optional_instruction(self):
        result, directory = configure("dotprod")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        commands = json.loads((directory / "compile_commands.json").read_text())
        kernels = [item["command"] for item in commands
                   if Path(item["file"]).name in ("quants.c", "quants.cpp", "ggml-cpu.c", "ggml-cpu.cpp")
                   and "ggml-cpu" in item["file"]]
        self.assertTrue(kernels, "No CPU kernel compilation was configured")
        self.assertTrue(any("-march=armv8-a+dotprod" in command for command in kernels),
                        "The dotprod candidate silently uses baseline kernels")
        self.assertFalse(any("+i8mm" in command or "+fp16" in command for command in kernels))

    def test_baseline_probe_and_engine_do_not_require_optional_arm_instructions(self):
        result, directory = configure("baseline")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        commands = json.loads((directory / "compile_commands.json").read_text())
        probe = [item for item in commands if Path(item["file"]).name == "cpu_features.cpp"]
        self.assertEqual(1, len(probe), "CPU detection must be a separate baseline native library")
        self.assertIn("-march=armv8-a", probe[0]["command"])
        self.assertFalse(any("+dotprod" in item["command"] or "+i8mm" in item["command"] for item in commands))

    def test_unknown_variant_is_rejected_instead_of_packaging_the_wrong_engine(self):
        result, _ = configure("typo")
        self.assertNotEqual(0, result.returncode)

    def test_dotprod_cannot_be_built_for_a_non_arm_abi(self):
        result, _ = configure("dotprod", "x86_64")
        self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    print("EVIDENCE_DIR=" + str(EVIDENCE), flush=True)
    unittest.main(argv=[sys.argv[0], *TEST_ARGS])
