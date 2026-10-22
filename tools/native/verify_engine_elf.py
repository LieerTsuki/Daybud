"""Check ELF alignment/JNI identity/probe isolation, not handset speed.

The pinned upstream baseline includes optional .inst kernels behind CPU checks.
Their presence alone is not an ISA requirement; compiler flags are checked by
test_engine_build.py. This script cannot prove those runtime guards on hardware.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


def inspect(path, llvm):
    suffix = ".exe" if sys.platform == "win32" else ""

    def run(name, *arguments):
        return subprocess.run([str(llvm / (name + suffix)), *arguments, str(path)],
                              check=True, capture_output=True, text=True, encoding="utf-8").stdout

    headers = run("llvm-readelf", "-lW")
    alignments = [int(line.split()[-1], 16) for line in headers.splitlines()
                  if line.strip().startswith("LOAD ")]
    if not alignments or any(value < 16384 for value in alignments):
        raise AssertionError(str(path) + ": LOAD alignment is below 16 KiB")
    disassembly = run("llvm-objdump", "-d")
    symbols = run("llvm-readelf", "--dyn-syms", "--wide")
    dynamic = run("llvm-readelf", "-dW")
    return {
        "path": str(path.resolve()),
        "bytes": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "loadAlignments": alignments,
        "dotProductInstructions": len(re.findall(r"\b(?:udot|sdot)\s", disassembly)),
        "i8mmInstructions": len(re.findall(r"\b(?:smmla|ummla|usmmla)\s", disassembly)),
        "inferenceJni": sorted(set(re.findall(r"Java_\S*NativeLlamaBindings_\S+", symbols))),
        "probeJni": "Java_com_example_lifemanager_data_diary_ai_local_NativeCpuFeatures_hasArmDotProduct" in symbols,
        "dependencies": re.findall(r"Shared library: \[([^]]+)\]", dynamic),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--llvm", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--dotprod", type=Path, required=True)
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    results = {name: inspect(getattr(args, name), args.llvm) for name in ("baseline", "dotprod", "probe")}
    assert results["dotprod"]["dotProductInstructions"] > 0, "Candidate has no dotprod kernel"
    assert results["probe"]["dotProductInstructions"] == 0, "Baseline probe requires dotprod"
    assert results["probe"]["i8mmInstructions"] == 0, "Baseline probe requires i8mm"
    assert len(results["baseline"]["inferenceJni"]) == 5
    assert results["baseline"]["inferenceJni"] == results["dotprod"]["inferenceJni"]
    assert not results["probe"]["inferenceJni"] and results["probe"]["probeJni"]
    assert not results["baseline"]["probeJni"] and not results["dotprod"]["probeJni"]
    assert set(results["probe"]["dependencies"]) <= {"libc.so", "libdl.so", "libm.so"}
    report = json.dumps({
        "status": "pass",
        "devicePerformanceVerified": False,
        "runtimeDispatchVerifiedOnArm": False,
        "note": "Pinned upstream engines contain optional assembly guarded by upstream CPU checks; instruction counts alone do not prove ISA requirements or dispatch correctness.",
        "libraries": results,
    }, indent=2)
    if args.output:
        args.output.write_text(report + "\n", encoding="utf-8")
    print(report)


if __name__ == "__main__":
    main()
