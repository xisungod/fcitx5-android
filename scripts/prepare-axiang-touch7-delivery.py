#!/usr/bin/env python3
"""Verify touch.7 application changes against the exact public touch.6 APK.

All 350 assets and 28 native libraries must remain byte-identical. Recompile the
unchanged probe and rerun actual host/ARM checks against the frozen source; native
provenance also binds the changed Kotlin wrapper. Require the actual DEX marker,
full JUnit XML, durable signer and 16 KiB alignment. No publication or key access.
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
import tempfile
import zipfile

sys.dont_write_bytecode = True
_common_spec = importlib.util.spec_from_file_location(
    "touch5_delivery_common", Path(__file__).with_name("prepare-axiang-touch5-delivery.py"))
common = importlib.util.module_from_spec(_common_spec)
_common_spec.loader.exec_module(common)
require, sha256, write_json, git = common.require, common.sha256, common.write_json, common.git

BASE_COMMIT = "48a497bdda5d2dd8cfcd8fe83eedce47e3c65578"
BASE_APK_SHA256 = "ab4178a3d014fc85d6f4b26ebbfcb25a1cfe97cfef14e0d30f5112191c042aa9"
BASE_APK_BYTES = 268244332
RIME_VERSION = "1.16.1"
RIME_SHA256 = "e81472fd974a557e7233b0a0ca5659fa7da6c4a2eccb5a4a08c9d807d14c7159"
RIME_REVISION = "de4700e9f6b75b109910613df907965e3cbe0567"
RIME_SOURCE_SHA256 = "62816f834d938fe40bef86565116826319485c0585d3e2bb1fdd255e3a56308d"
PREBUILT_REVISION = "92ab7d6291a1fa426f199be917857ebb6054d08c"
PREBUILDER_REVISION = "71c6edbf9850209e8c36192ad97ccc55dd25573f"
RIME_ABI_PATCH_SHA256 = "fbbc68497ac908a01ce0f6344bf1c5d661b22bd520f0993463313c78f22354e3"
LIBCXX_SHA256 = "b17919df195f29b0a238468c04171b93cedcdffbe0d30cdc634327cf7f7889bc"
BOOST_SHA256 = "aca59f889f0f32028ad88ba6764582b63c916ce5f77b31289ad19421a96c555f"
GENERATED_CONFIG_SHA256 = "b9fee5e534f12bbc050f776ca7b0837746df74a7e61f75b5b50105735ae5dd74"
ABI_HEADERS_SHA256 = {
    "rime/engine.h": "53533ea444716517b6ddcd2c4ac8880c7d5d8700c9ae9c40b3fc2ea18945e774",
    "rime/context.h": "d9bd9fe926f9ef6123f173841331e99d0d399b78408730c23ce6c2890f0b4dbc",
    "rime/gear/memory.h": "949118f744358dc7f4dd33ee2d3ec409ef0225042822ceb8045a76ecf7b10c9b",
    "rime/common.h": "b0671fc7a4d0e12812bdc10447bcb00d6d490fc8d42cebe3ebc5bafc313cf256",
}
COMPILER_SHA256 = "315eee3085eef60d589d62c1c81d8525f31f55bf1416bc1a1abe8572a60cd372"
STRIP_SHA256 = "8a07cfa758258bba569776bb833aa079551bc0fd7e67972f3bd6e482a37f3722"
ABI_FLAGS = ["--target=aarch64-linux-android23", "-std=c++17", "-fPIC", "-shared",
             "-fvisibility=default", "-DBOOST_DISABLE_CURRENT_LOCATION",
             "-DBOOST_ALL_NO_EMBEDDED_GDB_SCRIPTS", "-DGLOG_USE_GLOG_EXPORT", "-DGLOG_STATIC_DEFINE"]
RTTI_HEADER_SHA256 = {
    "original_header_sha256": "5352aaa68d0de67f8ff9f7785015ba19eb567c548b25b4c4fe301c1f7652cb65",
    "bridge_header_sha256": "2b4c5dfde3a218257bf75d7cbd18deb3745953ef3af03151026ff1154564d2a5",
    "translator_original_header_sha256": "1f6ee3057fcb00be8899e2e6cf0cfeeed951f10f4afaf46a5627ebf05fbbf343",
    "translator_bridge_header_sha256": "c12f54c2c899dadf032e5ca10cde949ec78bfa23bd9b11a3b04a9d525b15ff07",
}
RTTI_SYMBOLS = [
    "_ZTIN4rime13ComponentBaseE",
    "_ZTIN4rime5ClassINS_6ConfigERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEEE9ComponentE",
    "_ZTIN4rime5ClassINS_10TranslatorERKNS_6TicketEE9ComponentE",
    "_ZTIN4rime10TranslatorE",
    "_ZTIN4rime6MemoryE",
]
PACKAGE, LABEL, SIGNER = common.PACKAGE, common.LABEL, common.SIGNER
VERSION_NAME, VERSION_CODE = "1.2-touch.7", 7
APK_NAME = "AXiang-1.2-touch.7-arm64.apk"
PROBE_ASSET = "lib/arm64-v8a/libaxiangtouch.so"
RIME_ASSET = "lib/arm64-v8a/librime.so"
LUA_SOURCE, LUA_ASSET = common.LUA_SOURCE, common.LUA_ASSET
PUBLIC_FILES = (common.PUBLIC_FILES - {common.APK_NAME}) | {
    APK_NAME, "native-probe-provenance.json", "packaged-rime-version.json", "runtime-smoke-summary.json"}
PUBLIC_PREFIXES = common.PUBLIC_PREFIXES + ("app/src/main/cpp/typing/",)
PUBLIC_EXACT = {
    "scripts/prepare-axiang-touch7-delivery.py", "docs/axiang/touch7.md",
    "scripts/verify-packaged-rime-version.py", "scripts/build-rime-touch-probe.py",
    "scripts/check-rime-touch-probe.cpp", "scripts/check-rime-touch-commit.cpp",
    "scripts/benchmark-rime-touch-probe.cpp", "scripts/test-rime-touch-probe.py",
    "scripts/test-axiang-touch7-native.py", "scripts/rime/xuancai_correction.lua",
    "scripts/check-axiang-correction-ranking.cpp", "scripts/test-axiang-correction-ranking.py",
    "scripts/check-axiang-adjacent-correction.c", "scripts/check-xuancai-rime.c",
    "scripts/benchmark-axiang-correction-latency.py", "scripts/check-axiang-correction-latency.c",
    "scripts/check-axiang-correction-stream.cpp",
    "scripts/prepare-rime-touch-fixture.cpp", "scripts/check-packaged-rime-touch-jni.cpp",
    "scripts/test-packaged-rime-touch-runtime.py",
}
REQUIRED_NATIVE_SOURCES = {
    "app/src/main/cpp/typing/rime-touch-probe.h",
    "app/src/main/cpp/typing/rime-touch-probe.cpp",
    "app/src/main/cpp/typing/rime-touch-jni.cpp",
    "scripts/build-rime-touch-probe.py",
}


def pinned_baseline(path):
    require(path.is_file() and not path.is_symlink(), "Public touch.6 APK must be a regular file")
    require(path.stat().st_size == BASE_APK_BYTES and sha256(path) == BASE_APK_SHA256,
            "Public touch.6 APK identity differs")
    return path


def source_hashes(root, records, required=()):
    require(isinstance(records, dict) and set(required) <= set(records),
            "Native evidence omits required source identities")
    for name, digest in records.items():
        relative = PurePosixPath(name)
        require(not relative.is_absolute() and ".." not in relative.parts and "\\" not in name and
                (name.startswith(PUBLIC_PREFIXES) or name in PUBLIC_EXACT),
                "Unreviewed native evidence source: " + name)
        require(re.fullmatch(r"[0-9a-f]{64}", digest or "") and
                sha256(root / name) == digest, "Native evidence source differs: " + name)


def public_json(raw, label):
    require(not re.search(rb"github_pat_[A-Za-z0-9_]{20,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|"
                          rb"/tmp/codex-remote-attachments/|/workspace/|/home/|/private/", raw),
            label + " contains private data or local paths")
    return json.loads(raw)


def probe_provenance(root, folder):
    native, provenance_path = folder / "libaxiangtouch.so", folder / "provenance.json"
    require(native.is_file() and not native.is_symlink() and provenance_path.is_file() and
            not provenance_path.is_symlink(), "Probe build output must contain regular library/provenance files")
    provenance = public_json(provenance_path.read_bytes(), "Probe provenance")
    require(provenance.get("schema") == 2 and provenance.get("abi") == "arm64-v8a" and
            provenance.get("ndk_version") == "28.0.13004108" and
            provenance.get("native_sha256") == sha256(native) and
            provenance.get("native_bytes") == native.stat().st_size and
            provenance.get("librime_sha256") == RIME_SHA256 and
            provenance.get("runtime_version") == RIME_VERSION,
            "Probe runtime/compiler/output identity differs")
    headers = provenance.get("upstream_headers", {})
    require(provenance.get("android_api") == 23 and
            provenance.get("prebuilt_revision") == PREBUILT_REVISION and
            provenance.get("prebuilder_revision") == PREBUILDER_REVISION and
            provenance.get("librime_revision") == RIME_REVISION and
            provenance.get("libcxx_sha256") == LIBCXX_SHA256 and
            headers.get("librime", {}).get("sha256") == RIME_SOURCE_SHA256 and
            headers.get("boost", {}).get("sha256") == BOOST_SHA256 and
            headers.get("boost", {}).get("version") == "1.90.0" and
            headers.get("glog", {}).get("prebuilt_revision") == PREBUILT_REVISION and
            provenance.get("abi_patch", {}).get("sha256") == RIME_ABI_PATCH_SHA256 and
            provenance.get("generated_build_config_sha256") == GENERATED_CONFIG_SHA256 and
            provenance.get("abi_header_sha256") == ABI_HEADERS_SHA256 and
            provenance.get("compiler", {}).get("sha256") == COMPILER_SHA256 and
            provenance.get("strip", {}).get("sha256") == STRIP_SHA256 and
            provenance.get("abi_flags") == ABI_FLAGS,
            "Probe must use the actual runtime's exact source, Android ABI patch and headers")
    source_hashes(root, provenance.get("source_files_sha256"), REQUIRED_NATIVE_SOURCES)
    adapter = provenance.get("component_rtti_import", {})
    require(all(adapter.get(key) == digest for key, digest in RTTI_HEADER_SHA256.items()) and
            adapter.get("required_rtti_symbols") == RTTI_SYMBOLS and
            adapter.get("all_required_imported_from_runtime") is True and
            adapter.get("main_library_modified") is False,
            "Probe must import the actual runtime's RTTI through the reviewed bridge-only header adapter")
    require(provenance.get("initializes_or_deploys_rime") is False and
            provenance.get("uses_rime_service_session") is False and
            provenance.get("probe_user_dictionary_enabled") is False,
            "Probe build does not retain isolated no-learning behavior")
    data = native.read_bytes()
    require(data[:6] == b"\x7fELF\x02\x01" and struct.unpack_from("<H", data, 18)[0] == 183,
            "Probe must be AArch64 little-endian ELF64")
    offset, entry_size, count = struct.unpack_from("<Q", data, 32)[0], *struct.unpack_from("<HH", data, 54)
    alignments = [struct.unpack_from("<Q", data, offset + index * entry_size + 48)[0]
                  for index in range(count)
                  if struct.unpack_from("<I", data, offset + index * entry_size)[0] == 1]
    require(alignments and min(alignments) >= 16384 and
            provenance.get("elf_load_alignment") == alignments, "Probe load alignment evidence differs")
    return native, provenance, provenance_path


def verify_rtti_imports(root, baseline, native):
    spec = importlib.util.spec_from_file_location("packaged_rime_elf", root / "scripts/verify-packaged-rime-version.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    probe_elf = module.Elf64(native.read_bytes())
    with zipfile.ZipFile(baseline) as archive:
        runtime_elf = module.Elf64(archive.read(RIME_ASSET))
    for symbol in RTTI_SYMBOLS:
        imports = [item for item in probe_elf.symbols if item["name"] == symbol]
        exports = [item for item in runtime_elf.symbols if item["name"] == symbol]
        require(len(imports) == len(exports) == 1 and imports[0]["section"] == 0 and
                exports[0]["section"] != 0 and exports[0]["other"] & 3 == 0,
                "Actual ELF RTTI import/export does not match the runtime: " + symbol)


def prepare(args):
    root, work = args.root.resolve(), args.work.resolve()
    require((root / "app/build.gradle.kts").is_file(), "--root must be the Android checkout")
    baseline = pinned_baseline(args.source_apk.resolve())
    native, provenance, _ = probe_provenance(root, args.probe_build.resolve())
    verify_rtti_imports(root, baseline, native)
    head, dirty = git(root, "rev-parse", "HEAD").decode().strip(), git(root, "status", "--porcelain") != b""
    reuse = args.reuse_prebuilt is not None
    prebuilt = args.reuse_prebuilt.resolve() if reuse else work / "prebuilt"
    work.mkdir(parents=True, exist_ok=True)
    require(not reuse or prebuilt.is_dir(), "Reusable inputs must already exist")
    if prebuilt.exists() and not reuse:
        shutil.rmtree(prebuilt)
    lua_bytes = (root / LUA_SOURCE).read_bytes()
    files, baseline_files, changed = [], [], []
    with zipfile.ZipFile(baseline) as archive:
        names, counts = common.archive_inputs(archive)
        speech = json.loads(archive.read("assets/asr/SOURCE.json"))
        overlay = common.lua_overlay(archive, lua_bytes)
        overlay[PROBE_ASSET] = native.read_bytes()
        require(hashlib.sha256(archive.read(RIME_ASSET)).hexdigest() == RIME_SHA256,
                "Baseline engine is not the verified actual 1.16.1 runtime")
        for name in names:
            previous = archive.read(name)
            current = overlay.get(name, previous)
            baseline_files.append({"path": name, "bytes": len(previous),
                                   "sha256": hashlib.sha256(previous).hexdigest()})
            files.append({"path": name, "bytes": len(current),
                          "sha256": hashlib.sha256(current).hexdigest()})
            if current != previous:
                changed.append(name)
            destination = (prebuilt / "jniLibs" / name.removeprefix("lib/")
                           if name.startswith("lib/") else prebuilt / name)
            if reuse:
                require(destination.is_file() and not destination.is_symlink() and
                        destination.stat().st_size == len(current) and
                        sha256(destination) == hashlib.sha256(current).hexdigest(),
                        "Reusable input differs from reviewed bytes: " + name)
            else:
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(current)
    require(not changed, "Touch.7 must retain every native/data byte from verified touch.6")
    changed_assets = sorted(name for name in changed if name.startswith("assets/"))
    require(changed_assets == [] and changed == [], "Touch.7 changes only application code/resources")
    jar = root / "app/libs/sherpa-onnx-1.13.8.jar"
    require(jar.is_file() and sha256(jar) == speech["compile_jar"]["sha256"],
            "Speech compile JAR differs from unchanged packaged speech runtime")
    manifest = {"schema": 7, "base_commit": BASE_COMMIT, "source_apk_name": baseline.name,
                "source_apk_sha256": BASE_APK_SHA256, "source_apk_bytes": BASE_APK_BYTES,
                "source_commit": head, "source_checkout_dirty": dirty, "counts": counts,
                "files": files, "base_files": baseline_files, "added_files": [],
                "changed_assets": changed_assets, "changed_native": [],
                "probe_sha256": provenance["native_sha256"], "runtime_version": RIME_VERSION,
                "librime_sha256": RIME_SHA256, "lua_sha256": hashlib.sha256(lua_bytes).hexdigest(),
                "compile_jar": {"path": str(jar.relative_to(root)), "sha256": sha256(jar)}}
    write_json(work / "prebuilt-manifest.json", manifest)
    escaped = json.dumps(str(prebuilt), ensure_ascii=False).replace("$", "\\$")
    (work / "typing-test.init.gradle").write_text(f"""// Pinned touch.6 engine/data; independently rebuilt probe must match byte for byte.
def verifiedInput = new File({escaped})
gradle.beforeProject {{ p ->
    // These tasks install already hash-checked CMake output. With CMake
    // disabled, forcing them to rerun would query an absent cxxAbiModel.
    // All Java/Kotlin, Android resource, JNI merge, APK and test tasks run.
    p.tasks.configureEach {{ task ->
        def type = task.class
        def nativeInstaller = false
        while (type != null) {{
            if (type.simpleName == 'CMakeBuildInstallTask') nativeInstaller = true
            type = type.superclass
        }}
        if (nativeInstaller) task.enabled = false
    }}
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
""", encoding="utf-8")
    print(json.dumps({"prepared": True, "source_checkout_dirty": dirty,
                      "changed_assets": changed_assets, "changed_native": []}, ensure_ascii=False))


def source_patch(root, commit):
    require(git(root, "merge-base", "--is-ancestor", BASE_COMMIT, commit) == b"",
            "Frozen source does not descend from public touch.6")
    paths = [value.decode("utf-8") for value in
             git(root, "diff", "--name-only", "-z", BASE_COMMIT, commit).split(b"\0") if value]
    require(paths, "No touch.7 source changes found")
    records = {}
    for name in paths:
        require(name.startswith(PUBLIC_PREFIXES) or name in PUBLIC_EXACT,
                "Touch.7 update contains an unreviewed source path: " + name)
        blob = git(root, "show", commit + ":" + name)
        blob.decode("utf-8")
        require(b"\0" not in blob, "Public source includes binary data: " + name)
        records[name] = {"bytes": len(blob), "sha256": hashlib.sha256(blob).hexdigest()}
    patch = git(root, "diff", "--full-index", BASE_COMMIT, commit, "--", *paths)
    require(not re.search(rb"(?m)^GIT binary patch$", patch), "Public patch includes binary payload")
    return patch, {"base_commit": BASE_COMMIT, "source_commit": commit, "files": paths,
                   "source_files": [{"path": name, **record} for name, record in records.items()],
                   "patch": "typing-test-source.patch", "bytes": len(patch),
                   "sha256": hashlib.sha256(patch).hexdigest(),
                   "private_logs_prebuilts_and_generated_binaries_included": False}


def defaults(root):
    source = (root / "app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt").read_text()
    expected = {"pinyin_down_order": True, "pinyin_touch_alternatives": True,
                "pinyin_touch_promotion": True, "pinyin_touch_personalization": True,
                "touch_diagnostic_logging": False, "pinyin_touch_correction": False,
                "touch_boundary_settling": False}
    for key, value in expected.items():
        matches = re.findall(r'"' + re.escape(key) + r'"\s*,\s*(true|false)\b', source)
        require(matches == [str(value).lower()], "Expected default is not uniquely present: " + key)
    return {("diagnostic_logging" if key == "touch_diagnostic_logging" else key): value
            for key, value in expected.items()}


def native_test_summary(args, root, commit, apk):
    require(args.native_summary.is_file() and not args.native_summary.is_symlink() and
            args.native_summary.stat().st_size < 512 * 1024, "Native summary must be a bounded regular file")
    summary = public_json(args.native_summary.read_bytes(), "Native summary")
    require(summary.get("schema") == 2 and summary.get("source_commit") == commit and
            summary.get("source_working_tree_clean") is True, "Native tests must identify clean frozen source")
    host = summary.get("host_rime", {})
    require(host.get("engine_version") == RIME_VERSION and args.native_rime_library.is_file() and
            sha256(args.native_rime_library) == host.get("library_sha256"),
            "Tests must actually use the identified host Rime 1.16.1 library")
    require(summary.get("device_verification") is False and summary.get("phone_latency_verified") is False,
            "Host tests may not claim real phone accuracy, installation, or latency")
    source_hashes(root, summary.get("test_sources_sha256"))
    require(summary.get("test_sources_sha256"), "Native tests must identify their actual executable sources")
    require(summary.get("lua_source") == {"path": LUA_SOURCE, "sha256": sha256(root / LUA_SOURCE)},
            "Native tests used a different correction Lua")
    schemas = summary.get("schemas_sha256", {})
    require(set(schemas) == {"assets/usr/share/rime-data/rime_ice.schema.yaml",
                           "assets/usr/share/rime-data/build/rime_ice.schema.yaml"},
            "Native tests must identify packaged original and compiled schemas")
    with zipfile.ZipFile(apk) as archive:
        for name, digest in schemas.items():
            require(hashlib.sha256(archive.read(name)).hexdigest() == digest,
                    "Native tests used a different packaged schema: " + name)
    tests = summary.get("tests", {})
    require(isinstance(tests.get("cases"), int) and tests["cases"] > 0 and
            all(tests.get(key) == 0 for key in ("failures", "errors", "skipped")), "Native tests did not pass")
    suites = tests.get("suites", {})
    require(isinstance(suites, dict) and {
            "correction_ranking", "adjacent_correction", "rime_smoke",
            "readonly_probe_isolation", "explicit_probe_candidate_commit"} <= set(suites),
            "Native tests must cover the engine, isolated probe and native candidate commit")
    totals = {key: 0 for key in ("cases", "failures", "errors", "skipped")}
    for suite in suites.values():
        values = suite.get("tests", suite)
        require(isinstance(values.get("cases"), int) and values["cases"] > 0 and
                all(values.get(key) == 0 for key in ("failures", "errors", "skipped")), "Native suite failed")
        for key in totals:
            totals[key] += values[key]
    require(all(tests[key] == totals[key] for key in totals), "Native suite counts differ from total")
    return summary


def runtime_test_summary(args, root, commit, apk, probe):
    require(args.runtime_summary.is_file() and not args.runtime_summary.is_symlink() and
            args.runtime_summary.stat().st_size < 512 * 1024,
            "Actual Android runtime summary must be a bounded regular file")
    summary = public_json(args.runtime_summary.read_bytes(), "Actual Android runtime summary")
    require(summary.get("schema") == 1 and summary.get("source_commit") == commit and
            summary.get("source_checkout_dirty") is False and summary.get("passed") is True and
            summary.get("runtime") == "qemu-aarch64 with Android bionic; no phone UI" and
            summary.get("engine_version") == RIME_VERSION and
            summary.get("baseline_apk_sha256") == BASE_APK_SHA256 and
            summary.get("native_sha256") == sha256(probe) and
            summary.get("native_bytes") == probe.stat().st_size,
            "Android execution did not pass against the frozen source and actual packaged libraries")
    with zipfile.ZipFile(apk) as archive:
        dependencies = {PurePosixPath(name).name: hashlib.sha256(archive.read(name)).hexdigest()
                        for name in common.archive_inputs(archive)[0]
                        if name.startswith("lib/") and name != PROBE_ASSET}
        shared_assets = {name.removeprefix("assets/usr/share/rime-data/"):
                         hashlib.sha256(archive.read(name)).hexdigest()
                         for name in common.archive_inputs(archive)[0]
                         if name.startswith("assets/usr/share/rime-data/")}
    require(summary.get("dependency_hashes") == dependencies,
            "Android execution dependency provenance differs from actual APK")
    loaded = summary.get("actually_loaded_dependency_hashes", {})
    require(isinstance(loaded, dict) and {"librime.so", "libc++_shared.so"} <= set(loaded) and
            set(loaded) <= set(dependencies) and
            all(dependencies[name] == digest for name, digest in loaded.items()),
            "Android execution did not load the actual packaged Rime/C++ runtime")
    evidence = summary.get("evidence", {})
    require(evidence.get("engine") == RIME_VERSION and
            evidence.get("jni_adapter") == "FakeJNI, no ART" and
            evidence.get("actual_shared_library") is True and evidence.get("create_succeeded") is True and
            isinstance(evidence.get("queries"), int) and evidence["queries"] > 0 and
            isinstance(evidence.get("targets_found"), int) and
            evidence["targets_found"] == evidence["queries"] and
            isinstance(evidence.get("returned_candidates"), int) and
            evidence["queries"] <= evidence["returned_candidates"] <= evidence["queries"] * 3 and
            all(evidence.get(key) is True for key in
                ("wrong_thread_rejected", "budget_empty_is_ready", "raw_input_unchanged",
                 "caret_unchanged", "live_menu_unchanged", "user_files_unchanged", "no_commit",
                 "owner_pthread_enforced", "duplicate_create_rejected", "unsupported_schema_rejected",
                 "invalid_input_rejected", "oversize_context_rejected", "utf8_context_supported",
                 "recreate_succeeded", "gaileme_literal_first")),
            "Actual shared JNI probe did not pass successful-query/isolation checks")
    fixture = summary.get("fixture", {})
    require(fixture.get("setup_deployed") is True and fixture.get("no_probe_deploy") is True and
            fixture.get("disposable") is True and fixture.get("synthetic_public_inputs_only") is True and
            fixture.get("shared_asset_hashes") == shared_assets and
            fixture.get("shared_asset_count") == len(shared_assets) and
            fixture.get("prism_format") == "Rime::Prism/4.0" and
            isinstance(fixture.get("artifact_hashes"), dict) and
            fixture["artifact_hashes"] and
            all(re.fullmatch(r"[0-9a-f]{64}", value or "") for value in fixture["artifact_hashes"].values()),
            "Android execution must identify the disposable engine-deployed fixture")
    limits = summary.get("limits", {})
    require(all(limits.get(key) is False for key in
                ("art_verified", "ui_verified", "handset_installation_verified",
                 "phone_latency_verified", "all_27_dependencies_loaded")),
            "Android emulator/FakeJNI evidence must not claim handset/ART/UI/latency coverage")
    require(summary.get("harness_source") == "scripts/check-packaged-rime-touch-jni.cpp" and
            summary.get("harness_source_sha256") == sha256(root / summary["harness_source"]),
            "Actual Android test must use the frozen public JNI harness")
    for key in ("harness_source_sha256", "harness_executable_sha256", "emulator_sha256"):
        require(re.fullmatch(r"[0-9a-f]{64}", summary.get(key, "")),
                "Android runtime summary omits executable identity: " + key)
    return summary


def verify(args):
    root, work, output = args.root.resolve(), args.work.resolve(), args.output.resolve()
    head = git(root, "rev-parse", "HEAD").decode().strip()
    require(re.fullmatch(r"[0-9a-f]{40}", args.source_commit) and head == args.source_commit and
            git(root, "status", "--porcelain") == b"", "Final verification requires the clean frozen full source SHA")
    baseline = pinned_baseline(args.source_apk.resolve())
    probe, provenance, provenance_path = probe_provenance(root, args.probe_build.resolve())
    verify_rtti_imports(root, baseline, probe)
    manifest = json.loads((work / "prebuilt-manifest.json").read_text())
    require(manifest.get("schema") == 7 and manifest.get("base_commit") == BASE_COMMIT and
            manifest.get("source_commit") == head and manifest.get("source_checkout_dirty") is False and
            manifest.get("source_apk_sha256") == BASE_APK_SHA256 and
            manifest.get("source_apk_bytes") == BASE_APK_BYTES and
            manifest.get("counts") == common.EXPECTED_COUNTS and manifest.get("added_files") == [] and
            manifest.get("changed_native") == [] and
            manifest.get("probe_sha256") == sha256(probe) and manifest.get("librime_sha256") == RIME_SHA256 and
            manifest.get("runtime_version") == RIME_VERSION and
            manifest.get("lua_sha256") == sha256(root / LUA_SOURCE), "Rerun prepare from frozen source/native inputs")
    require(manifest.get("compile_jar") == {"path": "app/libs/sherpa-onnx-1.13.8.jar",
            "sha256": sha256(root / "app/libs/sherpa-onnx-1.13.8.jar")}, "Speech compile JAR changed")
    expected = {record["path"]: record for record in manifest["files"]}
    old_records = {record["path"]: record for record in manifest["base_files"]}
    require(len(expected) == len(manifest["files"]) == 378 and set(old_records) == set(expected) and
            len(old_records) == len(manifest["base_files"]), "Prepared manifest has invalid membership")
    apks = sorted((root / "app/build/outputs/apk/debug").glob("*.apk"))
    require(len(apks) == 1, "Expected one current debug APK")
    apk = apks[0]
    sdk = args.sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    require(sdk, "Actual Android SDK is required")
    tools = Path(sdk).resolve() / "build-tools/36.1.0"
    subprocess.run([sys.executable, "-B", str(root / "scripts/verify-xuancai-rime-package.py"), str(apk),
                    "--bundled-rime", "--offline-dictation", "--aapt", str(tools / "aapt"),
                    "--apksigner", str(tools / "apksigner"), "--expected-main-package", PACKAGE,
                    "--expected-app-label", LABEL, "--expected-version-name", VERSION_NAME,
                    "--expected-version-code", str(VERSION_CODE)], check=True)
    signing = subprocess.check_output([str(tools / "apksigner"), "verify", "--print-certs", str(apk)], text=True)
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", signing, re.MULTILINE)
    require([digest.lower() for digest in certificates] == [SIGNER], "Durable test signature differs")
    subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True)
    permissions = subprocess.check_output([str(tools / "aapt"), "dump", "permissions", str(apk)], text=True)
    require("android.permission.INTERNET" not in permissions, "Unexpected INTERNET permission")
    with zipfile.ZipFile(baseline) as before, zipfile.ZipFile(apk) as after:
        names, counts = common.archive_inputs(after)
        require(set(names) == set(expected), "Packaged engine/data membership changed")
        allowed = common.lua_overlay(before, (root / LUA_SOURCE).read_bytes())
        allowed[PROBE_ASSET] = probe.read_bytes()
        changed = []
        for name in names:
            previous, current = before.read(name), after.read(name)
            require(old_records[name] == {"path": name, "bytes": len(previous),
                    "sha256": hashlib.sha256(previous).hexdigest()}, "Baseline provenance differs: " + name)
            require(expected[name] == {"path": name, "bytes": len(current),
                    "sha256": hashlib.sha256(current).hexdigest()} and current == allowed.get(name, previous),
                    "Packaged bytes differ from reviewed build inputs: " + name)
            if current != previous:
                changed.append(name)
        changed_assets = sorted(name for name in changed if name.startswith("assets/"))
        require(changed_assets == manifest["changed_assets"] and
                changed == [] and changed_assets == [], "Asset/native delta differs")
        descriptor = json.loads(after.read(common.DESCRIPTOR_ASSET))
    marker = common.compiled_source(apk)
    require(marker["value"] == head, "Actual DEX marker differs from frozen source")
    version_raw = subprocess.check_output([sys.executable, "-B", str(root / "scripts/verify-packaged-rime-version.py"),
                                          str(apk), "--expected-library-sha256", RIME_SHA256])
    packaged_version = public_json(version_raw, "Packaged runtime evidence")
    tests, reports = common.unit_test_summary(root, args.expected_tests)
    native = native_test_summary(args, root, head, apk)
    runtime = runtime_test_summary(args, root, head, apk, probe)
    patch, patch_manifest = source_patch(root, head)
    snapshot = {"branch": git(root, "branch", "--show-current").decode().strip(), "commit": head,
                "tree": git(root, "rev-parse", "HEAD^{tree}").decode().strip(), "compiled_marker": marker,
                "compiled_source_is_fixed_commit": True, "private_logs_included": False,
                "url": "https://github.com/xisungod/fcitx5-android/tree/" + head}
    verification = {
        "schema": 7, "package": PACKAGE, "label": LABEL, "version_name": VERSION_NAME,
        "version_code": VERSION_CODE, "abi": "arm64-v8a", "base_commit": BASE_COMMIT,
        "compiled_source_commit": head, "compiled_source_marker": marker,
        "source_checkout_head": head, "source_checkout_dirty": False,
        "source_apk_sha256": BASE_APK_SHA256, "apk_sha256": sha256(apk), "apk_bytes": apk.stat().st_size,
        "asset_count": counts["assets"], "native_count": counts["lib"],
        "changed_assets": changed_assets, "changed_native": [], "added_assets_or_natives": [],
        "all_28_native_identical_to_touch_6": True,
        "all_350_assets_identical_to_touch_6": True,
        "unchanged_asset_count": 350 - len(changed_assets),
        "packaged_rime_version": RIME_VERSION, "packaged_librime_sha256": RIME_SHA256,
        "packaged_probe_sha256": sha256(probe), "native_probe_provenance_sha256": sha256(provenance_path),
        "actual_elf_rtti_imports_verified": True,
        "packaged_correction_lua_sha256": manifest["lua_sha256"],
        "deployment_descriptor_updated": bool(changed_assets),
        "deployment_descriptor_aggregate_sha256": descriptor["sha256"],
        "signature_verified": True, "signature_certificate_sha256": SIGNER, "zip_alignment_verified": True,
        "signing_note": "Same durable touch.2–touch.6 test signer/package and higher version code.",
        "network_permission": False, "new_neural_model_included": False,
        "default_experimental_switches": defaults(root), "automatic_personalization_training_added": False,
        "explicit_prompt_calibration_added": True,
        "personalization_requires_qualified_profile": True,
        "independent_rime_worker_thread_added": False,
        "unit_test_classes": tests["classes"], "unit_tests": tests["tests"],
        "unit_test_failures": tests["failures"], "unit_test_errors": tests["errors"], "unit_test_skipped": tests["skipped"],
        "native_regression_tests": native["tests"]["cases"], "native_regression_failures": native["tests"]["failures"],
        "native_regression_errors": native["tests"]["errors"], "native_regression_skipped": native["tests"]["skipped"],
        "native_regression_host_rime_version": native["host_rime"]["engine_version"],
        "native_regression_host_rime_sha256": native["host_rime"]["library_sha256"],
        "native_regression_summary_sha256": sha256(args.native_summary),
        "actual_android_runtime_smoke_passed": True,
        "actual_android_runtime_smoke_summary_sha256": sha256(args.runtime_summary),
        "actual_android_runtime_smoke_queries": runtime["evidence"]["queries"],
        "actual_android_runtime_smoke_scope": "qemu_android_bionic_actual_shared_JNI_with_FakeJNI_not_ART_or_handset_UI",
        "native_regression_scope": "public_synthetic_host_rime_and_packaged_ELF_checks_not_handset_accuracy_or_latency",
        "device_installation_or_launch_verified": False, "phone_accuracy_or_latency_verified": False,
        "release_kind": "touch_metrics_probe_recovery_and_candidate_ui_prerelease", "publication_performed": False,
    }
    require(not output.exists() or not any(output.iterdir()), "Use an empty output directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="touch7-delivery-", dir=output.parent) as temporary:
        staging = Path(temporary)
        shutil.copyfile(apk, staging / APK_NAME)
        shutil.copyfile(work / "prebuilt-manifest.json", staging / "prebuilt-manifest.json")
        shutil.copyfile(root / "docs/axiang/touch7.md", staging / "GUIDE.zh-CN.md")
        shutil.copyfile(args.native_summary, staging / "native-regression-summary.json")
        shutil.copyfile(args.runtime_summary, staging / "runtime-smoke-summary.json")
        shutil.copyfile(provenance_path, staging / "native-probe-provenance.json")
        write_json(staging / "packaged-rime-version.json", packaged_version)
        write_json(staging / "build-verification.json", verification)
        write_json(staging / "unit-test-summary.json", tests)
        write_json(staging / "source-patch-manifest.json", patch_manifest)
        write_json(staging / "source-snapshot.json", snapshot)
        (staging / "typing-test-source.patch").write_bytes(patch)
        with zipfile.ZipFile(staging / "full-junit-reports.zip", "w", zipfile.ZIP_DEFLATED) as archive:
            for report in reports:
                archive.write(report, report.name)
        (staging / "SHA256SUMS.txt").write_text("".join(f"{sha256(path)}  {path.name}\n"
                                                    for path in sorted(staging.iterdir())), encoding="utf-8")
        require({path.name for path in staging.iterdir()} == PUBLIC_FILES, "Public artifact whitelist differs")
        require(git(root, "rev-parse", "HEAD").decode().strip() == head and git(root, "status", "--porcelain") == b"",
                "Source changed during final verification")
        if output.exists():
            output.rmdir()
        staging.rename(output)
    print(json.dumps(verification, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for action, handler in (("prepare", prepare), ("verify", verify)):
        command = commands.add_parser(action)
        command.add_argument("--root", type=Path, default=Path.cwd())
        command.add_argument("--work", type=Path, required=True)
        command.add_argument("--source-apk", type=Path, required=True)
        command.add_argument("--probe-build", type=Path, required=True)
        if action == "prepare":
            command.add_argument("--reuse-prebuilt", type=Path,
                                 help="Read-only reuse after checking every pinned asset/native byte")
        if action == "verify":
            command.add_argument("--output", type=Path, required=True)
            command.add_argument("--sdk", type=Path)
            command.add_argument("--source-commit", required=True)
            command.add_argument("--expected-tests", type=int)
            command.add_argument("--native-summary", type=Path, required=True)
            command.add_argument("--native-rime-library", type=Path, required=True)
            command.add_argument("--runtime-summary", type=Path, required=True)
        command.set_defaults(handler=handler)
    args = parser.parse_args()
    try:
        args.handler(args)
    except (ValueError, KeyError, OSError, UnicodeError, zipfile.BadZipFile, struct.error,
            common.ET.ParseError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Touch.7 delivery verification failed: {error}\n")


if __name__ == "__main__":
    main()
