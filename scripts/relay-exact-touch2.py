#!/usr/bin/env python3
"""Relay the already verified touch.2 files using unreferenced Git blobs.

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
SOURCE = "7f2cc39d5fa882473774620ced8dae76abd15f77"
APK = "AXiang-1.2-touch.2-arm64.apk"
APK_BYTES = 268486892
APK_HASH = "a9118a03cbcbda659744bee4f433c1862c76ed950f5ca0b0efc3a0cd25080cbd"
SIGNER = "ffede124b18d54af5d4cfa9f3a32504fa2ccc898563bc4816bacbe3e46ec0494"
RELEASE = 404313433
TAG = "axiang-touch-1.2-touch.2"
FILES = {
    APK, "SHA256SUMS.txt", "build-verification.json", "full-junit-reports.zip",
    "model-provenance.json", "native-commit-verification.json",
    "native-host-verification.json", "native-provenance.json",
    "pinyin-touch-source.patch", "prebuilt-manifest.json",
    "python-test-summary.json", "source-patch-manifest.json",
    "source-snapshot.json", "unit-test-summary.json", "使用说明.md",
}


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


def validate(folder):
    require({p.name for p in folder.iterdir() if p.is_file()} == FILES,
            "The delivery folder must contain exactly the reviewed public file whitelist")
    for name in FILES:
        path = folder / name
        require(path.is_file() and not path.is_symlink(), "Public delivery members must be regular files")
    apk = folder / APK
    require(apk.stat().st_size == APK_BYTES and digest(apk) == APK_HASH, "Exact reviewed APK differs")
    listed = {}
    for line in (folder / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
        checksum, name = line.split("  ", 1)
        require(name in FILES - {"SHA256SUMS.txt"} and name not in listed and
                bool(re.fullmatch(r"[0-9a-f]{64}", checksum)), "Invalid checksum member")
        require(digest(folder / name) == checksum, "Public delivery file checksum differs: " + name)
        listed[name] = checksum
    require(set(listed) == FILES - {"SHA256SUMS.txt"}, "Checksums must cover all public members")
    verification = json.loads((folder / "build-verification.json").read_text())
    expected = {
        "compiled_source_commit": SOURCE, "source_checkout_head": SOURCE,
        "source_checkout_dirty": False, "apk_sha256": APK_HASH, "apk_bytes": APK_BYTES,
        "signature_certificate_sha256": SIGNER, "signature_verified": True,
        "zip_alignment_verified": True, "package": "org.fcitx.fcitx5.android.axiang.touch2",
        "version_name": "1.2-touch.2", "version_code": 2, "unit_tests": 526,
        "unit_test_failures": 0, "unit_test_errors": 0, "unit_test_skipped": 0,
        "python_tests": 49, "asset_count": 350, "native_count": 28,
        "all_27_native_and_349_baseline_assets_identical_to_stable_1_2": True,
        "minirbt_included": False, "network_permission": False,
        "default_experimental_switches": {
            "pinyin_down_order": True, "pinyin_touch_alternatives": True, "diagnostic_logging": False},
    }
    for key, value in expected.items():
        require(verification.get(key) == value, "Reviewed verification differs: " + key)
    require(json.loads((folder / "source-snapshot.json").read_text())["commit"] == SOURCE,
            "Source snapshot differs")
    for name, count in (("unit-test-summary.json", 526), ("python-test-summary.json", 49)):
        summary = json.loads((folder / name).read_text())
        require(summary["tests"] == count and all(summary[k] == 0 for k in ("failures", "errors", "skipped")),
                "Full test evidence differs")
    prepared = json.loads((folder / "prebuilt-manifest.json").read_text())
    expected_members = {r["path"]: r for r in prepared["files"] + prepared["added_files"]}
    with zipfile.ZipFile(apk) as archive:
        actual = {n for n in archive.namelist() if n.startswith(("assets/", "lib/")) and not n.endswith("/")}
        require(actual == set(expected_members), "APK assets/native member set differs")
        for name, record in expected_members.items():
            data = archive.read(name)
            require(len(data) == record["bytes"] and hashlib.sha256(data).hexdigest() == record["sha256"],
                    "APK packaged data differs: " + name)
    return verification


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
                      "jvm_tests": 526, "python_tests": 49,
                      "private_signing_material_transferred": False}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
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
    {"prepare": prepare, "upload": upload, "restore": restore}[args.mode](args)


if __name__ == "__main__":
    main()
