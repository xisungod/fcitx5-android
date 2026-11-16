#!/usr/bin/env python3
"""Build the pinned offline Wanxiang + zhwiki data overlay for touch test APKs."""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import tempfile
import time
from pathlib import Path
from urllib.request import Request, urlopen

from rime_host_tools import ENGINE, LUA, OCTAGRAM, SOURCES, build_deployer
from touch_rime_overlay import (
    COMPILED, GRAMMAR, MODEL, OLD_MODEL, RIME, ZHWIKI, ZHWIKI_LICENSE,
    apply_overlay, file_hash, read_json, require, write_json,
)

ROOT = Path(__file__).resolve().parents[1]
WANXIANG_LICENSE_REV = "7de2f3d0b4677d1e058eecdd48ac412d0314edf9"
WANXIANG_LICENSE_BASE = (
    "https://raw.githubusercontent.com/amzxyz/rime-wanxiang-data/" + WANXIANG_LICENSE_REV + "/"
)
MODEL_SPEC = {
    "project": "https://github.com/amzxyz/rime-wanxiang-data",
    "tag": "LTS",
    "release_asset_id": 747939897,
    "url": "https://github.com/amzxyz/rime-wanxiang-data/releases/download/LTS/wanxiang-lts-zh-hans.gram",
    "bytes": 407160876,
    "sha256": "d4f64356231764ff27b79c9b0db79cd9d665013464cf5544e2fbd9a71e0f1c5a",
    "license": "CC-BY-4.0",
    "license_revision": WANXIANG_LICENSE_REV,
}
ZHWIKI_SPEC = {
    "project": "https://github.com/felixonmars/fcitx5-pinyin-zhwiki",
    "tag": "0.3.1",
    "release_asset_id": 651457580,
    "url": "https://github.com/felixonmars/fcitx5-pinyin-zhwiki/releases/download/0.3.1/zhwiki-20261018.dict.yaml",
    "bytes": 57688941,
    "sha256": "5d8cc276aa9febb68ce1b95cfde07fdf2e21dfd2e89b8992f465aad95bed5241",
    "license": "CC-BY-SA-4.0",
    "license_url": "https://creativecommons.org/licenses/by-sa/4.0/",
    "attribution": "Chinese Wikipedia contributors, via felixonmars/fcitx5-pinyin-zhwiki 0.3.1.",
    "attribution_url": "https://zh.wikipedia.org/",
    "dump": "https://dumps.wikimedia.org/zhwiki/20261001/zhwiki-20261001-all-titles-in-ns0.gz",
    "dump_license": "https://dumps.wikimedia.org/legal.html",
    "changes": "The source table is unmodified; librime includes it in a precompiled lookup table.",
}
LICENSE_SPECS = {
    "LICENSE": {
        "url": WANXIANG_LICENSE_BASE + "LICENSE",
        "bytes": 18656,
        "sha256": "9e5f1b3c610b9c2da5c313bf81d577a7d1acec686bdb0384edefa6df0f90cd94",
    },
    "LICENSE-SCOPE.md": {
        "url": WANXIANG_LICENSE_BASE + "LICENSE-SCOPE.md",
        "bytes": 1196,
        "sha256": "41ba4ac41b6a28a117c172dd22fc8cf31ff3902f79737cc22e22b9412af624f4",
    },
}
GRAMMAR_CONFIG = {
    "language": "wanxiang-lts-zh-hans",
    "collocation_max_length": "8",
    "collocation_min_length": "2",
    "collocation_penalty": "-18",
    "non_collocation_penalty": "-8",
    "weak_collocation_penalty": "-100",
    "rear_penalty": "-20",
}
CONTEXT_CONFIG = {"contextual_suggestions": "true", "max_homophones": "7", "max_homographs": "7"}


def pinned_download(cache: Path, filename: str, spec: dict) -> Path:
    target = cache / filename
    cache.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        partial = target.with_suffix(target.suffix + ".partial")
        try:
            request = Request(spec["url"], headers={"User-Agent": "AXiang-rime-precompile/1"})
            with urlopen(request, timeout=120) as response, partial.open("wb") as output:
                shutil.copyfileobj(response, output)
            require(partial.stat().st_size == spec["bytes"] and file_hash(partial) == spec["sha256"],
                    "Pinned asset digest differs: " + filename)
            partial.replace(target)
        finally:
            partial.unlink(missing_ok=True)
    require(target.is_file() and target.stat().st_size == spec["bytes"] and file_hash(target) == spec["sha256"],
            "Cached asset digest differs: " + filename)
    return target


def replace_once(original: str, before: str, after: str):
    require(original.count(before) == 1, "Unexpected schema/dictionary baseline near: " + before)
    return original.replace(before, after, 1)


def section(yaml_text: str, name: str):
    match = re.search(rf"^{name}:\n((?:  [^\n]+\n)+)", yaml_text, re.MULTILINE)
    require(match is not None, "Missing Rime section: " + name)
    return match.group(1)


def require_keys(yaml_text: str, name: str, keys: dict):
    body = section(yaml_text, name)
    for key, value in keys.items():
        require(re.search(rf'^  {key}: (?:{re.escape(value)}|"{re.escape(value)}")\s*$', body, re.MULTILINE),
                f"Missing compiled Rime option {name}/{key}: {value}")


def validate_compiled(output: Path):
    require(all((output / name).stat().st_size > 0 for name in COMPILED), "Incomplete Rime precompile")
    for name in ("rime_ice.schema.yaml", "rime_ice_t9.schema.yaml"):
        compiled = (output / name).read_text(encoding="utf-8")
        require_keys(compiled, "__build_info", {"rime_version": ENGINE})
        require_keys(compiled, "grammar", GRAMMAR_CONFIG)
        require_keys(compiled, "translator", CONTEXT_CONFIG | {"dictionary": "rime_ice"})
    t9 = (output / "rime_ice_t9.schema.yaml").read_text(encoding="utf-8")
    require_keys(t9, "translator", {"prism": "rime_ice_t9", "user_dict": "rime_ice"})
    require_keys(t9, "speller", {"alphabet": "23456789"})
    require(not (output / "rime_ice_t9.table.bin").exists(), "Nine-key must reuse the precompiled Chinese table")


def count_rows(path):
    with path.open(encoding="utf-8") as source:
        for row in source:
            if row.strip() == "...":
                return sum(bool(line.strip()) and not line.startswith("#") for line in source)
    raise ValueError("Missing dictionary metadata delimiter")


def evaluate_sentences(deployer, old, new, compiled, temp, work):
    """Record real before/after candidates from the pinned engine and fresh user data."""
    lib = deployer.resolve().parents[1] / "lib"
    headers = deployer.resolve().parents[2] / "librime" / "src"
    def compile_probe(source):
        executable = temp / Path(source).stem
        subprocess.run([
            "cc", str(Path(__file__).with_name(source)), "-I" + str(headers),
            "-L" + str(lib), "-Wl,-rpath," + str(lib), "-lrime", "-o", str(executable),
        ], check=True)
        return executable

    probe = compile_probe("check-rime-lexicon-sentences.c")
    cases = [
        tuple(line.split("\t"))
        for line in Path(__file__).with_name("rime").joinpath("lexicon-sentences.tsv").read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#")
    ]
    require(len(cases) == 66, "Rime benchmark must run all 66 fixed cases")
    original = temp / "original-share"
    original.mkdir()
    for entry in old.iterdir():
        (original / entry.name).symlink_to(entry, target_is_directory=entry.is_dir())
    (original / "opencc").symlink_to(old.parent / "opencc", target_is_directory=True)
    result, summary = {}, {}
    for name, shared, build, model_name in (
        ("before", original, old / "build", Path(OLD_MODEL).name),
        ("after", new, compiled, Path(MODEL).name),
    ):
        user = temp / ("probe-" + name)
        user.mkdir()
        assessed = subprocess.run(
            [str(probe), str(shared), str(user), str(build), "rime_ice"],
            input="\n".join(pinyin for _, pinyin, _ in cases) + "\n",
            env={**os.environ, "RIME_AB_EXPECT_GRAMMAR": model_name}, capture_output=True,
            check=True, text=True, encoding="utf-8",
        )
        (work / f"rime-sentence-{name}.log").write_text(assessed.stderr, encoding="utf-8")
        lines = [line.split("\t") for line in assessed.stdout.splitlines()]
        require(len(lines) == len(cases), "Rime benchmark case count differs")
        candidates = []
        for (category, pinyin, expected), line in zip(cases, lines):
            require(line[0] == pinyin and len(line) >= 2, "No real Rime candidate for fixed case: " + pinyin)
            candidates.append({"category": category, "pinyin": pinyin, "expected": expected, "candidates": line[1:]})
        result[name] = candidates
        summary[name] = {
            "top1": sum(case["expected"] == case["candidates"][0] for case in candidates),
            "top5": sum(case["expected"] in case["candidates"] for case in candidates),
        }
    write_json(work / "rime-sentence-ab.json", {
        "cases": len(cases), "grammar": GRAMMAR_CONFIG, "translator": CONTEXT_CONFIG,
        "summary": summary, "results": result,
    })
    params = ", ".join(f"{key}={value}" for key, value in (GRAMMAR_CONFIG | CONTEXT_CONFIG).items())
    print(f"Rime after parameters: {params}")
    for index, (before, after) in enumerate(zip(result["before"], result["after"]), 1):
        expected = after["expected"]
        first = after["candidates"][0]
        status = "PASS" if first == expected else "MISS"
        print(f"Rime case {index:02d}/{len(cases)} [{after['category']}] {status}: "
              f"pinyin={after['pinyin']}; expected={expected}; before={before['candidates'][0]}; after={first}")
    print(f"Rime full-pinyin fixed fixtures: top1 {summary['before']['top1']}/{len(cases)} -> "
          f"{summary['after']['top1']}/{len(cases)}. Top5 {summary['before']['top5']}/{len(cases)} -> "
          f"{summary['after']['top5']}/{len(cases)}.")
    for (category, _, expected), before, after in zip(cases, result["before"], result["after"]):
        if category == "user":
            print(f"Long-sentence example: expected={expected}; before={before['candidates'][0]}; after={after['candidates'][0]}")
    nine_key = compile_probe("check-axiang-rime-t9.c")
    t9_user = temp / "probe-nine-key"
    t9_user.mkdir()
    t9_result = subprocess.run(
        [str(nine_key), str(new), str(t9_user), str(compiled)], check=True, capture_output=True, text=True, encoding="utf-8",
    )
    (work / "rime-sentence-nine-key.log").write_text(t9_result.stdout + t9_result.stderr, encoding="utf-8")
    print("Real nine-key Rime smoke checks passed with the rebuilt table and prism.")


def prepare(args):
    work = args.work.resolve()
    base_share = work / "prebuilt" / "assets" / RIME
    require(base_share.is_dir(), "Run pinned APK extraction before the Rime overlay")
    cache = (args.downloads or (work / "downloads" / "rime-overlay")).resolve()
    model = pinned_download(cache, Path(MODEL).name, MODEL_SPEC)
    wiki = pinned_download(cache, "zhwiki-20261018.dict.yaml", ZHWIKI_SPEC)
    license_files = {GRAMMAR + name: pinned_download(cache, name, spec) for name, spec in LICENSE_SPECS.items()}
    prebuilt_manifest = read_json(base_share / "build/PREBUILT.json")
    require(prebuilt_manifest["engine"] == ENGINE and prebuilt_manifest["sources"] == SOURCES,
            "Pinned host compiler differs from the bundled engine")
    total_started = time.monotonic()
    deployer = args.deployer or build_deployer((args.host_work or work / "rime-host").resolve(), args.workers)
    with tempfile.TemporaryDirectory(prefix="rime-overlay-", dir=work) as directory:
        temp = Path(directory)
        shared, user, prebuilt = temp / "rime-data", temp / "user", temp / "prebuilt"
        shutil.copytree(base_share, shared, ignore=shutil.ignore_patterns("build", "*.gram"))
        # Mirror the packaged OpenCC descriptor's symlink without copying its data.
        (shared / "opencc").symlink_to(base_share.parent / "opencc", target_is_directory=True)
        user.mkdir()
        prebuilt.mkdir()
        (shared / model.name).symlink_to(model)
        (shared / "zhwiki.dict.yaml").symlink_to(wiki)
        schema = shared / "rime_ice.schema.yaml"
        translator_before = section(schema.read_text(encoding="utf-8"), "translator")
        grammar = "grammar:\n" + "".join(f"  {key}: {value}\n" for key, value in GRAMMAR_CONFIG.items())
        schema.write_text(replace_once(schema.read_text(encoding="utf-8"),
                                       "grammar:\n  language: zh-hans-t-essay-bgw-compact\n", grammar),
                          encoding="utf-8")
        require(section(schema.read_text(encoding="utf-8"), "translator") == translator_before,
                "The overlay must not change the existing translator")
        dictionary = shared / "rime_ice.dict.yaml"
        old_table_line = "  - cn_dicts/others   # 一些杂项\n"
        dictionary.write_text(replace_once(dictionary.read_text(encoding="utf-8"), old_table_line,
                                           old_table_line + "  - zhwiki  # Encyclopedia terms; keep upstream Rime Ice weights first\n"),
                              encoding="utf-8")

        started = time.monotonic()
        subprocess.run([str(deployer.resolve()), "--build", str(user), str(shared), str(prebuilt)], check=True)
        seconds = time.monotonic() - started
        validate_compiled(prebuilt)
        require(file_hash(prebuilt / "rime_ice.table.bin") != prebuilt_manifest["files_sha256"]["rime_ice.table.bin"],
                "The host returned the old table without compiling the new dictionary")
        all_compiled = dict(prebuilt_manifest["files_sha256"])
        for name in sorted(COMPILED):
            all_compiled[name] = file_hash(prebuilt / name)
        compiled_metadata = {
            **prebuilt_manifest,
            "engine": ENGINE, "plugins": {"lua": LUA, "octagram": OCTAGRAM}, "sources": SOURCES,
            "files_sha256": all_compiled, "overlay": {"name": "wanxiang-zhwiki-v1", "inputs": {
                "rime_ice.schema.yaml": file_hash(schema), "rime_ice.dict.yaml": file_hash(dictionary),
                "zhwiki.dict.yaml": ZHWIKI_SPEC["sha256"], model.name: MODEL_SPEC["sha256"],
            }},
        }
        write_json(prebuilt / "PREBUILT.json", compiled_metadata)
        evaluate_sentences(deployer, base_share, shared, prebuilt, temp, work)
        model_source, wiki_source = temp / "model.json", temp / "zhwiki.json"
        write_json(model_source, MODEL_SPEC)
        write_json(wiki_source, ZHWIKI_SPEC)

        changes = {
            RIME + "rime_ice.schema.yaml": schema,
            RIME + "rime_ice.dict.yaml": dictionary,
            RIME + "build/PREBUILT.json": prebuilt / "PREBUILT.json",
            MODEL: model,
            ZHWIKI: wiki,
            GRAMMAR + "SOURCE.json": model_source,
            ZHWIKI_LICENSE + "SOURCE.json": wiki_source,
            **license_files,
            **{RIME + "build/" + name: prebuilt / name for name in COMPILED},
        }
        rows = count_rows(wiki)
        require(rows > 1700000, "Encyclopedia dictionary unexpectedly incomplete")
        old_model_size = (work / "prebuilt" / "assets" / OLD_MODEL).stat().st_size
        apply_overlay(work, "wanxiang-zhwiki-v1", changes, model_extension={
            "grammar": MODEL_SPEC, "zhwiki": ZHWIKI_SPEC,
        }, rows={"zhwiki": rows})
        evidence = {
            "engine": ENGINE,
            "precompile_seconds": round(seconds, 3),
            "all_preparation_seconds": round(time.monotonic() - total_started, 3),
            "grammar_bytes_before": old_model_size,
            "grammar_bytes_after": MODEL_SPEC["bytes"],
            "zhwiki_bytes": ZHWIKI_SPEC["bytes"],
            "zhwiki_raw_rows": rows,
            "shared_chinese_table_bytes": (prebuilt / "rime_ice.table.bin").stat().st_size,
        }
        write_json(work / "rime-precompile.json", evidence)
        print(f"Rime: precompiled both Pinyin schemas in {seconds:.1f}s "
              f"(native Android libraries unchanged); model {old_model_size:,} -> {MODEL_SPEC['bytes']:,} bytes, "
              f"zhwiki {rows:,} rows / {ZHWIKI_SPEC['bytes']:,} bytes.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", required=True, type=Path, help="Directory from prepare-axiang-touch9-build.py")
    parser.add_argument("--host-work", type=Path, help="Scratch directory for the pinned host compiler")
    parser.add_argument("--deployer", type=Path, help="Use an already built pinned deployer (local verification)")
    parser.add_argument("--downloads", type=Path, help="Optional cache of already downloaded, verified source files")
    parser.add_argument("--workers", type=int, default=2)
    prepare(parser.parse_args())
