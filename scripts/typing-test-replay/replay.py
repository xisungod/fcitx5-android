#!/usr/bin/env python3
"""Replay touch.3/touch.4 test reports through actual pure production Kotlin sources.

No engine/session or user dictionary is opened. Targets are evaluation labels
only, removed before the JVM sees the input. Output contains private input text;
keep it outside the repository. Timings describe this host, not Android latency.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
TYPING = Path("app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/typing")
SOURCES = ("PinyinMultiPathTracker.kt", "PinyinSpatialKeyDecider.kt", "PinyinTapEvidence.kt",
           "PinyinTouchLanguageModel.kt")
OPTIONAL_SOURCES = ("PinyinMultiPathSearch.kt", "PinyinSyllableInventory.kt")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def finite(value, name):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"{name} must be a finite number")
    return value


def load_report(path):
    doc = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(doc, dict) or doc.get("format") not in {"axiang-typing-test-v1", "axiang-typing-test-v2"}:
        raise ValueError("Expected AXiang typing-test-v1/v2 JSON report")
    trials, layouts = doc.get("trials"), doc.get("layouts")
    if not isinstance(trials, list) or not 1 <= len(trials) <= 100 or not isinstance(layouts, dict):
        raise ValueError("Report needs 1–100 trials and layout definitions")
    normalized, skipped = [], []
    for ordinal, trial in enumerate(trials):
        if not isinstance(trial, dict):
            raise ValueError("Trial must be an object")
        touches, raw = trial.get("first_attempt_touches"), trial.get("first_attempt_pinyin")
        if not isinstance(raw, str) or not re.fullmatch(r"[a-z]{1,64}", raw):
            skipped.append({"ordinal": ordinal, "reason": "unsupported_first_attempt"})
            continue
        if not isinstance(touches, list) or len(touches) != len(raw):
            skipped.append({"ordinal": ordinal, "reason": "touch_count_mismatch"})
            continue
        taps = []
        for index, tap in enumerate(touches):
            if not isinstance(tap, dict) or tap.get("original") != raw[index]:
                raise ValueError(f"trial {ordinal}: touch spelling does not match raw")
            layout = layouts.get(tap.get("layout"))
            if not isinstance(layout, dict) or not isinstance(layout.get("cells"), list):
                raise ValueError(f"trial {ordinal}: missing geometry")
            cells, keys = [], set()
            for cell in layout["cells"]:
                if not isinstance(cell, dict) or not re.fullmatch(r"[a-z]", cell.get("key", "")):
                    raise ValueError("Invalid cell letter")
                if cell["key"] in keys:
                    raise ValueError("Duplicate key geometry")
                keys.add(cell["key"])
                fields = {name: finite(cell.get(name), name) for name in ("left", "top", "right", "bottom")}
                if fields["right"] <= fields["left"] or fields["bottom"] <= fields["top"]:
                    raise ValueError("Invalid cell dimensions")
                cells.append({"key": cell["key"], **fields})
            fields = {name: finite(tap.get(name), name) for name in ("down_x", "down_y", "density")}
            if fields["density"] <= 0:
                raise ValueError("Density must be positive")
            taps.append({"original": tap["original"], **fields, "cells": cells})
        # This allowlist is the entire JVM input. No targets, final text, learned
        # offsets, candidate rankings or calibration labels can reach the model.
        normalized.append({"ordinal": ordinal, "taps": taps})
    return doc, {"trials": normalized}, skipped


def jar(cache, group, artifact, version):
    matches = sorted((cache / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
    if len(matches) != 1:
        raise ValueError(f"Need cached Maven jar {group}:{artifact}:{version}; run Gradle dependency resolution first")
    return matches[0]


def compiler_tools(gradle_cache, java_override):
    cache = gradle_cache / "modules-2/files-2.1"
    versions = sorted((cache / "org.jetbrains.kotlin/kotlin-compiler-embeddable").glob("*"))
    versions = [p.name for p in versions if re.fullmatch(r"\d+\.\d+\.\d+", p.name)]
    if not versions:
        raise ValueError("No cached Kotlin compiler; run Gradle first")
    version = max(versions, key=lambda v: tuple(map(int, v.split("."))))
    runtime = jar(cache, "org.jetbrains.kotlin", "kotlin-stdlib", version)
    gson = jar(cache, "com.google.code.gson", "gson", "2.11.0")
    compiler = [jar(cache, "org.jetbrains.kotlin", artifact, version) for artifact in
                ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-script-runtime", "kotlin-daemon-embeddable")]
    compiler += [jar(cache, "org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
                 jar(cache, "org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0")]
    annotations = sorted((cache / "org.jetbrains/annotations").glob("*/*/annotations-*.jar"))
    if annotations:
        compiler.append(annotations[-1])
    java = str(java_override or shutil.which("java") or "")
    if not java:
        raise ValueError("Java executable required")
    return java, compiler, [runtime, gson], version


def run_source(source_root, model_path, normalized, work, cache, java, warmup, repeats, additional):
    source_files = [source_root / TYPING / name for name in SOURCES]
    source_files += [source_root / TYPING / name for name in OPTIONAL_SOURCES
                     if (source_root / TYPING / name).is_file()]
    source_files += [source_root / TYPING / name for name in additional
                     if source_root / TYPING / name not in source_files]
    if not all(path.is_file() for path in source_files):
        raise ValueError("Missing production source file")
    java, compiler, runtime, version = compiler_tools(cache, java)
    work.mkdir(parents=True, exist_ok=True)
    input_path, output_path, classes = work / "unlabelled-input.json", work / "jvm-output.json", work / "classes"
    input_path.write_text(json.dumps(normalized, separators=(",", ":")), encoding="utf-8")
    classes.mkdir(exist_ok=True)
    cp = os.pathsep.join(map(str, runtime))
    command = [java, "-cp", os.pathsep.join(map(str, compiler)),
               "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
               "-jvm-target", "17", "-classpath", cp, "-d", str(classes),
               *map(str, source_files), str(HERE / "ReplayMain.kt")]
    completed = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (work / "compile.log").write_text(completed.stdout, encoding="utf-8")
    if completed.returncode:
        raise ValueError(f"Kotlin compilation failed; inspect {work / 'compile.log'}")
    subprocess.run([java, "-cp", str(classes) + os.pathsep + cp,
                    "org.fcitx.fcitx5.android.tools.typingreplay.ReplayMainKt",
                    str(input_path), str(model_path), str(output_path), str(warmup), str(repeats)],
                   check=True)
    result = json.loads(output_path.read_text())
    result["production_sources_sha256"] = {str(p.relative_to(source_root)): digest(p) for p in source_files}
    result["model_sha256"] = digest(model_path)
    result["kotlin_compiler_version"] = version
    try:
        result["source_commit"] = subprocess.check_output(["git", "-C", str(source_root), "rev-parse", "HEAD"], text=True, stderr=subprocess.DEVNULL).strip()
        result["working_tree_modified"] = bool(subprocess.check_output(["git", "-C", str(source_root), "status", "--porcelain"], text=True, stderr=subprocess.DEVNULL).strip())
    except subprocess.CalledProcessError:
        result["source_commit"] = None
        result["working_tree_modified"] = None
    return result


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)] if values else None


def attach_evaluation(result, report):
    times, correct_trials, alternatives_on_correct, exact_target_alternatives = [], 0, 0, 0
    correct_intermediate_proposals, correct_intermediate_trials = 0, 0
    for trial in result["trials"]:
        source = report["trials"][trial["ordinal"]]
        trial["prompt_id"] = source.get("prompt_id")
        trial["known_target_pinyin"] = source.get("target_pinyin")
        trial["known_target_han"] = source.get("target")
        trial["first_attempt_complete"] = source.get("first_attempt_complete")
        trial["input_kind"] = source.get("input_kind")
        trial["recorded_candidate_snapshot"] = source.get("candidate_snapshot")
        trial["recorded_alternative_events"] = source.get("alternative_events", [])
        trial["candidate_snapshot_is_phone_recording"] = True
        raw_correct = trial["literal_spelling"] == trial["known_target_pinyin"]
        trial["literal_equals_known_target"] = raw_correct
        if raw_correct:
            correct_trials += 1
        for step in trial["steps"]:
            measured = step["record_ns"]
            step["record_median_ns"] = statistics.median(measured)
            step["record_p95_ns"] = percentile(measured, .95)
            times.append(step["record_median_ns"])
        intermediate = sum(bool(step["proposal"]) for step in trial["steps"][:-1])
        trial["intermediate_proposal_count"] = intermediate
        if raw_correct and intermediate:
            correct_intermediate_trials += 1
            correct_intermediate_proposals += intermediate
        final = trial["steps"][-1]["proposal"] if trial["steps"] else None
        trial["final_proposal_equals_known_target"] = bool(final and final["alternativeSpelling"] == trial["known_target_pinyin"])
        exact_target_alternatives += trial["final_proposal_equals_known_target"]
        alternatives_on_correct += bool(raw_correct and final)
    result["summary"] = {"replayed_trials": len(result["trials"]), "replayed_keys": len(times),
                         "literal_correct_trials": correct_trials,
                         "final_alternatives_on_literal_correct_trials": alternatives_on_correct,
                         "intermediate_alternatives_on_literal_correct_trials": correct_intermediate_proposals,
                         "literal_correct_trials_with_intermediate_alternatives": correct_intermediate_trials,
                         "final_alternative_equals_known_target_trials": exact_target_alternatives,
                         "host_per_key_median_of_repeats_p50_ns": percentile(times, .5),
                         "host_per_key_median_of_repeats_p95_ns": percentile(times, .95),
                         "host_per_key_median_of_repeats_max_ns": max(times, default=None)}
    return result


def compare_sources(baseline, current):
    old = {trial["ordinal"]: trial for trial in baseline["trials"]}
    comparison = []
    for trial in current["trials"]:
        prior = old[trial["ordinal"]]
        before = prior["steps"][-1] if prior["steps"] else {}
        after = trial["steps"][-1] if trial["steps"] else {}
        comparison.append({"prompt_id": trial["prompt_id"], "literal_equals_known_target": trial["literal_equals_known_target"],
                           "old_alternative": (before.get("proposal") or {}).get("alternativeSpelling"),
                           "new_alternative": (after.get("proposal") or {}).get("alternativeSpelling"),
                           "old_final_ns": before.get("record_median_ns"),
                           "new_final_ns": after.get("record_median_ns")})
    return comparison


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--baseline-root", type=Path)
    parser.add_argument("--model", type=Path, help="Frozen public model; same file used for both sources")
    parser.add_argument("--additional-source", action="append", default=[], help="Additional pure .kt filename in typing package")
    parser.add_argument("--baseline-additional-source", action="append", default=[])
    parser.add_argument("--gradle-cache", type=Path, default=Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches")
    parser.add_argument("--java", type=Path)
    parser.add_argument("--warmup", type=int, default=3)
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--work", type=Path, help="Private compiler/intermediate output directory")
    args = parser.parse_args(argv)
    if not 0 <= args.warmup <= 30 or not 1 <= args.repeats <= 100:
        parser.error("warmup must be 0–30; repeats 1–100")
    if args.output.exists():
        parser.error("Output must be a new file")
    report, unlabelled, skipped = load_report(args.report)
    if not unlabelled["trials"]:
        parser.error("No replayable trials")
    root = args.source_root.resolve()
    model = (args.model or root / "app/src/main/assets/typing/pinyin_touch_model.tsv").resolve()
    with tempfile.TemporaryDirectory(prefix="axiang-typing-replay-") as temporary:
        work = args.work or Path(temporary)
        current = attach_evaluation(run_source(root, model, unlabelled, work / "current", args.gradle_cache,
                                              args.java, args.warmup, args.repeats, args.additional_source), report)
        baseline = attach_evaluation(run_source(args.baseline_root.resolve(), model, unlabelled, work / "baseline",
                                               args.gradle_cache, args.java, args.warmup, args.repeats,
                                               args.baseline_additional_source), report) if args.baseline_root else None
    output = {"format": "axiang-pure-touch-replay-v1", "input_sha256": digest(args.report),
              "targets_sent_to_model": False, "automatic_training": False,
              "native_rime_queries": 0, "replay_kind": "pure_touch_and_spelling",
              "timing_scope": "host recordTap only; geometry, JSON, one-key explanation, native Rime and UI excluded",
              "warmup_passes_per_trial": args.warmup, "measured_passes_per_trial": args.repeats,
              "limitations": ["Report contains first-attempt DOWN positions only, not MOVE/UP or full later edits.",
                              "Sequentially supplied literal before/after assumes Rime accepts the recorded letters; no native session replay.",
                              "Recorded phone candidates are evidence only, not queried or scored by this tool.",
                              "A proposal score is uncalibrated; no phone accuracy, false-correction rate or latency claim."],
              "skipped": skipped, "current": current,
              **({"baseline": baseline, "comparison": compare_sources(baseline, current)} if baseline else {})}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    args.output.chmod(0o600)
    print(json.dumps({"output": str(args.output), "summary": current["summary"], "skipped": len(skipped)}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"typing-test-replay: {error}", file=sys.stderr)
        raise SystemExit(2)
