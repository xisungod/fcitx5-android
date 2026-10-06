#!/usr/bin/env python3
"""Prepare and verify the isolated AXiang Pinyin touch experiment locally.

Reuses the hash-pinned stable 1.2 APK's complete native/assets set and adds exactly
the public-dictionary next-letter TSV and the source-built ARM64 Rime probe.
Current Kotlin/resources are compiled normally. No user logs, private dictionaries,
neural models, signing secrets, network fetches, or publication are handled here.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

PACKAGE = "org.fcitx.fcitx5.android.axiang.touch2"
LABEL = "AXiang 触点测试2"
VERSION_NAME = "1.2-touch.2"
VERSION_CODE = 2
APK_NAME = "AXiang-1.2-touch.2-arm64.apk"
MODEL_ASSET = "assets/typing/pinyin_touch_model.tsv"
PROBE_NATIVE = "lib/arm64-v8a/libaxiangtouch.so"
NDK_VERSION = "28.0.13004108"
EXPECTED_COUNTS = {"assets": 350, "lib": 28}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def stable_helper(root):
    path = root / "scripts/prepare-axiang-diagnostic-delivery.py"
    spec = importlib.util.spec_from_file_location("axiang_stable_delivery", path)
    require(spec is not None and spec.loader is not None, "Cannot load the pinned stable delivery helper")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def model_metadata(data, source):
    lines = data.decode("utf-8").splitlines()
    require(lines and lines[0].startswith("# AXPL1\t"), "Unexpected touch TSV format")
    metadata_lines = [line[2:] for line in lines if line.startswith("# {")]
    require(len(metadata_lines) == 1, "Expected one embedded public-model provenance object")
    metadata = json.loads(metadata_lines[0])
    require(metadata.get("format") == "AXPL1", "Touch model provenance format mismatch")
    require(metadata.get("source_project") == source["project"] and
            metadata.get("source_revision") == source["revision"] and
            metadata.get("source_archive_sha256") == source["archive_sha256"],
            "Touch model differs from the stable APK's pinned public dictionary")
    require(metadata.get("license", "").startswith("GPL-3.0"), "Touch model license provenance is missing")
    hashes = metadata.get("sources_sha256", {})
    require(hashes and all(source["files_sha256"].get(path) == digest for path, digest in hashes.items()),
            "Touch model source checksums differ from stable public dictionary provenance")
    probabilities, syllables = {}, set()
    scale = metadata.get("probability_scale")
    require(scale == 32768, "Unexpected next-letter probability scale")
    for line in lines:
        if not line or line.startswith("#"):
            continue
        columns = line.split("\t")
        if columns[0] == "P":
            require(len(columns) == 28 and columns[1] not in probabilities, "Invalid or duplicate probability row")
            values = [int(value) for value in columns[2:]]
            require(min(values) > 0 and sum(values) == scale, "Next-letter row must be positive and normalized")
            probabilities[columns[1]] = values
        elif columns[0] == "S":
            require(len(columns) == 3 and re.fullmatch(r"[a-z]+", columns[1]) and
                    columns[1] not in syllables and int(columns[2]) > 0,
                    "Invalid or duplicate syllable inventory row")
            syllables.add(columns[1])
        else:
            raise ValueError("Unexpected touch model row type")
    require("-" in probabilities and "^" in probabilities, "Missing global/start next-letter priors")
    require(len(probabilities) == metadata.get("contexts") and len(syllables) == metadata.get("syllables"),
            "Actual model row counts differ from provenance")
    return {**metadata, "asset": MODEL_ASSET, "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest()}


def probe_metadata(data, provenance, root, librime_sha256):
    """Check the actual new binary, its pinned dependency and source build inputs."""
    require(len(data) >= 64 and data[:6] == b"\x7fELF\x02\x01" and
            int.from_bytes(data[16:18], "little") == 3 and
            int.from_bytes(data[18:20], "little") == 183,
            "Touch probe must be an AArch64 ELF64 shared library")
    offset = int.from_bytes(data[32:40], "little")
    entry_size = int.from_bytes(data[54:56], "little")
    count = int.from_bytes(data[56:58], "little")
    require(entry_size == 56 and count > 0 and offset + entry_size * count <= len(data),
            "Touch probe has invalid ELF program headers")
    alignments = []
    for index in range(count):
        kind, _, file_offset, address, _, file_size, memory_size, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, offset + index * entry_size)
        if kind == 1:
            require(alignment >= 16384 and alignment & (alignment - 1) == 0 and
                    file_offset % alignment == address % alignment and
                    file_size <= memory_size and file_offset + file_size <= len(data),
                    "Touch probe LOAD segment is invalid or lacks 16 KiB page alignment")
            alignments.append(alignment)
    require(alignments, "Touch probe has no loadable ELF segment")
    digest = hashlib.sha256(data).hexdigest()
    require(provenance.get("schema") == 1 and provenance.get("abi") == "arm64-v8a" and
            provenance.get("ndk_version") == NDK_VERSION and
            provenance.get("native_sha256") == digest and provenance.get("native_bytes") == len(data) and
            provenance.get("librime_sha256") == librime_sha256,
            "Touch probe build provenance differs from actual bytes or the pinned baseline librime")
    source_hashes = provenance.get("source_files_sha256")
    require(isinstance(source_hashes, dict) and source_hashes,
            "Touch probe source checksums are missing")
    for name, expected in source_hashes.items():
        path = PurePosixPath(name)
        require(not path.is_absolute() and ".." not in path.parts and "\\" not in name and
                re.fullmatch(r"[0-9a-f]{64}", expected) is not None,
                "Touch probe has an unsafe source path or malformed checksum")
        source = (root / name).resolve()
        require(source.is_relative_to(root) and source.is_file() and sha256(source) == expected,
                f"Touch probe source checksum differs: {name}")
    return {**provenance, "native": PROBE_NATIVE, "load_segment_alignments": alignments}


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
            require(name.startswith("lib/arm64-v8a/") and name.endswith(".so"), f"Unexpected native member: {name}")
    return selected, counts


def prepare(args):
    root, work = args.root.resolve(), args.work.resolve()
    require((root / "app/build.gradle.kts").is_file(), "--root must be the Android checkout")
    helper = stable_helper(root)
    source = helper.verified_file(args.source_apk.resolve(), helper.BASE_APK)
    prebuilt = work / "prebuilt"
    overlay = work / "overlay"
    work.mkdir(parents=True, exist_ok=True)
    # Always extract one verified native/assets set. Stale input paths cannot
    # survive a repeated preparation. This helper never fetches missing files.
    for directory in (prebuilt, overlay):
        if directory.exists():
            shutil.rmtree(directory)
    files = []
    with zipfile.ZipFile(source) as archive:
        names, counts = helper.archive_inputs(archive)
        speech = json.loads(archive.read("assets/asr/SOURCE.json"))
        public_source = json.loads(archive.read("assets/usr/share/licenses/rime-ice/SOURCE.json"))
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
    require(MODEL_ASSET not in names and PROBE_NATIVE not in names,
            "Pinned baseline unexpectedly already contains an experimental addition")
    model = args.model.resolve() if args.model else root / "app/src/main/assets/typing/pinyin_touch_model.tsv"
    model_data = model.read_bytes()
    metadata = model_metadata(model_data, public_source)
    overlay_model = overlay / MODEL_ASSET
    overlay_model.parent.mkdir(parents=True, exist_ok=True)
    overlay_model.write_bytes(model_data)

    native_data = args.native_probe.resolve().read_bytes()
    native_provenance = json.loads(args.native_provenance.resolve().read_text(encoding="utf-8"))
    librime_hash = next(entry["sha256"] for entry in files if entry["path"] == "lib/arm64-v8a/librime.so")
    native_metadata = probe_metadata(native_data, native_provenance, root, librime_hash)
    overlay_native = overlay / "jniLibs" / PROBE_NATIVE.removeprefix("lib/")
    overlay_native.parent.mkdir(parents=True, exist_ok=True)
    overlay_native.write_bytes(native_data)

    jar = root / "app/libs/sherpa-onnx-1.13.8.jar"
    require(jar.is_file() and sha256(jar) == speech["compile_jar"]["sha256"],
            "Existing compile JAR must match stable 1.2; prepare the pinned speech runtime first")
    manifest = {"schema": 2, "source_apk": helper.BASE_APK, "source_apk_path": str(source),
                "source_apk_sha256": helper.BASE_APK["sha256"],
                "base_commit": helper.BASE_COMMIT, "baseline_counts": counts, "packaged_counts": EXPECTED_COUNTS,
                "files": files, "added_files": [{"path": MODEL_ASSET, "sha256": metadata["sha256"],
                                                   "bytes": metadata["bytes"]},
                                                  {"path": PROBE_NATIVE, "sha256": native_metadata["native_sha256"],
                                                   "bytes": native_metadata["native_bytes"]}],
                "model_provenance": metadata,
                "native_provenance": native_metadata,
                "compile_jar": {"path": str(jar.relative_to(root)), "sha256": sha256(jar)}}
    write_json(work / "prebuilt-manifest.json", manifest)
    def groovy_path(path):
        return json.dumps(str(path), ensure_ascii=False).replace("$", "\\$")
    init = f"""// Stable 1.2 bytes, plus exactly one public TSV and one source-built ARM64 probe.
def verifiedInput = new File({groovy_path(prebuilt)})
def touchOverlay = new File({groovy_path(overlay)})
gradle.beforeProject {{ p ->
    ['com.android.application', 'com.android.library'].each {{ androidPlugin ->
        p.plugins.withId(androidPlugin) {{
            p.extensions.getByName('androidComponents').finalizeDsl {{ dsl ->
                dsl.externalNativeBuild.cmake.path = null
                dsl.buildFeatures.prefab = false
                if (androidPlugin == 'com.android.library') dsl.buildFeatures.prefabPublishing = false
                if (p.path == ':app') {{
                    dsl.buildTypes.getByName('debug').resValue('string', 'app_name', '{LABEL}')
                    dsl.sourceSets.getByName('main').assets.setSrcDirs([
                        new File(verifiedInput, 'assets'), new File(touchOverlay, 'assets')])
                    dsl.sourceSets.getByName('main').jniLibs.setSrcDirs([
                        new File(verifiedInput, 'jniLibs'), new File(touchOverlay, 'jniLibs')])
                    dsl.packaging.jniLibs.keepDebugSymbols.add('**/*.so')
                }}
            }}
        }}
    }}
}}
"""
    init_path = work / "touch.init.gradle"
    init_path.write_text(init, encoding="utf-8")
    print(json.dumps({"packaged_counts": EXPECTED_COUNTS, "model": metadata, "native_probe": native_metadata,
                      "source_apk_sha256": helper.BASE_APK["sha256"], "init": str(init_path)}, ensure_ascii=False))


def verify(args):
    root, work, output = args.root.resolve(), args.work.resolve(), args.output.resolve()
    helper = stable_helper(root)
    manifest = json.loads((work / "prebuilt-manifest.json").read_text(encoding="utf-8"))
    require(manifest["schema"] == 2 and manifest["source_apk"] == helper.BASE_APK and
            manifest["baseline_counts"] == helper.EXPECTED_COUNTS
            and manifest["packaged_counts"] == EXPECTED_COUNTS, "Prepared inputs do not match this pinned delivery")
    apks = sorted((root / "app/build/outputs/apk/debug").glob("*.apk"))
    require(len(apks) == 1, f"Expected one arm64 debug APK, found {len(apks)}")
    sdk_value = args.sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    require(sdk_value, "Android SDK path is required for real APK verification")
    tools = Path(sdk_value).resolve() / "build-tools/36.1.0"
    for tool in ("aapt", "apksigner", "zipalign"):
        require((tools / tool).is_file(), f"Missing Android verification tool: {tools / tool}")
    # Verify the newly built artifact before copying it into the delivery folder.
    source_apk = apks[0]
    subprocess.run([
        sys.executable, str(root / "scripts/verify-xuancai-rime-package.py"), str(source_apk),
        "--bundled-rime", "--offline-dictation", "--aapt", str(tools / "aapt"),
        "--apksigner", str(tools / "apksigner"), "--expected-main-package", PACKAGE,
        "--expected-app-label", LABEL, "--expected-version-name", VERSION_NAME,
        "--expected-version-code", str(VERSION_CODE),
    ], check=True)
    signing = subprocess.check_output([str(tools / "apksigner"), "verify", "--print-certs", str(source_apk)], text=True)
    certificates = sorted(set(digest.lower() for digest in re.findall(
        r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", signing, re.MULTILINE)))
    require(len(certificates) == 1, "Expected one actually verified touch-test APK signer")
    subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(source_apk)], check=True)
    baseline = {entry["path"]: entry for entry in manifest["files"]}
    additions = {entry["path"]: entry for entry in manifest["added_files"]}
    require(len(baseline) == len(manifest["files"]) == sum(helper.EXPECTED_COUNTS.values()) and
            len(additions) == len(manifest["added_files"]) == 2 and
            set(additions) == {MODEL_ASSET, PROBE_NATIVE} and not set(baseline).intersection(additions),
            "Prepared manifest has duplicate, missing or unexpected members")
    pinned_apk = helper.verified_file(Path(manifest["source_apk_path"]), helper.BASE_APK)
    with zipfile.ZipFile(pinned_apk) as archive:
        pinned_names, _ = helper.archive_inputs(archive)
        require(set(pinned_names) == set(baseline), "Prepared baseline member list differs from pinned stable 1.2")
        for name in pinned_names:
            data, entry = archive.read(name), baseline[name]
            require(len(data) == entry["bytes"] and hashlib.sha256(data).hexdigest() == entry["sha256"],
                    f"Prepared baseline provenance differs from pinned stable 1.2: {name}")
    expected_files = baseline | additions
    with zipfile.ZipFile(source_apk) as archive:
        names, counts = archive_inputs(archive)
        require(set(names) == set(expected_files), "Packaged native/assets file set changed")
        for name in names:
            data, entry = archive.read(name), expected_files[name]
            require(len(data) == entry["bytes"] and hashlib.sha256(data).hexdigest() == entry["sha256"],
                    f"Packaged native/asset differs from prepared verified input: {name}")
        public_source = json.loads(archive.read("assets/usr/share/licenses/rime-ice/SOURCE.json"))
        actual_model = model_metadata(archive.read(MODEL_ASSET), public_source)
        require(actual_model == manifest["model_provenance"], "Packaged model metadata differs from preparation")
        actual_probe = probe_metadata(archive.read(PROBE_NATIVE), manifest["native_provenance"], root,
                                      baseline["lib/arm64-v8a/librime.so"]["sha256"])
        require(actual_probe == manifest["native_provenance"], "Packaged probe metadata differs from preparation")
    tests = helper.unit_test_summary(root, args.expected_tests)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True).strip())
    verification = {
        "schema": 2, "version_name": VERSION_NAME, "version_code": VERSION_CODE,
        "package": PACKAGE, "label": LABEL, "abi": "arm64-v8a",
        "base_commit": helper.BASE_COMMIT, "source_checkout_head": commit, "source_checkout_dirty": dirty,
        "source_apk_sha256": helper.BASE_APK["sha256"], "apk_sha256": sha256(source_apk),
        "apk_bytes": source_apk.stat().st_size, "native_count": counts["lib"], "asset_count": counts["assets"],
        "all_27_native_and_349_baseline_assets_identical_to_stable_1_2": True,
        "only_added_asset": MODEL_ASSET, "only_added_native": PROBE_NATIVE,
        "public_next_letter_model": actual_model, "native_probe": actual_probe,
        "network_permission": False, "minirbt_included": False,
        "signature_verified": True, "signature_certificate_sha256": certificates[0],
        "signing_note": "Actual local debug certificate; not asserted to match stable AXiang or another test APK.",
        "zip_alignment_verified": True,
        "unit_test_classes": tests["classes"], "unit_tests": tests["tests"],
        "unit_test_failures": tests["failures"], "unit_test_errors": tests["errors"], "unit_test_skipped": tests["skipped"],
        "device_installation_or_launch_verified": False, "release_kind": "local_opt_in_touch_experiment",
        "publication_performed": False,
    }
    output.mkdir(parents=True, exist_ok=True)
    apk = output / APK_NAME
    shutil.copyfile(source_apk, apk)
    write_json(output / "build-verification.json", verification)
    write_json(output / "unit-test-summary.json", tests)
    write_json(output / "model-provenance.json", actual_model)
    write_json(output / "native-provenance.json", actual_probe)
    shutil.copyfile(work / "prebuilt-manifest.json", output / "prebuilt-manifest.json")
    paths = [apk, output / "build-verification.json", output / "unit-test-summary.json",
             output / "model-provenance.json", output / "native-provenance.json", output / "prebuilt-manifest.json"]
    (output / "SHA256SUMS.txt").write_text("".join(f"{sha256(path)}  {path.name}\n" for path in paths), encoding="utf-8")
    print(json.dumps(verification, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    preparation = commands.add_parser("prepare", help="Verify/extract stable bytes and add the public TSV and source-built probe")
    preparation.add_argument("--root", type=Path, default=Path.cwd())
    preparation.add_argument("--work", type=Path, required=True)
    preparation.add_argument("--source-apk", type=Path, required=True, help="The local SHA-256-pinned stable 1.2 arm64 APK")
    preparation.add_argument("--model", type=Path, help="Override the checkout's next-letter TSV path")
    preparation.add_argument("--native-probe", type=Path, required=True, help="The source-built ARM64 libaxiangtouch.so")
    preparation.add_argument("--native-provenance", type=Path, required=True, help="Its source/native/NDK checksum provenance JSON")
    preparation.set_defaults(handler=prepare)
    verification = commands.add_parser("verify", help="Verify APK identity, baseline bytes, exact additions, signature and full-suite results")
    verification.add_argument("--root", type=Path, default=Path.cwd())
    verification.add_argument("--work", type=Path, required=True)
    verification.add_argument("--output", type=Path, required=True)
    verification.add_argument("--sdk", type=Path)
    verification.add_argument("--expected-tests", type=int, required=True)
    verification.set_defaults(handler=verify)
    args = parser.parse_args()
    try:
        args.handler(args)
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, ET.ParseError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Touch-test delivery verification failed: {error}\n")


if __name__ == "__main__":
    main()
