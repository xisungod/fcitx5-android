#!/usr/bin/env python3
"""Build-time preparation and verification for the isolated AXiang diagnostic APK.

The pinned public 1.2 APK supplies one byte-identical native/assets set. Only the
current checkout's Kotlin/resources are compiled. No device input logs, model
downloads at runtime, release signing secrets, or publication are handled here.
"""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


BASE_COMMIT = "2a038c9bb5360d6760666c3df564cab4c66d1564"
BASE_APK = {
    "url": "https://github.com/xisungod/fcitx5-android/releases/download/axiang-v1.2/axiang-input-1.2-arm64.apk",
    "filename": "axiang-input-1.2-arm64.apk",
    "sha256": "9817126695d21b598980fdced927c0bb29cb9b15de59d6cc734aed36718a3e70",
}
PACKAGE = "org.fcitx.fcitx5.android.axiang.diagnostics"
LABEL = "AXiang 诊断"
VERSION_NAME = "1.2-diagnostic.1"
VERSION_CODE = 1
RELEASE_TAG = "axiang-diagnostics-1.2-diagnostic.1"
APK_NAME = "AXiang-1.2-diagnostic.1-arm64.apk"
EXPECTED_COUNTS = {"assets": 349, "lib": 27}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def verified_file(path, spec):
    require(path.is_file(), f"Missing pinned input: {path}")
    require(sha256(path) == spec["sha256"], f"Pinned input SHA-256 mismatch: {path}")
    return path


def fetch(spec, cache):
    """Never accept a partial or unchecked cached download."""
    cache.mkdir(parents=True, exist_ok=True)
    destination = cache / spec["filename"]
    if destination.exists():
        return verified_file(destination, spec)
    partial = destination.with_name(destination.name + ".partial")
    for attempt in range(3):
        try:
            request = urllib.request.Request(spec["url"], headers={"User-Agent": "AXiang-diagnostic-build/1"})
            with urllib.request.urlopen(request, timeout=120) as response, partial.open("wb") as output:
                shutil.copyfileobj(response, output)
            verified_file(partial, spec)
            partial.replace(destination)
            return destination
        except (OSError, ValueError):
            if attempt == 2:
                raise
            time.sleep(2 * (attempt + 1))
        finally:
            partial.unlink(missing_ok=True)
    raise AssertionError("unreachable")


def archive_inputs(archive):
    names = [info.filename for info in archive.infolist() if not info.is_dir()]
    require(len(names) == len(set(names)), "APK contains duplicate ZIP paths")
    selected = sorted(name for name in names if name.startswith(("assets/", "lib/")))
    counts = {prefix: sum(name.startswith(prefix + "/") for name in selected) for prefix in EXPECTED_COUNTS}
    require(counts == EXPECTED_COUNTS, f"Pinned native/assets counts differ: {counts}")
    for name in selected:
        path = PurePosixPath(name)
        require(not path.is_absolute() and ".." not in path.parts and "\\" not in name,
                f"Unsafe APK member: {name}")
        if name.startswith("lib/"):
            require(name.startswith("lib/arm64-v8a/") and name.endswith(".so"),
                    f"Unexpected native member: {name}")
    return selected, counts


def load_runtime_spec(root):
    path = root / "scripts/prepare-axiang-asr.py"
    spec = importlib.util.spec_from_file_location("axiang_pinned_asr", path)
    require(spec is not None and spec.loader is not None, "Cannot read pinned ASR preparation module")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    # Importing the module does not execute main or download ASR/model archives.
    return dict(module.DOWNLOADS["runtime"]), module.VERSION


def prepare(args):
    root, work = args.root.resolve(), args.work.resolve()
    require((root / "app/build.gradle.kts").is_file(), "--root must be the Android checkout")
    work.mkdir(parents=True, exist_ok=True)
    cache = work / "downloads"
    source = verified_file(args.source_apk.resolve(), BASE_APK) if args.source_apk else fetch(BASE_APK, cache)
    prebuilt = work / "prebuilt"
    # Remove stale extracted members; the descriptor and complete asset tree must
    # always come from this one verified archive, never a merge of two builds.
    if prebuilt.exists():
        shutil.rmtree(prebuilt)
    files = []
    with zipfile.ZipFile(source) as archive:
        names, counts = archive_inputs(archive)
        speech_source = json.loads(archive.read("assets/asr/SOURCE.json"))
        expected_jar = speech_source["compile_jar"]["sha256"]
        require(re.fullmatch(r"[0-9a-f]{64}", expected_jar) is not None,
                "Stable speech provenance has no compile JAR checksum")
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
            files.append({"path": name, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)})

    runtime_spec, runtime_version = load_runtime_spec(root)
    require(runtime_version == "1.13.8", "This delivery expects the pinned stable speech runtime")
    require(speech_source["downloads"]["runtime"] == runtime_spec,
            "Stable APK and source checkout speech runtime pins disagree")
    jar = root / f"app/libs/sherpa-onnx-{runtime_version}.jar"
    if not (jar.is_file() and sha256(jar) == expected_jar):
        runtime = verified_file(args.runtime_aar.resolve(), runtime_spec) if args.runtime_aar else fetch(runtime_spec, cache)
        with zipfile.ZipFile(runtime) as archive:
            classes = archive.read("classes.jar")
        require(hashlib.sha256(classes).hexdigest() == expected_jar,
                "Official runtime classes.jar differs from stable speech provenance")
        jar.parent.mkdir(parents=True, exist_ok=True)
        jar.write_bytes(classes)

    manifest = {
        "schema": 1, "source_apk": BASE_APK, "source_apk_sha256": BASE_APK["sha256"],
        "base_commit": BASE_COMMIT, "counts": counts, "files": files,
        "compile_jar": {"path": str(jar.relative_to(root)), "sha256": sha256(jar)},
        "runtime_download": runtime_spec,
    }
    write_json(work / "prebuilt-manifest.json", manifest)
    # Serialize the path as a Groovy-compatible double-quoted string, escaping
    # interpolation too. There is no shell execution or user-supplied code here.
    groovy_path = json.dumps(str(prebuilt), ensure_ascii=False).replace("$", "\\$")
    init = f"""// Pinned AXiang 1.2 assets/native libraries; compile current diagnostic Kotlin/resources.
def verifiedInput = new File({groovy_path})
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
    (work / "diagnostics.init.gradle").write_text(init, encoding="utf-8")
    print(json.dumps({"counts": counts, "source_apk_sha256": BASE_APK["sha256"],
                      "compile_jar_sha256": sha256(jar), "init": str(work / "diagnostics.init.gradle")}, ensure_ascii=False))


def unit_test_summary(root, expected_tests):
    results = root / "app/build/test-results/testDebugUnitTest"
    xml_files = sorted(results.glob("TEST-*.xml"))
    require(xml_files, "No actual Gradle unit-test XML results found")
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for path in xml_files:
        suite = ET.parse(path).getroot()
        require(suite.tag == "testsuite", f"Unexpected unit-test XML root: {path}")
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
    require(totals["tests"] == expected_tests, f"Expected {expected_tests} tests, actual XML reports {totals['tests']}")
    require(totals["failures"] == totals["errors"] == totals["skipped"] == 0,
            f"Unit-test results are not a complete pass: {totals}")
    return {"classes": len(xml_files), **totals,
            "result_files": [{"path": str(path.relative_to(root)), "sha256": sha256(path)} for path in xml_files]}


def verify(args):
    root, work, output = args.root.resolve(), args.work.resolve(), args.output.resolve()
    manifest = json.loads((work / "prebuilt-manifest.json").read_text(encoding="utf-8"))
    require(manifest["source_apk"] == BASE_APK and manifest["counts"] == EXPECTED_COUNTS,
            "Prepared inputs do not match this pinned delivery")
    apks = sorted((root / "app/build/outputs/apk/debug").glob("*.apk"))
    require(len(apks) == 1, f"Expected one arm64 debug APK, found {len(apks)}")
    output.mkdir(parents=True, exist_ok=True)
    apk = output / APK_NAME
    shutil.copyfile(apks[0], apk)

    sdk = args.sdk.resolve() if args.sdk else Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT", "")).resolve()
    tools = sdk / "build-tools/36.1.0"
    for tool in ("aapt", "apksigner", "zipalign"):
        require((tools / tool).is_file(), f"Missing Android verification tool: {tools / tool}")
    subprocess.run([
        sys.executable, str(root / "scripts/verify-xuancai-rime-package.py"), str(apk),
        "--bundled-rime", "--offline-dictation", "--aapt", str(tools / "aapt"),
        "--apksigner", str(tools / "apksigner"), "--expected-main-package", PACKAGE,
        "--expected-app-label", LABEL, "--expected-version-name", VERSION_NAME,
        "--expected-version-code", str(VERSION_CODE),
    ], check=True)
    signing = subprocess.check_output([str(tools / "apksigner"), "verify", "--print-certs", str(apk)], text=True)
    certificates = sorted(set(digest.lower() for digest in re.findall(
        r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", signing, re.MULTILINE)))
    require(len(certificates) == 1, "Expected one actually verified diagnostic APK signer")
    subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True)

    expected_files = {entry["path"]: entry for entry in manifest["files"]}
    require(len(expected_files) == sum(EXPECTED_COUNTS.values()), "Prepared manifest has duplicate or missing members")
    with zipfile.ZipFile(apk) as archive:
        actual_names, counts = archive_inputs(archive)
        require(set(actual_names) == set(expected_files), "Packaged native/assets file set changed")
        for name in actual_names:
            data = archive.read(name)
            entry = expected_files[name]
            require(len(data) == entry["bytes"] and hashlib.sha256(data).hexdigest() == entry["sha256"],
                    f"Packaged native/asset differs from verified stable 1.2: {name}")
    tests = unit_test_summary(root, args.expected_tests)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    verification = {
        "schema": 1, "version_name": VERSION_NAME, "version_code": VERSION_CODE,
        "package": PACKAGE, "label": LABEL, "abi": "arm64-v8a",
        "base_commit": BASE_COMMIT, "source_commit": commit,
        "source_apk_sha256": BASE_APK["sha256"], "apk_sha256": sha256(apk), "apk_bytes": apk.stat().st_size,
        "native_count": counts["lib"], "asset_count": counts["assets"],
        "all_native_and_assets_identical_to_stable_1_2": True,
        "network_permission": False, "minirbt_included": False,
        "submission_policy": "unchanged_up", "boundary_settling_default": True,
        "diagnostic_logging_default": False,
        "signature_verified": True, "signature_certificate_sha256": certificates[0],
        "signing_note": "Actual CI debug certificate; not asserted to match another diagnostic build or stable AXiang.",
        "zip_alignment_verified": True,
        "unit_test_classes": tests["classes"], "unit_tests": tests["tests"],
        "unit_test_failures": tests["failures"], "unit_test_errors": tests["errors"], "unit_test_skipped": tests["skipped"],
        "device_installation_or_launch_verified": False,
        "release_tag": RELEASE_TAG, "release_kind": "diagnostic_prerelease",
        "workflow_delivery_stage": "assets_uploaded_to_existing_draft_only; publication is a separate step",
        "github_run_id": os.environ.get("GITHUB_RUN_ID"),
    }
    write_json(output / "build-verification.json", verification)
    write_json(output / "unit-test-summary.json", tests)
    shutil.copyfile(work / "prebuilt-manifest.json", output / "prebuilt-manifest.json")
    checksum_paths = [apk, output / "build-verification.json", output / "unit-test-summary.json", output / "prebuilt-manifest.json"]
    (output / "SHA256SUMS.txt").write_text("".join(f"{sha256(path)}  {path.name}\n" for path in checksum_paths), encoding="utf-8")
    print(json.dumps(verification, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    preparation = commands.add_parser("prepare", help="Verify/extract stable native/assets and prepare the compile JAR/init script")
    preparation.add_argument("--root", type=Path, default=Path.cwd())
    preparation.add_argument("--work", type=Path, required=True)
    preparation.add_argument("--source-apk", type=Path, help="Reuse a local APK only if its pinned SHA-256 matches")
    preparation.add_argument("--runtime-aar", type=Path, help="Reuse the pinned official runtime AAR; no ASR/model archives are fetched")
    preparation.set_defaults(handler=prepare)
    verification = commands.add_parser("verify", help="Verify the real build, assets, signature and actual unit-test results")
    verification.add_argument("--root", type=Path, default=Path.cwd())
    verification.add_argument("--work", type=Path, required=True)
    verification.add_argument("--output", type=Path, required=True)
    verification.add_argument("--sdk", type=Path)
    verification.add_argument("--expected-tests", type=int, default=401)
    verification.set_defaults(handler=verify)
    args = parser.parse_args()
    try:
        args.handler(args)
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, ET.ParseError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Diagnostic delivery verification failed: {error}\n")


if __name__ == "__main__":
    main()
