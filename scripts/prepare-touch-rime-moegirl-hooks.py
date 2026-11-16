#!/usr/bin/env python3
"""Precompile empty opt-in encyclopedia hooks without shipping third-party entries."""

from pathlib import Path
import argparse
import re
import shutil
import subprocess
import tempfile
import time

from rime_host_tools import ENGINE, SOURCES, build_deployer
from touch_rime_overlay import COMPILED, RIME, apply_overlay, file_hash, read_json, require, write_json

SCHEMAS = ("rime_ice.schema.yaml", "rime_ice_t9.schema.yaml")
COMPILED_HOOKS = {name for name in COMPILED if name.endswith((".prism.bin", ".schema.yaml"))}
PATCH = "axiang_moegirl.yaml"


def prepare(args):
    work = args.work.resolve()
    base_share = work / "prebuilt" / "assets" / RIME
    initial = read_json(base_share / "build/PREBUILT.json")
    require(initial["engine"] == ENGINE and initial["sources"] == SOURCES, "Staged Rime compiler provenance differs")
    deployer = args.deployer or build_deployer(work / "rime-host", args.workers)
    with tempfile.TemporaryDirectory(prefix="rime-schema-hooks-", dir=work) as directory:
        temp = Path(directory)
        shared, user = temp / "rime-data", temp / "user"
        shutil.copytree(base_share, shared, ignore=shutil.ignore_patterns("*.gram"))
        (shared / "opencc").symlink_to(base_share.parent / "opencc", target_is_directory=True)
        user.mkdir()

        fuzzy = "__patch: xuancai_mobile:/patch\n"
        full = shared / SCHEMAS[0]
        source = full.read_text(encoding="utf-8")
        require(source.count(fuzzy) == 1, "Existing fuzzy patch point differs")
        full.write_text(source.replace(fuzzy, "__patch: [xuancai_mobile:/patch, axiang_moegirl:/qwerty]\n"),
                        encoding="utf-8")
        nine_key = shared / SCHEMAS[1]
        source = nine_key.read_text(encoding="utf-8")
        require(not re.search("^__patch:", source, re.MULTILINE), "Unexpected T9 schema patch")
        nine_key.write_text(source + "\n__patch: axiang_moegirl:/t9\n", encoding="utf-8")
        patch = shared / PATCH
        shutil.copyfile(Path(__file__).with_name("rime") / PATCH, patch)

        compiled = shared / "build"
        # Keep the staged table so librime can reuse it while refreshing the configuration/prism CRCs.
        start = time.monotonic()
        subprocess.run([str(deployer.resolve()), "--build", str(user), str(shared), str(compiled)], check=True)
        require(file_hash(compiled / "rime_ice.table.bin") == initial["files_sha256"]["rime_ice.table.bin"],
                "Adding empty encyclopedia hooks must not recompile the shared dictionary")
        require(file_hash(compiled / "rime_ice.reverse.bin") == initial["files_sha256"]["rime_ice.reverse.bin"],
                "Adding empty encyclopedia hooks must not change the reverse lookup table")
        for name in COMPILED_HOOKS:
            path = compiled / name
            require(path.stat().st_size > 0, "Missing rebuilt Rime schema/prism: " + name)
            initial["files_sha256"][name] = file_hash(path)
        for name in SCHEMAS:
            result = compiled.joinpath(name).read_text(encoding="utf-8")
            require("axiang_moegirl" not in result, "The empty extension must not enable an encyclopedia dictionary")
            require(re.search(r"^  rime_version: 1\.12\.0\s*$", result, re.MULTILINE),
                    "The Rime schema was not compiled by librime 1.12.0")
        if "overlay" in initial:
            initial["overlay"]["inputs"]["rime_ice.schema.yaml"] = file_hash(full)
        hook_paths = SCHEMAS + (PATCH,)
        initial["moegirl_hooks"] = {name: file_hash(shared / name) for name in hook_paths}
        write_json(compiled / "PREBUILT.json", initial)
        apply_overlay(work, "moegirl-hooks-v1", {
            **{RIME + name: shared / name for name in hook_paths},
            **{RIME + "build/" + name: compiled / name for name in COMPILED_HOOKS | {"PREBUILT.json"}},
        })
        evidence = {"rime_version": ENGINE, "seconds": round(time.monotonic() - start, 3),
                    "retained_shared_table_sha256": initial["files_sha256"]["rime_ice.table.bin"]}
        write_json(work / "rime-moegirl-hooks.json", evidence)
        print("Precompiled opt-in encyclopedia hooks, with the shared table unchanged:", evidence)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", required=True, type=Path)
    parser.add_argument("--deployer", type=Path, help="Use a previously built pinned local host deployer")
    parser.add_argument("--workers", type=int, default=2)
    prepare(parser.parse_args())
