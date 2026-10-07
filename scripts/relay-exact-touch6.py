#!/usr/bin/env python3
"""Relay the already verified touch.6 files using unreferenced Git blobs.

prepare is strictly local. upload only creates immutable, unreferenced blobs;
it never writes a tree, commit, ref, workflow, release or signing credential.
restore only reads the blobs and reconstructs the pinned public files.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import time
import urllib.error
import urllib.request
import zipfile

REPO = "xisungod/fcitx5-android"
SOURCE = APK_BYTES = APK_HASH = RELEASE = TAG = TESTS = IDENTITY_HASH = None
APK = "AXiang-1.2-touch.6-arm64.apk"
SIGNER = "ffede124b18d54af5d4cfa9f3a32504fa2ccc898563bc4816bacbe3e46ec0494"
FILES = {
    APK, "SHA256SUMS.txt", "build-verification.json", "full-junit-reports.zip",
    "prebuilt-manifest.json", "native-regression-summary.json", "typing-test-source.patch", "source-patch-manifest.json",
    "source-snapshot.json", "unit-test-summary.json", "GUIDE.zh-CN.md",
    "native-probe-provenance.json", "packaged-rime-version.json", "runtime-smoke-summary.json",
}
IDENTITY = None
BASE_APK_HASH = "5452c34a20dc47c66f26377e05f86b231a900900019a0d5258f2b3f11d423a25"
BASE_COMMIT = "94fb1b14189f8e61f7ad81ecdfe18361a012f351"
RIME_VERSION = "1.16.1"
RIME_HASH = "e81472fd974a557e7233b0a0ca5659fa7da6c4a2eccb5a4a08c9d807d14c7159"
PROBE_ASSET = "lib/arm64-v8a/libaxiangtouch.so"
LUA_ASSET = "assets/usr/share/rime-data/lua/xuancai_correction.lua"
LUA_ASSETS = ["assets/descriptor.json", "assets/usr/share/licenses/rime-ice/SOURCE.json", LUA_ASSET]
ABI_HEADERS = {
    "rime/engine.h": "53533ea444716517b6ddcd2c4ac8880c7d5d8700c9ae9c40b3fc2ea18945e774",
    "rime/context.h": "d9bd9fe926f9ef6123f173841331e99d0d399b78408730c23ce6c2890f0b4dbc",
    "rime/gear/memory.h": "949118f744358dc7f4dd33ee2d3ec409ef0225042822ceb8045a76ecf7b10c9b",
    "rime/common.h": "b0671fc7a4d0e12812bdc10447bcb00d6d490fc8d42cebe3ebc5bafc313cf256",
}
ABI_FLAGS = ["--target=aarch64-linux-android23", "-std=c++17", "-fPIC", "-shared",
             "-fvisibility=default", "-DBOOST_DISABLE_CURRENT_LOCATION",
             "-DBOOST_ALL_NO_EMBEDDED_GDB_SCRIPTS", "-DGLOG_USE_GLOG_EXPORT", "-DGLOG_STATIC_DEFINE"]
RTTI_IMPORTS = [
    "_ZTIN4rime13ComponentBaseE",
    "_ZTIN4rime5ClassINS_6ConfigERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEEE9ComponentE",
    "_ZTIN4rime5ClassINS_10TranslatorERKNS_6TicketEE9ComponentE",
    "_ZTIN4rime10TranslatorE", "_ZTIN4rime6MemoryE",
]
COMPONENT_RTTI_PROOF = {
    "original_header_sha256": "5352aaa68d0de67f8ff9f7785015ba19eb567c548b25b4c4fe301c1f7652cb65",
    "bridge_header_sha256": "2b4c5dfde3a218257bf75d7cbd18deb3745953ef3af03151026ff1154564d2a5",
    "translator_original_header_sha256": "1f6ee3057fcb00be8899e2e6cf0cfeeed951f10f4afaf46a5627ebf05fbbf343",
    "translator_bridge_header_sha256": "c12f54c2c899dadf032e5ca10cde949ec78bfa23bd9b11a3b04a9d525b15ff07",
    "change": "ComponentBase and Translator inline defaulted destructors become external key-function declarations; unchanged Rime exports their existing destructors and RTTI",
    "main_library_modified": False, "required_rtti_symbols": RTTI_IMPORTS,
    "all_required_imported_from_runtime": True,
}


def set_identity(path, expected_hash):
    global IDENTITY, SOURCE, APK_BYTES, APK_HASH, RELEASE, TAG, TESTS, IDENTITY_HASH
    require(re.fullmatch(r"[0-9a-f]{64}", expected_hash) and digest(path) == expected_hash,
            "Pinned relay identity hash differs")
    data = json.loads(path.read_text())
    require(data.get("format") == "axiang-touch6-exact-delivery-identity-v1" and
            data.get("repository") == REPO and data.get("apk_name") == APK and
            data.get("signer_sha256") == SIGNER and data.get("version_code") == 6 and
            data.get("version_name") == "1.2-touch.6" and
            data.get("package") == "org.fcitx.fcitx5.android.axiang.touch2" and
            data.get("baseline_apk_sha256") == BASE_APK_HASH and
            re.fullmatch(r"[0-9a-f]{40}", data.get("source_commit", "")) and
            re.fullmatch(r"[0-9a-f]{64}", data.get("apk_sha256", "")) and
            isinstance(data.get("apk_bytes"), int) and 260000000 <= data["apk_bytes"] <= 300000000 and
            isinstance(data.get("release_id"), int) and data["release_id"] > 0 and
            data.get("release_tag") == "axiang-touch-1.2-touch.6" and
            isinstance(data.get("unit_tests"), int) and data["unit_tests"] >= 633 and
            data.get("contains_signing_key_or_user_logs") is False and
            set(data.get("public_files", {})) == FILES,
            "Pinned reviewed touch.6 identity is invalid")
    IDENTITY, IDENTITY_HASH = data, expected_hash
    SOURCE, APK_BYTES, APK_HASH = data["source_commit"], data["apk_bytes"], data["apk_sha256"]
    RELEASE, TAG, TESTS = data["release_id"], data["release_tag"], data["unit_tests"]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def git_sha(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.chmod(0o600)
    temporary.replace(path)


def api(method, route, payload=None, github_token=False):
    require(route.startswith("git/blobs"), "Only Git blob API operations are permitted")
    headers = {"Accept": "application/vnd.github+json", "Content-Type": "application/json",
               "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "AXiang-exact-public-APK-relay"}
    if github_token:
        token = os.environ.get("GH_TOKEN")
        require(bool(token), "Runner GH_TOKEN must be supplied")
        headers["Authorization"] = "Bearer " + token
    body = json.dumps(payload, separators=(",", ":")).encode() if payload is not None else None
    request = urllib.request.Request("https://api.github.com/repos/" + REPO + "/" + route,
                                     data=body, method=method, headers=headers)
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt == 2:
                raise RuntimeError(f"Git blob API {method} returned HTTP {error.code}") from None
        except urllib.error.URLError:
            if attempt == 2:
                raise RuntimeError("Git blob API transport failed") from None
        time.sleep(5 * (attempt + 1))


def public_json(path):
    raw = path.read_bytes()
    require(len(raw) < 2 * 1024 * 1024 and not re.search(
        rb"github_pat_[A-Za-z0-9_]{20,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|"
        rb"/tmp/codex-remote-attachments/|/workspace/|/home/|/private/|AXiang-(?:typing-test-\d{10,}|touch-diagnostics)", raw),
        "Public evidence includes credentials, local paths or private user report identity: " + path.name)
    return json.loads(raw)


def validate(folder):
    require(IDENTITY is not None, "Load the exact reviewed identity first")
    require({p.name for p in folder.iterdir()} == FILES,
            "Delivery folder must contain exactly the reviewed public file whitelist")
    for name in FILES:
        path = folder / name
        require(path.is_file() and not path.is_symlink(), "Public members must be regular files")
        record = IDENTITY["public_files"][name]
        require(path.stat().st_size == record["bytes"] and digest(path) == record["sha256"],
                "Exact reviewed public bytes differ: " + name)
    apk = folder / APK
    require(apk.stat().st_size == APK_BYTES and digest(apk) == APK_HASH, "Exact reviewed APK differs")
    listed = {}
    for line in (folder / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
        checksum, name = line.split("  ", 1)
        require(name in FILES - {"SHA256SUMS.txt"} and name not in listed and
                bool(re.fullmatch(r"[0-9a-f]{64}", checksum)) and digest(folder / name) == checksum,
                "Invalid public checksum member: " + name)
        listed[name] = checksum
    require(set(listed) == FILES - {"SHA256SUMS.txt"}, "Checksums must cover every public member")
    records = {name: public_json(folder / name) for name in FILES if name.endswith(".json")}
    verification = records["build-verification.json"]
    expected = {
        "schema": 6, "base_commit": BASE_COMMIT,
        "compiled_source_commit": SOURCE, "source_checkout_head": SOURCE, "source_checkout_dirty": False,
        "apk_sha256": APK_HASH, "apk_bytes": APK_BYTES,
        "signature_certificate_sha256": SIGNER, "signature_verified": True, "zip_alignment_verified": True,
        "package": "org.fcitx.fcitx5.android.axiang.touch2", "version_name": "1.2-touch.6", "version_code": 6,
        "source_apk_sha256": BASE_APK_HASH, "asset_count": 350, "native_count": 28,
        "changed_native": [PROBE_ASSET], "all_other_27_native_identical_to_touch_5": True,
        "all_other_assets_identical_to_touch_5": True,
        "added_assets_or_natives": [], "packaged_rime_version": RIME_VERSION, "packaged_librime_sha256": RIME_HASH,
        "network_permission": False, "new_neural_model_included": False,
        "automatic_personalization_training_added": False, "independent_rime_worker_thread_added": False,
        "unit_tests": TESTS, "unit_test_failures": 0, "unit_test_errors": 0, "unit_test_skipped": 0,
        "native_regression_failures": 0, "native_regression_errors": 0, "native_regression_skipped": 0,
        "native_regression_host_rime_version": RIME_VERSION,
        "device_installation_or_launch_verified": False, "phone_accuracy_or_latency_verified": False,
        "actual_android_runtime_smoke_passed": True,
        "actual_android_runtime_smoke_scope": "qemu_android_bionic_actual_shared_JNI_with_FakeJNI_not_ART_or_handset_UI",
        "actual_elf_rtti_imports_verified": True,
        "release_kind": "touch_probe_runtime_compatibility_prerelease", "publication_performed": False,
        "default_experimental_switches": {
            "pinyin_down_order": True, "pinyin_touch_alternatives": True, "diagnostic_logging": False,
            "pinyin_touch_personalization": False, "pinyin_touch_correction": False,
            "touch_boundary_settling": False},
    }
    for key, value in expected.items():
        require(verification.get(key) == value, "Reviewed verification differs: " + key)
    changed_assets = verification.get("changed_assets")
    require(changed_assets in ([], LUA_ASSETS) and
            verification.get("unchanged_asset_count") == 350 - len(changed_assets) and
            verification.get("deployment_descriptor_updated") == bool(changed_assets),
            "Only the optional exact Lua overlay and integrity records may alter assets")
    snapshot = records["source-snapshot.json"]
    require(verification.get("compiled_source_marker", {}).get("value") == SOURCE and
            snapshot.get("commit") == SOURCE and snapshot.get("compiled_marker", {}).get("value") == SOURCE and
            snapshot.get("compiled_source_is_fixed_commit") is True and snapshot.get("private_logs_included") is False,
            "Exact compiled/source snapshot marker differs")
    patch = records["source-patch-manifest.json"]
    require(patch.get("source_commit") == SOURCE and patch.get("base_commit") == BASE_COMMIT and
            patch.get("private_logs_prebuilts_and_generated_binaries_included") is False and
            patch.get("bytes") == (folder / "typing-test-source.patch").stat().st_size and
            patch.get("sha256") == digest(folder / "typing-test-source.patch"), "Public source patch differs")
    changed_sources = {record["path"]: record["sha256"] for record in patch.get("source_files", [])}
    require(len(changed_sources) == len(patch.get("source_files", [])) == len(patch.get("files", [])) and
            set(changed_sources) == set(patch.get("files", [])), "Public source patch file identities differ")
    summary = records["unit-test-summary.json"]
    require(summary["tests"] == TESTS and all(summary[k] == 0 for k in ("failures", "errors", "skipped")),
            "Full test summary differs")
    import xml.etree.ElementTree as ET
    with zipfile.ZipFile(folder / "full-junit-reports.zip") as archive:
        members = archive.namelist()
        require(len(members) == len(set(members)) == summary["classes"] == verification.get("unit_test_classes") and
                all(re.fullmatch(r"TEST-[^/]+\.xml", n) for n in members), "JUnit archive member set differs")
        totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
        for name in members:
            suite = ET.fromstring(archive.read(name))
            require(suite.tag == "testsuite", "JUnit archive root differs")
            for key in totals: totals[key] += int(suite.get(key, "0"))
        require(all(totals[k] == summary[k] for k in totals), "Actual full JUnit reports differ from summary")
    native_path = folder / "native-regression-summary.json"
    native = records[native_path.name]
    native_tests = native.get("tests", {})
    host = native.get("host_rime", {})
    require(native.get("schema") == 2 and native.get("source_commit") == SOURCE and
            native.get("source_working_tree_clean") is True and
            native.get("device_verification") is False and native.get("phone_latency_verified") is False and
            native_tests.get("cases") == verification.get("native_regression_tests") and native_tests.get("cases", 0) >= 63 and
            all(native_tests.get(k) == 0 for k in ("failures", "errors", "skipped")) and
            digest(native_path) == verification.get("native_regression_summary_sha256") and
            host.get("engine_version") == RIME_VERSION and
            host.get("library_sha256") == verification.get("native_regression_host_rime_sha256") and
            bool(re.fullmatch(r"[0-9a-f]{64}", host.get("library_sha256", ""))),
            "Frozen native 1.16.1 regression evidence differs")
    suites = native_tests.get("suites", {})
    require(isinstance(suites, dict) and {
            "correction_ranking", "adjacent_correction", "rime_smoke", "readonly_probe_isolation",
            "explicit_probe_candidate_commit"} <= set(suites),
            "Native engine, isolated probe and explicit native candidate commit suites are required")
    native_totals = {key: 0 for key in ("cases", "failures", "errors", "skipped")}
    for suite in suites.values():
        values = suite.get("tests", suite)
        require(isinstance(values.get("cases"), int) and values["cases"] > 0 and
                all(values.get(k) == 0 for k in ("failures", "errors", "skipped")), "Native suite failed")
        for key in native_totals: native_totals[key] += values[key]
    require(all(native_tests[k] == native_totals[k] for k in native_totals), "Native suite total differs")
    lua_digest = verification.get("packaged_correction_lua_sha256")
    require(native.get("lua_source") == {"path": "scripts/rime/xuancai_correction.lua", "sha256": lua_digest},
            "Native regressions tested a different packaged Lua")
    provenance_path = folder / "native-probe-provenance.json"
    provenance = records[provenance_path.name]
    expected_provenance = {
        "schema": 2, "abi": "arm64-v8a", "android_api": 23, "ndk_version": "28.0.13004108",
        "native_sha256": verification.get("packaged_probe_sha256"), "librime_sha256": RIME_HASH,
        "runtime_version": RIME_VERSION,
        "prebuilt_revision": "92ab7d6291a1fa426f199be917857ebb6054d08c",
        "prebuilder_revision": "71c6edbf9850209e8c36192ad97ccc55dd25573f",
        "librime_revision": "de4700e9f6b75b109910613df907965e3cbe0567",
        "libcxx_sha256": "b17919df195f29b0a238468c04171b93cedcdffbe0d30cdc634327cf7f7889bc",
        "generated_build_config_sha256": "b9fee5e534f12bbc050f776ca7b0837746df74a7e61f75b5b50105735ae5dd74",
        "abi_header_sha256": ABI_HEADERS, "abi_flags": ABI_FLAGS,
        "initializes_or_deploys_rime": False, "uses_rime_service_session": False,
        "probe_user_dictionary_enabled": False,
    }
    for key, value in expected_provenance.items():
        require(provenance.get(key) == value, "Probe provenance differs: " + key)
    require(digest(provenance_path) == verification.get("native_probe_provenance_sha256") and
            provenance.get("component_rtti_import") == COMPONENT_RTTI_PROOF and
            provenance.get("abi_patch", {}).get("sha256") == "fbbc68497ac908a01ce0f6344bf1c5d661b22bd520f0993463313c78f22354e3" and
            provenance.get("compiler", {}).get("sha256") == "315eee3085eef60d589d62c1c81d8525f31f55bf1416bc1a1abe8572a60cd372" and
            provenance.get("strip", {}).get("sha256") == "8a07cfa758258bba569776bb833aa079551bc0fd7e67972f3bd6e482a37f3722" and
            provenance.get("upstream_headers", {}).get("librime", {}).get("sha256") == "62816f834d938fe40bef86565116826319485c0585d3e2bb1fdd255e3a56308d" and
            provenance.get("upstream_headers", {}).get("boost", {}).get("sha256") == "aca59f889f0f32028ad88ba6764582b63c916ce5f77b31289ad19421a96c555f" and
            provenance.get("upstream_headers", {}).get("boost", {}).get("version") == "1.90.0" and
            provenance.get("upstream_headers", {}).get("marisa", {}).get("sha256") == "c24516edc43be8049ef4e23e50a574d4670036fe3595c49c0f01d4d87ce58f57" and
            provenance.get("upstream_headers", {}).get("marisa", {}).get("revision") == "3e87d53b78e15f2f43783d5e376561a8c9722051" and
            provenance.get("upstream_headers", {}).get("glog", {}).get("prebuilt_revision") == expected_provenance["prebuilt_revision"],
            "Actual runtime ABI/compiler/header provenance differs")
    source_proofs = provenance.get("source_files_sha256", {})
    require({"app/src/main/cpp/typing/rime-touch-probe.h", "app/src/main/cpp/typing/rime-touch-probe.cpp",
             "app/src/main/cpp/typing/rime-touch-jni.cpp", "scripts/build-rime-touch-probe.py"} <= set(source_proofs),
            "Probe provenance omits required source identities")
    for proof in (source_proofs, native.get("test_sources_sha256", {})):
        require(isinstance(proof, dict) and proof, "Native source proof must be a nonempty map")
        for name, checksum in proof.items():
            require(not name.startswith("/") and ".." not in Path(name).parts and "\\" not in name and
                    bool(re.fullmatch(r"[0-9a-f]{64}", checksum or "")), "Native source identity is invalid")
            if name in changed_sources:
                require(changed_sources[name] == checksum, "Frozen public source and native proof differ: " + name)
    packaged = records["packaged-rime-version.json"]
    require(packaged.get("schema_version") == 1 and packaged.get("verified") is True and
            packaged.get("apk_sha256") == APK_HASH and packaged.get("apk_size_bytes") == APK_BYTES and
            packaged.get("verification_method") == "pinned_elf_symbols_instructions_and_api_relocation" and
            packaged.get("executed_native_code") is False and packaged.get("expected_version") == RIME_VERSION and
            packaged.get("expected_library_sha256") == RIME_HASH and
            packaged.get("library", {}).get("sha256") == RIME_HASH and
            packaged.get("library", {}).get("version") == RIME_VERSION and
            packaged.get("library", {}).get("api_binding", {}).get("verified") is True,
            "Packaged actual Rime API binding audit differs")
    prepared = records["prebuilt-manifest.json"]
    require(prepared.get("schema") == 6 and prepared.get("base_commit") == BASE_COMMIT and
            prepared.get("source_commit") == SOURCE and prepared.get("source_checkout_dirty") is False and
            prepared.get("source_apk_sha256") == BASE_APK_HASH and prepared.get("source_apk_bytes") == 268230558 and
            prepared.get("added_files") == [] and prepared.get("changed_native") == [PROBE_ASSET] and
            prepared.get("changed_assets") == changed_assets and prepared.get("probe_sha256") == expected_provenance["native_sha256"] and
            prepared.get("librime_sha256") == RIME_HASH and prepared.get("runtime_version") == RIME_VERSION and
            prepared.get("lua_sha256") == lua_digest, "Prepared baseline/native provenance differs")
    expected_members = {record["path"]: record for record in prepared["files"]}
    baseline_members = {record["path"]: record for record in prepared["base_files"]}
    require(len(expected_members) == len(prepared["files"]) == len(baseline_members) == len(prepared["base_files"]) == 378 and
            set(expected_members) == set(baseline_members), "Prepared native/assets member count differs")
    actual_delta = []
    with zipfile.ZipFile(apk) as archive:
        actual = {name for name in archive.namelist() if name.startswith(("assets/", "lib/")) and not name.endswith("/")}
        require(actual == set(expected_members), "APK assets/native member set differs")
        for name, record in expected_members.items():
            data = archive.read(name)
            require(len(data) == record["bytes"] and hashlib.sha256(data).hexdigest() == record["sha256"],
                    "APK packaged data differs: " + name)
            if record != baseline_members[name]: actual_delta.append(name)
        require(set(actual_delta) == set(changed_assets) | {PROBE_ASSET}, "APK native/asset delta differs")
        require(hashlib.sha256(archive.read(LUA_ASSET)).hexdigest() == lua_digest and
                hashlib.sha256(archive.read("lib/arm64-v8a/librime.so")).hexdigest() == RIME_HASH and
                hashlib.sha256(archive.read("lib/arm64-v8a/libc++_shared.so")).hexdigest() == provenance["libcxx_sha256"],
                "Packaged correction/runtime C++ ABI bytes differ")
        require(set(native.get("schemas_sha256", {})) == {"assets/usr/share/rime-data/rime_ice.schema.yaml",
                "assets/usr/share/rime-data/build/rime_ice.schema.yaml"}, "Native test schema identities differ")
        for name, checksum in native["schemas_sha256"].items():
            require(hashlib.sha256(archive.read(name)).hexdigest() == checksum, "Native regression schema differs")
        probe = archive.read(PROBE_ASSET)
        require(len(probe) == provenance.get("native_bytes") and hashlib.sha256(probe).hexdigest() == provenance["native_sha256"],
                "Actual packaged probe differs from compiled evidence")
        import struct
        require(probe[:6] == b"\x7fELF\x02\x01" and struct.unpack_from("<H", probe, 18)[0] == 183,
                "Probe must be AArch64 ELF64")
        offset = struct.unpack_from("<Q", probe, 32)[0]
        entry_size, count = struct.unpack_from("<HH", probe, 54)
        alignments = [struct.unpack_from("<Q", probe, offset + index * entry_size + 48)[0]
                      for index in range(count) if struct.unpack_from("<I", probe, offset + index * entry_size)[0] == 1]
        require(alignments and min(alignments) >= 16384 and provenance.get("elf_load_alignment") == alignments,
                "Actual probe ELF load alignment differs")
        imported, exported = elf_dynamic_symbols(probe), elf_dynamic_symbols(archive.read("lib/arm64-v8a/librime.so"))
        for name in RTTI_IMPORTS:
            require(name in imported and imported[name][0] == 0 and
                    name in exported and exported[name][0] != 0 and exported[name][1] == 0,
                    "Actual probe must import the unchanged runtime's canonical RTTI: " + name)
    validate_runtime_summary(records["runtime-smoke-summary.json"], folder, verification, provenance)
    runtime = records["runtime-smoke-summary.json"]
    require(runtime.get("harness_source") == "scripts/check-packaged-rime-touch-jni.cpp" and
            changed_sources.get(runtime["harness_source"]) == runtime.get("harness_source_sha256"),
            "Android runtime proof must identify the exact frozen public JNI harness")
    return verification


def elf_dynamic_symbols(data):
    import struct
    require(data[:6] == b"\x7fELF\x02\x01" and len(data) >= 64, "Invalid dynamic-symbol ELF")
    offset = struct.unpack_from("<Q", data, 40)[0]
    entry_size, count = struct.unpack_from("<HH", data, 58)
    require(entry_size == 64 and count > 0 and offset + count * entry_size <= len(data), "Invalid ELF section table")
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, offset + i * entry_size) for i in range(count)]
    dynamic = [section for section in sections if section[1] == 11]
    require(len(dynamic) == 1, "Expected one dynamic ELF symbol table")
    table = dynamic[0]
    require(table[9] == 24 and table[5] % 24 == 0 and table[6] < len(sections) and
            table[4] + table[5] <= len(data), "Invalid dynamic ELF symbol entries")
    names = sections[table[6]]
    require(names[1] == 3 and names[4] + names[5] <= len(data), "Invalid ELF symbol string table")
    symbols = {}
    for at in range(table[4], table[4] + table[5], 24):
        name, info, visibility, section, _, _ = struct.unpack_from("<IBBHQQ", data, at)
        require(name < names[5], "Invalid ELF symbol name offset")
        end = data.find(b"\0", names[4] + name, names[4] + names[5])
        require(end >= 0, "Unterminated ELF symbol name")
        symbol = data[names[4] + name:end].decode("ascii")
        if symbol in RTTI_IMPORTS:
            require(symbol not in symbols, "Duplicate RTTI symbol in dynamic table")
            symbols[symbol] = (section, visibility & 3, info)
    return symbols


def validate_runtime_summary(runtime, folder, verification, provenance):
    require(runtime.get("schema") == 1 and runtime.get("source_commit") == SOURCE and
            runtime.get("source_checkout_dirty") is False and runtime.get("passed") is True and
            runtime.get("runtime") == "qemu-aarch64 with Android bionic; no phone UI" and
            runtime.get("engine_version") == RIME_VERSION and runtime.get("baseline_apk_sha256") == BASE_APK_HASH and
            runtime.get("native_sha256") == provenance["native_sha256"] and
            runtime.get("native_bytes") == provenance["native_bytes"] and
            digest(folder / "runtime-smoke-summary.json") == verification.get("actual_android_runtime_smoke_summary_sha256"),
            "Actual Android execution differs from the frozen source and packaged probe")
    require("draft_only" not in runtime, "Draft runtime evidence may not enter the delivery")
    with zipfile.ZipFile(folder / APK) as archive:
        dependencies = {Path(name).name: hashlib.sha256(archive.read(name)).hexdigest()
                        for name in archive.namelist()
                        if name.startswith("lib/") and name != PROBE_ASSET and not name.endswith("/")}
        shared_assets = {name.removeprefix("assets/usr/share/rime-data/"): hashlib.sha256(archive.read(name)).hexdigest()
                         for name in archive.namelist() if name.startswith("assets/usr/share/rime-data/") and not name.endswith("/")}
    require(len(dependencies) == 27 and runtime.get("dependency_hashes") == dependencies,
            "Android execution dependency provenance differs from actual APK")
    loaded = runtime.get("actually_loaded_dependency_hashes", {})
    require(isinstance(loaded, dict) and {"librime.so", "libc++_shared.so"} <= set(loaded) and
            set(loaded) <= set(dependencies) and all(dependencies[name] == checksum for name, checksum in loaded.items()),
            "Actual Android execution did not load the packaged Rime/C++ runtime")
    evidence = runtime.get("evidence", {})
    require(evidence.get("engine") == RIME_VERSION and evidence.get("jni_adapter") == "FakeJNI, no ART" and
            evidence.get("actual_shared_library") is True and
            evidence.get("create_succeeded") is True and
            isinstance(evidence.get("queries"), int) and evidence["queries"] > 0 and
            evidence["queries"] == verification.get("actual_android_runtime_smoke_queries") and
            isinstance(evidence.get("targets_found"), int) and evidence["targets_found"] == evidence["queries"] and
            isinstance(evidence.get("returned_candidates"), int) and
            evidence["queries"] <= evidence["returned_candidates"] <= evidence["queries"] * 3 and
            evidence.get("native_process_exit_code") == 0 and
            all(evidence.get(key) is True for key in
                ("wrong_thread_rejected", "budget_empty_is_ready", "raw_input_unchanged", "caret_unchanged",
                 "live_menu_unchanged", "user_files_unchanged", "no_commit", "owner_pthread_enforced",
                 "duplicate_create_rejected", "unsupported_schema_rejected", "invalid_input_rejected",
                 "oversize_context_rejected", "utf8_context_supported", "recreate_succeeded", "gaileme_literal_first")),
            "Actual shared JNI did not pass query/isolation checks")
    fixture = runtime.get("fixture", {})
    require(fixture.get("setup_deployed") is True and fixture.get("no_probe_deploy") is True and
            fixture.get("disposable") is True and fixture.get("synthetic_public_inputs_only") is True and
            fixture.get("shared_asset_hashes") == shared_assets and fixture.get("shared_asset_count") == len(shared_assets) and
            fixture.get("prism_format") == "Rime::Prism/4.0" and isinstance(fixture.get("artifact_hashes"), dict) and
            fixture["artifact_hashes"] and
            all(re.fullmatch(r"[0-9a-f]{64}", checksum or "") for checksum in fixture["artifact_hashes"].values()),
            "Actual Android execution omits the disposable engine-deployed fixture")
    limits = runtime.get("limits", {})
    require(all(limits.get(key) is False for key in
                ("art_verified", "ui_verified", "handset_installation_verified", "phone_latency_verified",
                 "all_27_dependencies_loaded", "jni_local_reference_management_verified")),
            "Emulator/FakeJNI evidence may not claim handset/ART/UI/latency coverage")
    for key in ("harness_source_sha256", "harness_executable_sha256", "emulator_sha256"):
        require(re.fullmatch(r"[0-9a-f]{64}", runtime.get(key, "")), "Android executable identity is missing: " + key)


def prepare(args):
    validate(args.folder)
    require(1024 * 1024 <= args.chunk_bytes <= 8 * 1024 * 1024, "Chunk size must be 1–8 MiB")
    args.work.mkdir(parents=True, exist_ok=True)
    metadata = args.work / "metadata.zip"
    if args.metadata:
        with zipfile.ZipFile(args.metadata) as archive:
            require(set(archive.namelist()) == FILES - {APK}, "Existing metadata members differ")
            for name in FILES - {APK}:
                require(archive.read(name) == (args.folder / name).read_bytes(), "Existing metadata bytes differ")
        if args.metadata.resolve() != metadata.resolve():
            shutil.copyfile(args.metadata, metadata)
    else:
        with zipfile.ZipFile(metadata, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name in sorted(FILES - {APK}):
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, (args.folder / name).read_bytes())
    records = []
    for path, kind in ((args.folder / APK, "apk"), (metadata, "metadata")):
        chunks = []
        with path.open("rb") as source:
            offset = 0
            while data := source.read(args.chunk_bytes):
                chunks.append({"offset": offset, "bytes": len(data), "git_blob_sha": git_sha(data),
                               "sha256": hashlib.sha256(data).hexdigest()})
                offset += len(data)
        records.append({"kind": kind, "name": path.name, "bytes": path.stat().st_size,
                        "sha256": digest(path), "chunks": chunks})
    manifest = {"format": "axiang-exact-public-git-blob-relay-v1", "repository": REPO,
                "source_commit": SOURCE, "release_id": RELEASE, "release_tag": TAG,
                "identity_sha256": IDENTITY_HASH,
                "apk_sha256": APK_HASH, "signer_sha256": SIGNER, "files": records,
                "public_files": sorted(FILES), "binary_objects_referenced_by_commit": False,
                "contains_signing_key_or_user_logs": False}
    save(args.work / "relay-manifest.json", manifest)
    expected_blobs = {c["git_blob_sha"] for r in records for c in r["chunks"]}
    require(set(args.existing_blob) <= expected_blobs, "Existing blob is not one of the exact prepared chunks")
    save(args.work / "relay-upload-state.json", {"uploaded_blob_shas": sorted(set(args.existing_blob)),
         "manifest_sha256": digest(args.work / "relay-manifest.json")})
    print(json.dumps({"external_writes": False, "chunks": sum(len(r["chunks"]) for r in records),
                      "manifest": str(args.work / "relay-manifest.json"),
                      "manifest_sha256": digest(args.work / "relay-manifest.json")}, ensure_ascii=False))


def upload(args):
    validate(args.folder)
    manifest_path = args.work / "relay-manifest.json"
    manifest = json.loads(manifest_path.read_text())
    state_path = args.work / "relay-upload-state.json"
    state = json.loads(state_path.read_text())
    require(state["manifest_sha256"] == digest(manifest_path), "Prepared relay manifest changed")
    uploaded = set(state["uploaded_blob_shas"])
    for record in manifest["files"]:
        path = args.folder / APK if record["kind"] == "apk" else args.work / "metadata.zip"
        require(path.stat().st_size == record["bytes"] and digest(path) == record["sha256"],
                "Prepared relay source differs")
        with path.open("rb") as source:
            for index, chunk in enumerate(record["chunks"]):
                source.seek(chunk["offset"])
                data = source.read(chunk["bytes"])
                require(git_sha(data) == chunk["git_blob_sha"] and
                        hashlib.sha256(data).hexdigest() == chunk["sha256"], "Prepared chunk differs")
                if chunk["git_blob_sha"] not in uploaded:
                    result = api("POST", "git/blobs", {"content": base64.b64encode(data).decode("ascii"),
                                                       "encoding": "base64"})
                    require(result["sha"] == chunk["git_blob_sha"], "Uploaded immutable blob differs")
                    uploaded.add(result["sha"])
                    state["uploaded_blob_shas"] = sorted(uploaded)
                    save(state_path, state)
                print(f"Ready {record['kind']} chunk {index + 1}/{len(record['chunks'])}", flush=True)
    print(json.dumps({"created_only_immutable_unreferenced_blobs": True,
                      "uploaded_blobs": len(uploaded), "manifest_sha256": digest(manifest_path)}))


def restore(args):
    require(digest(args.manifest) == args.manifest_sha256, "Pinned relay manifest differs")
    manifest = json.loads(args.manifest.read_text())
    require(manifest["format"] == "axiang-exact-public-git-blob-relay-v1" and
            manifest["repository"] == REPO and manifest["source_commit"] == SOURCE and
            manifest["identity_sha256"] == IDENTITY_HASH and
            manifest["release_id"] == RELEASE and manifest["release_tag"] == TAG and
            manifest["apk_sha256"] == APK_HASH and manifest["signer_sha256"] == SIGNER and
            set(manifest["public_files"]) == FILES and
            manifest["binary_objects_referenced_by_commit"] is False and
            manifest["contains_signing_key_or_user_logs"] is False,
            "Pinned relay identity differs")
    require(len(manifest["files"]) == 2 and {r["kind"] for r in manifest["files"]} == {"apk", "metadata"},
            "Only APK and metadata ZIP are valid relay payloads")
    require(not args.output.exists(), "Use a fresh restoration directory")
    args.output.mkdir(parents=True)
    metadata = args.output.parent / "relay-metadata.zip"
    for record in manifest["files"]:
        require(record["name"] == (APK if record["kind"] == "apk" else "metadata.zip"), "Invalid relay name")
        path = args.output / APK if record["kind"] == "apk" else metadata
        offset = 0
        with path.open("wb") as target:
            for chunk in record["chunks"]:
                require(chunk["offset"] == offset and 0 < chunk["bytes"] <= 8 * 1024 * 1024 and
                        bool(re.fullmatch(r"[0-9a-f]{40}", chunk["git_blob_sha"])), "Invalid relay chunk")
                blob = api("GET", "git/blobs/" + chunk["git_blob_sha"], github_token=args.github_token)
                require(blob["encoding"] == "base64" and blob["sha"] == chunk["git_blob_sha"] and
                        blob["size"] == chunk["bytes"], "Blob identity or size differs")
                data = base64.b64decode(blob["content"].replace("\n", ""), validate=True)
                require(len(data) == chunk["bytes"] and git_sha(data) == chunk["git_blob_sha"] and
                        hashlib.sha256(data).hexdigest() == chunk["sha256"], "Blob bytes differ")
                target.write(data)
                offset += len(data)
        require(offset == record["bytes"] and digest(path) == record["sha256"], "Reconstructed payload differs")
    with zipfile.ZipFile(metadata) as archive:
        require(set(archive.namelist()) == FILES - {APK} and len(archive.infolist()) == len(FILES) - 1,
                "Metadata ZIP members differ")
        for info in archive.infolist():
            require(not info.is_dir() and info.file_size < 32 * 1024 * 1024 and
                    not stat.S_ISLNK(info.external_attr >> 16), "Metadata ZIP member is unsafe")
            (args.output / info.filename).write_bytes(archive.read(info))
    metadata.unlink()
    validate(args.output)
    print(json.dumps({"restored_exact_previously_verified_apk": True, "apk_sha256": APK_HASH,
                      "apk_bytes": APK_BYTES, "compiled_source_commit": SOURCE,
                      "jvm_tests": TESTS,
                      "private_signing_material_transferred": False}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--identity", type=Path, required=True)
    parser.add_argument("--identity-sha256", required=True)
    sub = parser.add_subparsers(dest="mode", required=True)
    for command in ("prepare", "upload"):
        p = sub.add_parser(command)
        p.add_argument("--folder", type=Path, required=True)
        p.add_argument("--work", type=Path, required=True)
        if command == "prepare":
            p.add_argument("--chunk-bytes", type=int, default=8 * 1024 * 1024)
            p.add_argument("--metadata", type=Path)
            p.add_argument("--existing-blob", action="append", default=[])
    p = sub.add_parser("restore")
    p.add_argument("--manifest", type=Path, required=True)
    p.add_argument("--manifest-sha256", required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--github-token", action="store_true")
    args = parser.parse_args()
    set_identity(args.identity, args.identity_sha256)
    {"prepare": prepare, "upload": upload, "restore": restore}[args.mode](args)


if __name__ == "__main__":
    main()
