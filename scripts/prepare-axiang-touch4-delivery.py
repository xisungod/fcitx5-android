#!/usr/bin/env python3
"""Prepare and verify the source-only AXiang 1.2-touch.4 boundary-scoring update.

The complete assets/native tree comes from the hash-pinned public touch.3 APK.
Only current Kotlin/resources are compiled. Verification checks the actual DEX
BuildConfig marker, durable signer, package identity, complete JUnit XML results,
and a clean source commit before assembling a small public delivery whitelist.
This script never fetches files, uploads anything, or handles signing keys/logs.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

BASE_COMMIT = "dc04e721035b8f1cab7469a3476c32064cbc2d5e"
BASE_APK_SHA256 = "ca16dddd6aa25cbb36ad455d001fa8309ea6efbb7ef6c342df569aba8228577a"
BASE_APK_BYTES = 268597703
PACKAGE = "org.fcitx.fcitx5.android.axiang.touch2"
LABEL = "AXiang 触点测试2"
VERSION_NAME = "1.2-touch.4"
VERSION_CODE = 4
APK_NAME = "AXiang-1.2-touch.4-arm64.apk"
SIGNER = "ffede124b18d54af5d4cfa9f3a32504fa2ccc898563bc4816bacbe3e46ec0494"
EXPECTED_COUNTS = {"assets": 350, "lib": 28}
PUBLIC_FILES = {
    APK_NAME, "SHA256SUMS.txt", "build-verification.json", "prebuilt-manifest.json",
    "full-junit-reports.zip", "unit-test-summary.json", "typing-test-source.patch",
    "source-patch-manifest.json", "source-snapshot.json", "GUIDE.zh-CN.md",
}
PUBLIC_PREFIXES = ("app/src/main/java/", "app/src/main/res/", "app/src/test/java/")
PUBLIC_EXACT = {
    "scripts/prepare-axiang-touch4-delivery.py", "docs/axiang/touch4.md",
    "scripts/typing-test-replay/replay.py", "scripts/typing-test-replay/ReplayMain.kt",
    "scripts/typing-test-replay/candidate_replay.py", "scripts/typing-test-replay/test_replay.py",
    "scripts/typing-test-replay/README.md", "scripts/typing-test-replay/synthetic-report.json",
}
PUBLIC_JSON = {"scripts/typing-test-replay/synthetic-report.json"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def git(root, *arguments):
    return subprocess.check_output(["git", *arguments], cwd=root)


def pinned_baseline(path):
    require(path.is_file() and not path.is_symlink(), "Pinned touch.3 APK must be a regular file")
    require(path.stat().st_size == BASE_APK_BYTES and sha256(path) == BASE_APK_SHA256,
            "Pinned public touch.3 APK identity differs")
    return path


def archive_inputs(archive):
    names = [info.filename for info in archive.infolist() if not info.is_dir()]
    require(len(names) == len(set(names)), "APK contains duplicate ZIP paths")
    selected = sorted(name for name in names if name.startswith(("assets/", "lib/")))
    counts = {prefix: sum(name.startswith(prefix + "/") for name in selected) for prefix in EXPECTED_COUNTS}
    require(counts == EXPECTED_COUNTS, f"Packaged native/assets counts differ: {counts}")
    for name in selected:
        path = PurePosixPath(name)
        require(not path.is_absolute() and ".." not in path.parts and "\\" not in name,
                f"Unsafe APK member: {name}")
        if name.startswith("lib/"):
            require(name.startswith("lib/arm64-v8a/") and name.endswith(".so"),
                    f"Unexpected native member: {name}")
    return selected, counts


def prepare(args):
    root, work = args.root.resolve(), args.work.resolve()
    require((root / "app/build.gradle.kts").is_file(), "--root must be the Android checkout")
    source = pinned_baseline(args.source_apk.resolve())
    work.mkdir(parents=True, exist_ok=True)
    prebuilt = work / "prebuilt"
    if prebuilt.exists():
        shutil.rmtree(prebuilt)
    files = []
    with zipfile.ZipFile(source) as archive:
        names, counts = archive_inputs(archive)
        speech = json.loads(archive.read("assets/asr/SOURCE.json"))
        for name in names:
            data = archive.read(name)
            if name.startswith("lib/"):
                require(data[:6] == b"\x7fELF\x02\x01" and int.from_bytes(data[18:20], "little") == 183,
                        f"Expected AArch64 ELF64: {name}")
                destination = prebuilt / "jniLibs" / name.removeprefix("lib/")
            else:
                destination = prebuilt / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
            files.append({"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    jar = root / "app/libs/sherpa-onnx-1.13.8.jar"
    require(jar.is_file() and sha256(jar) == speech["compile_jar"]["sha256"],
            "Existing compile JAR must match the pinned touch.3 speech runtime")
    manifest = {"schema": 3, "base_commit": BASE_COMMIT, "source_apk_path": str(source),
                "source_apk_sha256": BASE_APK_SHA256, "source_apk_bytes": BASE_APK_BYTES,
                "counts": counts, "files": files, "added_files": [],
                "compile_jar": {"path": str(jar.relative_to(root)), "sha256": sha256(jar)}}
    write_json(work / "prebuilt-manifest.json", manifest)
    escaped = json.dumps(str(prebuilt), ensure_ascii=False).replace("$", "\\$")
    init = f"""// Complete hash-pinned public touch.3 asset/native tree; compile only current Kotlin/resources.
def verifiedInput = new File({escaped})
gradle.beforeProject {{ p ->
    ['com.android.application', 'com.android.library'].each {{ androidPlugin ->
        p.plugins.withId(androidPlugin) {{
            p.extensions.getByName('androidComponents').finalizeDsl {{ dsl ->
                dsl.externalNativeBuild.cmake.path = null
                dsl.buildFeatures.prefab = false
                if (androidPlugin == 'com.android.library') dsl.buildFeatures.prefabPublishing = false
                if (p.path == ':app') {{
                    dsl.buildTypes.getByName('debug').resValue('string', 'app_name', '{LABEL}')
                    dsl.sourceSets.getByName('main').assets.setSrcDirs([new File(verifiedInput, 'assets')])
                    dsl.sourceSets.getByName('main').jniLibs.setSrcDirs([new File(verifiedInput, 'jniLibs')])
                    dsl.packaging.jniLibs.keepDebugSymbols.add('**/*.so')
                }}
            }}
        }}
    }}
}}
"""
    path = work / "typing-test.init.gradle"
    path.write_text(init, encoding="utf-8")
    print(json.dumps({"counts": counts, "source_apk_sha256": BASE_APK_SHA256,
                      "added_assets_or_natives": [], "init": str(path)}, ensure_ascii=False))


def dex_build_marker(data):
    """Read BuildConfig.BUILD_GIT_HASH from real DEX static values, not a string search."""
    require(data[:4] == b"dex\n" and data[7:8] == b"\0" and len(data) >= 112,
            "Unsupported DEX header")
    require(struct.unpack_from("<I", data, 32)[0] == len(data) and
            struct.unpack_from("<I", data, 36)[0] == 112 and
            struct.unpack_from("<I", data, 40)[0] == 0x12345678, "Invalid DEX layout")

    def u32(offset):
        require(0 <= offset <= len(data) - 4, "DEX offset is outside the file")
        return struct.unpack_from("<I", data, offset)[0]

    def leb(offset):
        value = 0
        for index in range(5):
            require(offset < len(data), "Truncated DEX ULEB128")
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << (index * 7)
            if byte < 128:
                return value, offset
        raise ValueError("Oversized DEX ULEB128")

    string_count, string_offset = u32(56), u32(60)
    type_count, type_offset = u32(64), u32(68)
    field_count, field_offset = u32(80), u32(84)
    class_count, class_offset = u32(96), u32(100)

    def string(index):
        require(0 <= index < string_count, "Invalid DEX string index")
        offset = u32(string_offset + 4 * index)
        _, start = leb(offset)
        end = data.find(b"\0", start)
        require(end >= start, "Truncated DEX string")
        # Descriptor/field name/source SHA are ASCII. No need to reinterpret unrelated MUTF-8.
        return data[start:end]

    def typename(index):
        require(0 <= index < type_count, "Invalid DEX type index")
        return string(u32(type_offset + 4 * index))

    def encoded_value(offset, depth=0):
        require(depth < 8 and offset < len(data), "Invalid DEX encoded value")
        header = data[offset]
        offset += 1
        kind, argument = header & 31, header >> 5
        if kind == 0x1C:  # array
            require(argument == 0, "Invalid DEX encoded array")
            size, offset = leb(offset)
            for _ in range(size):
                _, offset = encoded_value(offset, depth + 1)
            return None, offset
        if kind in (0x1E, 0x1F):  # null, boolean
            require(argument <= (1 if kind == 0x1F else 0), "Invalid DEX null/boolean")
            return None, offset
        require(kind != 0x1D and offset + argument + 1 <= len(data),
                "Unsupported/truncated DEX annotation or value")
        value = int.from_bytes(data[offset:offset + argument + 1], "little")
        return (string(value) if kind == 0x17 else None), offset + argument + 1

    markers = []
    for index in range(class_count):
        offset = class_offset + index * 32
        if typename(u32(offset)) != b"Lorg/fcitx/fcitx5/android/BuildConfig;":
            continue
        class_data, values_offset = u32(offset + 24), u32(offset + 28)
        require(class_data and values_offset, "Compiled BuildConfig has no static values")
        static_count, cursor = leb(class_data)
        for _ in range(3):
            _, cursor = leb(cursor)
        fields, field_index = [], 0
        for _ in range(static_count):
            delta, cursor = leb(cursor)
            _, cursor = leb(cursor)
            field_index += delta
            require(field_index < field_count, "Invalid DEX static field index")
            fields.append(string(u32(field_offset + field_index * 8 + 4)))
        value_count, cursor = leb(values_offset)
        require(value_count <= len(fields), "Too many DEX static values")
        for field in fields[:value_count]:
            value, cursor = encoded_value(cursor)
            if field == b"BUILD_GIT_HASH":
                require(value is not None and re.fullmatch(rb"[0-9a-f]{40}", value),
                        "Compiled BuildConfig source marker is not a full source SHA")
                markers.append(value.decode("ascii"))
    require(len(markers) <= 1, "Duplicate BuildConfig source markers in one DEX")
    return markers[0] if markers else None


def compiled_source(apk):
    markers = []
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if re.fullmatch(r"classes(?:[1-9][0-9]*)?\.dex", name):
                marker = dex_build_marker(archive.read(name))
                if marker is not None:
                    markers.append({"dex": name, "field": "BuildConfig.BUILD_GIT_HASH", "value": marker})
    require(len(markers) == 1, "Actual APK must contain exactly one compiled app BuildConfig source marker")
    return markers[0]


def defaults(root):
    source = (root / "app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt").read_text()
    expected = {"pinyin_down_order": True, "pinyin_touch_alternatives": True,
                "touch_diagnostic_logging": False, "pinyin_touch_personalization": False,
                "pinyin_touch_correction": False, "touch_boundary_settling": False}
    for key, value in expected.items():
        matches = re.findall(r'"' + re.escape(key) + r'"\s*,\s*(true|false)\b', source)
        require(matches == [str(value).lower()], f"Expected default is not uniquely present in source: {key}")
    return {"pinyin_down_order": True, "pinyin_touch_alternatives": True, "diagnostic_logging": False,
            "pinyin_touch_personalization": False, "pinyin_touch_correction": False,
            "touch_boundary_settling": False}


def unit_test_summary(root, expected_tests):
    reports = sorted((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    require(reports, "No complete Gradle unit-test XML results found")
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for path in reports:
        suite = ET.parse(path).getroot()
        require(suite.tag == "testsuite", f"Unexpected JUnit XML root: {path}")
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
    require(totals["tests"] >= 589, "Complete suite must include the existing touch.3 regression tests")
    if expected_tests is not None:
        require(totals["tests"] == expected_tests,
                f"Expected {expected_tests} tests, actual XML reports {totals['tests']}")
    require(totals["failures"] == totals["errors"] == totals["skipped"] == 0,
            f"JUnit results are not a complete pass: {totals}")
    return {"classes": len(reports), **totals,
            "result_files": [{"path": str(path.relative_to(root)), "sha256": sha256(path)} for path in reports]}, reports


def source_patch(root, commit):
    require(git(root, "merge-base", "--is-ancestor", BASE_COMMIT, commit) == b"",
            "Frozen source does not descend from public touch.3")
    paths = [p.decode("utf-8") for p in git(root, "diff", "--name-only", "-z", BASE_COMMIT, commit).split(b"\0") if p]
    require(paths, "No touch.4 source changes found against public touch.3")
    records = []
    for name in paths:
        path = PurePosixPath(name)
        require((name.startswith(PUBLIC_PREFIXES) or name in PUBLIC_EXACT) and
                not path.is_absolute() and ".." not in path.parts and "\\" not in name and
                (path.suffix in {".kt", ".xml", ".py", ".md"} or name in PUBLIC_JSON),
                f"Source-only touch.4 update contains an unreviewed path: {name}")
        blob = git(root, "show", commit + ":" + name)
        blob.decode("utf-8")
        require(b"\0" not in blob, f"Binary data in a public source path: {name}")
        records.append({"path": name, "bytes": len(blob), "sha256": hashlib.sha256(blob).hexdigest()})
    patch = git(root, "diff", "--full-index", BASE_COMMIT, commit, "--", *paths)
    require(not re.search(rb"(?m)^GIT binary patch$", patch),
            "Public source patch contains binary payloads")
    return patch, {"base_commit": BASE_COMMIT, "source_commit": commit,
                   "patch": "typing-test-source.patch", "bytes": len(patch),
                   "sha256": hashlib.sha256(patch).hexdigest(), "files": paths, "source_files": records,
                   "private_logs_prebuilts_and_generated_binaries_included": False}


def verify(args):
    root, work, output = args.root.resolve(), args.work.resolve(), args.output.resolve()
    require(re.fullmatch(r"[0-9a-f]{40}", args.source_commit), "--source-commit must be the frozen full SHA")
    head = git(root, "rev-parse", "HEAD").decode().strip()
    require(head == args.source_commit and git(root, "status", "--porcelain") == b"",
            "Source checkout must be clean and at the supplied frozen source commit")
    manifest = json.loads((work / "prebuilt-manifest.json").read_text())
    require(manifest["schema"] == 3 and manifest["base_commit"] == BASE_COMMIT and
            manifest["source_apk_sha256"] == BASE_APK_SHA256 and
            manifest["source_apk_bytes"] == BASE_APK_BYTES and manifest["counts"] == EXPECTED_COUNTS and
            manifest["added_files"] == [], "Prepared inputs differ from pinned public touch.3")
    baseline = pinned_baseline(Path(manifest["source_apk_path"]))
    expected_files = {entry["path"]: entry for entry in manifest["files"]}
    require(len(expected_files) == len(manifest["files"]) == sum(EXPECTED_COUNTS.values()),
            "Prepared manifest contains duplicate, missing or unexpected entries")
    apks = sorted((root / "app/build/outputs/apk/debug").glob("*.apk"))
    require(len(apks) == 1, f"Expected one current ARM64 debug APK, found {len(apks)}")
    apk = apks[0]
    sdk = args.sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    require(sdk, "Android SDK path is required for actual APK verification")
    tools = Path(sdk).resolve() / "build-tools/36.1.0"
    for tool in ("aapt", "apksigner", "zipalign"):
        require((tools / tool).is_file(), f"Missing Android verification tool: {tools / tool}")
    subprocess.run([sys.executable, str(root / "scripts/verify-xuancai-rime-package.py"), str(apk),
                    "--bundled-rime", "--offline-dictation", "--aapt", str(tools / "aapt"),
                    "--apksigner", str(tools / "apksigner"), "--expected-main-package", PACKAGE,
                    "--expected-app-label", LABEL, "--expected-version-name", VERSION_NAME,
                    "--expected-version-code", str(VERSION_CODE)], check=True)
    signing = subprocess.check_output([str(tools / "apksigner"), "verify", "--print-certs", str(apk)], text=True)
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$",
                              signing, re.MULTILINE)
    require([value.lower() for value in certificates] == [SIGNER], "Actual APK durable touch.3 signer differs")
    subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True)
    permissions = subprocess.check_output([str(tools / "aapt"), "dump", "permissions", str(apk)], text=True)
    require("android.permission.INTERNET" not in permissions, "Typing test update unexpectedly has INTERNET permission")
    with zipfile.ZipFile(baseline) as before, zipfile.ZipFile(apk) as after:
        before_names, _ = archive_inputs(before)
        after_names, counts = archive_inputs(after)
        require(set(before_names) == set(after_names) == set(expected_files),
                "Packaged assets/native member set changed from public touch.3")
        for name in before_names:
            data, expected = before.read(name), expected_files[name]
            require(after.read(name) == data and len(data) == expected["bytes"] and
                    hashlib.sha256(data).hexdigest() == expected["sha256"],
                    f"Packaged bytes or preparation provenance differ from public touch.3: {name}")
    marker = compiled_source(apk)
    require(marker["value"] == head, "Actual DEX source marker differs from clean frozen checkout")
    switches = defaults(root)
    tests, reports = unit_test_summary(root, args.expected_tests)
    patch, patch_manifest = source_patch(root, head)
    snapshot = {"branch": git(root, "branch", "--show-current").decode().strip(), "commit": head,
                "tree": git(root, "rev-parse", "HEAD^{tree}").decode().strip(),
                "compiled_source_is_fixed_commit": True, "compiled_marker": marker,
                "url": "https://github.com/xisungod/fcitx5-android/tree/" + head,
                "private_logs_included": False}
    verification = {
        "schema": 3, "package": PACKAGE, "label": LABEL, "version_name": VERSION_NAME,
        "version_code": VERSION_CODE, "abi": "arm64-v8a", "base_commit": BASE_COMMIT,
        "compiled_source_commit": head, "compiled_source_marker": marker,
        "source_checkout_head": head, "source_checkout_dirty": False,
        "source_apk_sha256": BASE_APK_SHA256, "apk_sha256": sha256(apk), "apk_bytes": apk.stat().st_size,
        "asset_count": counts["assets"], "native_count": counts["lib"],
        "all_350_assets_and_28_native_identical_to_touch_3": True, "added_assets_or_natives": [],
        "network_permission": False, "minirbt_included": False, "new_neural_model_included": False,
        "signature_verified": True, "signature_certificate_sha256": SIGNER, "zip_alignment_verified": True,
        "signing_note": "Verified same durable touch.3 test signer; same package and higher code permit an update.",
        "default_experimental_switches": switches, "automatic_personalization_training_added": False,
        "independent_rime_worker_thread_added": False,
        "typing_test_latency_measurement": "enqueue_to_key_action_completion_ns",
        "unit_test_classes": tests["classes"], "unit_tests": tests["tests"],
        "unit_test_failures": tests["failures"], "unit_test_errors": tests["errors"],
        "unit_test_skipped": tests["skipped"], "device_installation_or_launch_verified": False,
        "release_kind": "boundary_scoring_prerelease", "publication_performed": False,
    }
    require(not output.exists() or not any(output.iterdir()), "Use an empty delivery directory to exclude stale/private files")
    output.parent.mkdir(parents=True, exist_ok=True)
    # Validate everything before creating the public directory. A failed copy is
    # confined to a sibling temporary directory and never leaves a valid-looking delivery.
    with tempfile.TemporaryDirectory(prefix="typing-test-delivery-", dir=output.parent) as temporary:
        staging = Path(temporary)
        shutil.copyfile(apk, staging / APK_NAME)
        shutil.copyfile(work / "prebuilt-manifest.json", staging / "prebuilt-manifest.json")
        shutil.copyfile(root / "docs/axiang/touch4.md", staging / "GUIDE.zh-CN.md")
        write_json(staging / "build-verification.json", verification)
        write_json(staging / "unit-test-summary.json", tests)
        write_json(staging / "source-patch-manifest.json", patch_manifest)
        write_json(staging / "source-snapshot.json", snapshot)
        (staging / "typing-test-source.patch").write_bytes(patch)
        with zipfile.ZipFile(staging / "full-junit-reports.zip", "w", zipfile.ZIP_DEFLATED) as archive:
            for path in reports:
                archive.write(path, path.name)
        members = sorted(staging.iterdir())
        (staging / "SHA256SUMS.txt").write_text("".join(f"{sha256(path)}  {path.name}\n" for path in members), encoding="utf-8")
        require({path.name for path in staging.iterdir()} == PUBLIC_FILES, "Unexpected public delivery file set")
        require(head == git(root, "rev-parse", "HEAD").decode().strip() and
                git(root, "status", "--porcelain") == b"", "Source changed during verification")
        if output.exists():
            output.rmdir()
        shutil.copytree(staging, output)
    print(json.dumps(verification, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    preparation = commands.add_parser("prepare", help="Extract the exact public touch.3 asset/native tree and write a Gradle init script")
    preparation.add_argument("--root", type=Path, default=Path.cwd())
    preparation.add_argument("--work", type=Path, required=True)
    preparation.add_argument("--source-apk", type=Path, required=True)
    preparation.set_defaults(handler=prepare)
    verification = commands.add_parser("verify", help="Verify frozen-source DEX, actual APK, signature, all JUnit reports and create a public whitelist")
    verification.add_argument("--root", type=Path, default=Path.cwd())
    verification.add_argument("--work", type=Path, required=True)
    verification.add_argument("--output", type=Path, required=True)
    verification.add_argument("--sdk", type=Path)
    verification.add_argument("--source-commit", required=True)
    verification.add_argument("--expected-tests", type=int, help="Optional exact count after the final complete suite finishes")
    verification.set_defaults(handler=verify)
    args = parser.parse_args()
    try:
        args.handler(args)
    except (ValueError, KeyError, OSError, UnicodeError, zipfile.BadZipFile, ET.ParseError,
            struct.error, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Touch.4 delivery verification failed: {error}\n")


if __name__ == "__main__":
    main()
