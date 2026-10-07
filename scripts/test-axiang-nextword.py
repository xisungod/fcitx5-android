#!/usr/bin/env python3
"""Fresh bounded UTF-8 host checks + actual ARM64 libime shared JNI execution.

Reuses Android bionic/QEMU and original APK dependencies read-only. ARM timings
describe emulator execution; no ART, device UI, handset accuracy or latency claim.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(command, path):
    with path.open("w") as log:
        subprocess.run([str(v) for v in command], check=True, stdout=log, stderr=subprocess.STDOUT)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root", type=Path, default=Path.cwd())
    p.add_argument("--work", type=Path, required=True)
    p.add_argument("--prebuilt", type=Path, required=True)
    p.add_argument("--ndk", type=Path, required=True)
    p.add_argument("--android-runtime", type=Path, required=True, help="Existing Android system directory containing linker64 and lib64/bionic")
    p.add_argument("--qemu", type=Path, required=True)
    p.add_argument("--source-commit", help="Final release mode: require this clean frozen source before and after actual checks")
    a = p.parse_args()
    a.work = a.work.resolve(); a.root = a.root.resolve(); a.work.mkdir(exist_ok=True)
    def source_state():
        head = subprocess.check_output(["git", "-C", str(a.root), "rev-parse", "HEAD"], text=True).strip()
        dirty = bool(subprocess.check_output(["git", "-C", str(a.root), "status", "--porcelain"]))
        if a.source_commit and (head != a.source_commit or dirty):
            raise ValueError("Native checks require the exact clean frozen source")
        return head, dirty
    head, dirty = source_state()
    if not (a.android_runtime / "bin/linker64").is_file() or not (a.android_runtime / "lib64/bionic/libc.so").is_file():
        raise ValueError("--android-runtime must be the actual runtime APEX root containing linker64/bionic")
    provenance = json.loads((a.work / "provenance.json").read_text())
    if digest(a.work / "libaxiangpredict.so") != provenance["native_sha256"]:
        raise ValueError("Actual bridge differs from its fresh build provenance")
    libs = a.prebuilt / "jniLibs/arm64-v8a"; models = a.prebuilt / "assets/usr/share/libime"
    expected = {libs / name: r for name, r in provenance["dependency_files"].items()}
    expected.update({models / name: r for name, r in provenance["model_files"].items()})
    for path, record in expected.items():
        if digest(path) != record["sha256"] or path.stat().st_size != record["bytes"]:
            raise ValueError("Actual dependency/model differs: " + path.name)
    harness = a.root / "scripts/check-axiang-nextword.cpp"; text = a.root / "app/src/main/cpp/axiangpredict-text.cpp"
    source_hashes = {str(path.relative_to(a.root)): digest(path) for path in (harness, text)}
    run(["g++", "-std=c++17", "-O2", "-DAXIANG_PREDICT_TEXT_ONLY", "-I", a.root / "app/src/main/cpp",
         harness, text, "-o", a.work / "host-text-check"], a.work / "host-compile.log")
    run([a.work / "host-text-check"], a.work / "host-check.log")
    host = json.loads((a.work / "host-check.log").read_text())
    tool = a.ndk / "toolchains/llvm/prebuilt/linux-x86_64"
    run([tool / "bin/clang++", "--target=aarch64-linux-android23", "--sysroot=" + str(tool / "sysroot"),
         "-std=c++20", "-O2", "-fPIE", "-pie", "-nostdlib++", "-I", a.root / "app/src/main/cpp",
         harness, text, "-L", libs, "-lc++_shared", "-ldl", "-o", a.work / "arm-jni-check"], a.work / "arm-compile.log")
    search = ":".join(str(path.resolve()) for path in (libs, a.android_runtime / "lib64/bionic"))
    run([a.qemu, "-E", "LD_LIBRARY_PATH=" + search, a.android_runtime / "bin/linker64",
         a.work / "arm-jni-check", a.work / "libaxiangpredict.so", models / "zh_CN.lm"], a.work / "arm-check.log")
    # Android's extracted linker may emit a fixed configuration warning before
    # the one harness JSON line. Preserve that raw log; reject zero/multiple JSONs.
    json_lines = [line for line in (a.work / "arm-check.log").read_text().splitlines()
                  if line.startswith("{")]
    if len(json_lines) != 1:
        raise ValueError("Actual Android harness did not produce exactly one result")
    arm = json.loads(json_lines[0])
    for path, record in expected.items():
        if digest(path) != record["sha256"]:
            raise ValueError("Native query modified a dependency/model")
    if source_state() != (head, dirty):
        raise ValueError("Source state changed during native checks")
    summary = {"schema": 1, "source_commit": head, "source_checkout_dirty": dirty,
               "native_sha256": provenance["native_sha256"], "host": host, "arm": arm,
               "harness_sources_sha256": source_hashes, "source_files_sha256": provenance["source_files_sha256"],
               "qemu_sha256": digest(a.qemu), "dependency_files": provenance["dependency_files"],
               "model_files": provenance["model_files"], "all_dependency_and_model_hashes_unchanged": True,
               "host_language_model_execution": False, "actual_android_shared_libime_execution": True,
               "art_verified": False, "phone_ui_or_installation_verified": False, "phone_latency_verified": False}
    (a.work / "test-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
