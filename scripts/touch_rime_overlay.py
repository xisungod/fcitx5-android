"""Seal explicit Rime-only overlays on the hash-verified touch package inputs."""

from __future__ import annotations

import hashlib
import json
import re
import shutil
from pathlib import Path, PurePosixPath

RIME = "usr/share/rime-data/"
GRAMMAR = "usr/share/licenses/rime-grammar/"
ZHWIKI_LICENSE = "usr/share/licenses/rime-zhwiki/"
ICE_SOURCE = "usr/share/licenses/rime-ice/SOURCE.json"
OLD_MODEL = RIME + "zh-hans-t-essay-bgw-compact.gram"
MODEL = RIME + "wanxiang-lts-zh-hans.gram"
ZHWIKI = RIME + "zhwiki.dict.yaml"
COMPILED = {
    "rime_ice.schema.yaml", "rime_ice.table.bin", "rime_ice.prism.bin",
    "rime_ice.reverse.bin", "rime_ice_t9.schema.yaml", "rime_ice_t9.prism.bin",
}
SOURCE_WHITELIST = {
    "single-edit-corrections-v1": (
        {RIME + "lua/axiang_typo_index.lua", RIME + "lua/xuancai_correction.lua"},
        set(),
        set(),
    ),
    "wanxiang-zhwiki-v1": (
        {RIME + "rime_ice.schema.yaml", RIME + "rime_ice.dict.yaml",
         RIME + "build/PREBUILT.json", GRAMMAR + "LICENSE", GRAMMAR + "SOURCE.json"}
        | {RIME + "build/" + name for name in COMPILED},
        {MODEL, ZHWIKI, GRAMMAR + "LICENSE-SCOPE.md", ZHWIKI_LICENSE + "SOURCE.json"},
        {OLD_MODEL},
    ),
    "moegirl-hooks-v1": (
        {RIME + "rime_ice.schema.yaml", RIME + "rime_ice_t9.schema.yaml", RIME + "build/PREBUILT.json"}
        | {RIME + "build/" + name for name in COMPILED if name.endswith((".prism.bin", ".schema.yaml"))},
        {RIME + "axiang_moegirl.yaml"},
        set(),
    ),
}
MANIFEST = "staged-inputs-manifest.json"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def file_hash(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def record(path, source):
    return {"path": path, "bytes": source.stat().st_size, "sha256": file_hash(source)}


def expected_inputs(work: Path, required_overlays=()):
    """Replay only the exact registered path sets over the immutable baseline."""
    base_path = work / "prebuilt-manifest.json"
    base = read_json(base_path)
    require(base["schema"] == "touch9-v1", "Unsupported baseline input manifest")
    baseline = {item["path"]: item for item in base["files"]}
    expected, history = dict(baseline), []
    if (work / MANIFEST).is_file():
        overlay_log = read_json(work / MANIFEST)
        require(overlay_log["baseline_sha256"] == file_hash(base_path), "Overlay baseline manifest hash mismatch")
        history = overlay_log["overlays"]
        seen = set()
        for item in history:
            name = item["kind"]
            require(name in SOURCE_WHITELIST and name not in seen, "Unknown or repeated overlay: " + name)
            seen.add(name)
            changed, added, removed = SOURCE_WHITELIST[name]
            changed = changed | {ICE_SOURCE, "descriptor.json"}
            all_paths = {"assets/" + path for path in changed | added | removed}
            require(item["before"] == {path: expected.get(path) for path in all_paths},
                    "Overlay did not start from the verified previous inputs: " + name)
            paths = {"assets/" + path for path in changed | added}
            records = {entry["path"]: entry for entry in item["files"]}
            require(set(records) == paths and len(records) == len(item["files"]), "Invalid overlay path set")
            for path, entry in records.items():
                require(re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]) and entry["bytes"] > 0,
                        "Invalid overlaid file measure: " + path)
            for path in removed:
                require(expected.pop("assets/" + path, None) is not None, "Missing removed input: " + path)
            expected.update(records)
    require(set(required_overlays) <= {item["kind"] for item in history}, "Required Rime data overlay was not built")
    return baseline, expected, history


def apply_overlay(work: Path, name: str, sources: dict[str, Path], *,
                  model_extension: dict | None = None, rows: dict | None = None):
    """Copy reviewed build/source files and refresh source and descriptor hashes together."""
    work = work.resolve()
    changed, added, removed = SOURCE_WHITELIST[name]
    require(set(sources) == changed | added, "Overlay exactly the declared files: " + name)
    _, before, history = expected_inputs(work)
    require(all(item["kind"] != name for item in history), "Use a fresh prepared work directory")
    stage = work / "prebuilt" / "assets"
    extra = {ICE_SOURCE, "descriptor.json"}
    all_paths = changed | added | removed | extra
    for path in all_paths:
        asset = stage / path
        old = before.get("assets/" + path)
        if path in added:
            require(old is None and not asset.exists(), "New overlay path already exists: " + path)
        else:
            require(old is not None and asset.is_file() and record("assets/" + path, asset) == old,
                    "Prepared baseline file changed: " + path)
    for path, source in sources.items():
        require(source.is_file() and source.resolve() != (stage / path).resolve(), "Missing or in-place overlay: " + path)

    rime_source = read_json(stage / ICE_SOURCE)
    descriptor = read_json(stage / "descriptor.json")
    for path, source in sources.items():
        dest = stage / path
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)
    for path in removed:
        (stage / path).unlink()
        descriptor["files"].pop(path)
        rime_source["files_sha256"].pop(path.removeprefix(RIME))

    def provenance_key(path):
        if path.startswith(GRAMMAR):
            return "grammar-license/" + path.removeprefix(GRAMMAR)
        if path.startswith(ZHWIKI_LICENSE):
            return "zhwiki-license/" + path.removeprefix(ZHWIKI_LICENSE)
        if path.startswith(RIME) and not path.startswith(RIME + "build/"):
            return path.removeprefix(RIME)
        return None

    records = [record("assets/" + path, stage / path) for path in sorted(sources)]
    for item in records:
        path, sha256 = item["path"].removeprefix("assets/"), item["sha256"]
        key = provenance_key(path)
        if key:
            rime_source["files_sha256"][key] = sha256
        descriptor["files"][path] = sha256
        for parent in PurePosixPath(path).parents:
            if parent != PurePosixPath("."):
                descriptor["files"].setdefault(str(parent), "")

    if model_extension is not None:
        rime_source["extensions"]["wanxiang_zhwiki"] = model_extension
    if rows:
        rime_source["raw_dictionary_rows"].update(rows)
    write_json(stage / ICE_SOURCE, rime_source)
    records.append(record("assets/" + ICE_SOURCE, stage / ICE_SOURCE))
    descriptor["files"][ICE_SOURCE] = records[-1]["sha256"]
    require(not (descriptor["files"].keys() & descriptor["symlinks"].keys()), "Overlay conflicts with an installed symlink")
    combined = {**descriptor["files"], **descriptor["symlinks"]}
    descriptor["sha256"] = hashlib.sha256(", ".join(path + value for path, value in combined.items()).encode()).hexdigest()
    write_json(stage / "descriptor.json", descriptor)
    records.append(record("assets/descriptor.json", stage / "descriptor.json"))

    history.append({"kind": name, "before": {"assets/" + path: before.get("assets/" + path) for path in sorted(all_paths)},
                    "files": records})
    write_json(work / MANIFEST, {"baseline_sha256": file_hash(work / "prebuilt-manifest.json"), "overlays": history})
