#!/usr/bin/env python3
"""Fresh Android-bionic model pools and exact current/baseline Kotlin ranking.

No APK rebuilding, Gradle, user input logs, model learning, or handset timing.
The full gold set is scored only after the production source has been frozen.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
GOLD = "docs/axiang/prediction-continuation-benchmark.tsv"
ASSET = "app/src/main/assets/typing/next_word_completions.tsv"
KOTLIN = ["app/src/main/java/org/fcitx/fcitx5/android/core/LibimeNextWordPredictor.kt"] + [
    "app/src/main/java/org/fcitx/fcitx5/android/input/prediction/" + name for name in [
        "NextWordPredictionOffer.kt", "NextWordPredictionRuntime.kt",
        "NextWordCompletionIndex.kt", "NextWordSuggestionBackend.kt"]]
HELPERS = [GOLD, "scripts/check-axiang-prediction-benchmark.py",
           "scripts/evaluate-axiang-prediction-benchmark.py",
           "scripts/check-axiang-prediction-benchmark.cpp",
           "scripts/check-axiang-prediction-benchmark.kt",
           "scripts/test-axiang-prediction-benchmark.py"]


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def run(command: list, log: Path, cwd: Path) -> str:
    with log.open("w", encoding="utf-8") as stream:
        subprocess.run([str(part) for part in command], cwd=cwd, stdout=stream,
                       stderr=subprocess.STDOUT, check=True)
    return log.read_text(encoding="utf-8")


def verify_actual_inputs(args) -> dict:
    libs = args.prebuilt / "jniLibs/arm64-v8a"
    models = args.prebuilt / "assets/usr/share/libime"
    paths = [(args.bridge, "lib/arm64-v8a/libaxiangpredict.so")]
    paths += [(libs / name, "lib/arm64-v8a/" + name) for name in
              ["libIMECore.so", "libFcitx5Utils.so", "libc++_shared.so"]]
    paths += [(models / name, "assets/usr/share/libime/" + name) for name in
              ["zh_CN.lm", "zh_CN.lm.predict"]]
    records = {}
    with zipfile.ZipFile(args.baseline_apk) as apk:
        for path, entry in paths:
            with apk.open(entry) as stream:
                reference = hashlib.file_digest(stream, "sha256").hexdigest()
            actual = digest(path)
            size = path.stat().st_size
            if actual != reference or size != apk.getinfo(entry).file_size:
                raise ValueError("Executed input differs from actual baseline APK: " + entry)
            records[entry] = dict(bytes=size, sha256=actual)
    return records


def compile_and_query(args, gold: list[dict], sources_root: Path,
                      asset: Path, name: str, provenance: dict) -> dict:
    def jar(group: str, artifact: str, version: str) -> Path:
        values = list((args.gradle_cache / group / artifact / version).glob("*/*.jar"))
        if len(values) != 1:
            raise ValueError("Expected exactly one cached jar: " + artifact)
        return values[0]
    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.3.21")
    compiler = jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.3.21")
    reflect = jar("org.jetbrains.kotlin", "kotlin-reflect", "2.3.21")
    script = jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.3.21")
    annotations = jar("org.jetbrains", "annotations", "13.0")
    keep = jar("androidx.annotation", "annotation-jvm", "1.9.1")
    coroutines = jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2")
    compile_jars = [compiler, stdlib, reflect, script, coroutines, annotations]
    runtime_jars = [stdlib, keep, coroutines, annotations]
    compiler_classpath = os.pathsep.join(map(str, compile_jars))
    classpath = os.pathsep.join(map(str, runtime_jars))
    classes = args.work / (name + "-classes")
    sources = [sources_root / path for path in KOTLIN]
    sources.append(args.root / "scripts/check-axiang-prediction-benchmark.kt")
    run([args.java, "-Xmx512m", "-cp", compiler_classpath,
         "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
         "-jvm-target", "17", "-classpath", classpath, "-d", classes, *sources],
        args.work / (name + "-compile.log"), args.root)
    output = run([args.java, "-cp", str(classes) + os.pathsep + classpath,
                  "org.fcitx.fcitx5.android.input.prediction.Check_axiang_prediction_benchmarkKt",
                  args.root / GOLD, args.work / "native-pools.tsv", asset],
                 args.work / (name + "-query.log"), args.root)
    cases = []
    for line in output.splitlines():
        if line.startswith("PUBLIC_PREDICTION_BENCHMARK\t"):
            _, identity, context, values = line.split("\t")
            cases.append(dict(id=identity, context=context,
                              candidates=values.split(",") if values else []))
    if len(cases) != 200:
        raise ValueError("Exact Kotlin backend must query all 200 public contexts")
    result = dict(provenance=dict(**provenance, completion_asset_sha256=digest(asset),
        production_kotlin_sources_sha256={str(path.relative_to(sources_root)): digest(path)
                                          for path in sources[:-1]},
        compiler_jars_sha256={path.name: digest(path) for path in compile_jars + runtime_jars}),
        cases=cases)
    (args.work / (name + "-candidates.json")).write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--prebuilt", type=Path, required=True)
    parser.add_argument("--bridge", type=Path, required=True)
    parser.add_argument("--baseline-apk", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--android-runtime", type=Path, required=True)
    parser.add_argument("--qemu", type=Path, required=True)
    parser.add_argument("--java", type=Path,
                        default=Path("/workspace/toolchains/axiang/jdk17/jdk-17.0.20.1+1/bin/java"))
    parser.add_argument("--gradle-cache", type=Path,
                        default=Path("/home/agent/.gradle/caches/modules-2/files-2.1"))
    parser.add_argument("--baseline-commit", help="actual prior APK source commit")
    parser.add_argument("--source-commit", help="exact clean frozen current source commit")
    parser.add_argument("--native-only", action="store_true",
                        help="collect raw native pools before freezing without evaluating heldout ranking")
    args = parser.parse_args()
    args.root = args.root.resolve()
    args.work = args.work.resolve()
    args.work.mkdir(parents=True, exist_ok=True)
    if not args.native_only and (not args.source_commit or not args.baseline_commit):
        parser.error("full evaluation requires --source-commit and --baseline-commit")
    if args.work.is_relative_to(args.root):
        raise ValueError("Benchmark output must remain outside the frozen repository")

    def source_state() -> tuple[str, bool]:
        head = subprocess.check_output(["git", "-C", str(args.root), "rev-parse", "HEAD"], text=True).strip()
        dirty = bool(subprocess.check_output(["git", "-C", str(args.root), "status", "--porcelain"]))
        if args.source_commit and (head != args.source_commit or dirty):
            raise ValueError("Full benchmark requires exact clean frozen current source")
        return head, dirty

    state = source_state()
    validator = module(args.root / "scripts/check-axiang-prediction-benchmark.py", "axiang_gold")
    evaluator = module(args.root / "scripts/evaluate-axiang-prediction-benchmark.py", "axiang_score")
    gold, benchmark_sha = validator.load_benchmark(args.root / GOLD)
    evaluator.self_test(gold)
    contexts = args.work / "contexts.txt"
    contexts.write_text("".join(row["context"] + "\n" for row in gold), encoding="utf-8")
    inputs = verify_actual_inputs(args)
    source_hashes = {path: digest(args.root / path) for path in HELPERS + KOTLIN + [
        "app/src/main/cpp/axiangpredict.h", "app/src/main/cpp/axiangpredict-text.cpp"]}
    tool = args.ndk / "toolchains/llvm/prebuilt/linux-x86_64"
    libs = args.prebuilt / "jniLibs/arm64-v8a"
    main_model = args.prebuilt / "assets/usr/share/libime/zh_CN.lm"
    harness = args.work / "arm-benchmark-check"
    run([tool / "bin/clang++", "--target=aarch64-linux-android23",
         "--sysroot=" + str(tool / "sysroot"), "-std=c++20", "-O2", "-fPIE", "-pie",
         "-nostdlib++", "-I", args.root / "app/src/main/cpp",
         args.root / "scripts/check-axiang-prediction-benchmark.cpp",
         args.root / "app/src/main/cpp/axiangpredict-text.cpp", "-L", libs,
         "-lc++_shared", "-ldl", "-o", harness], args.work / "native-compile.log", args.root)
    search = ":".join(str(path.resolve()) for path in (libs, args.android_runtime / "lib64/bionic"))
    output = run([args.qemu, "-E", "LD_LIBRARY_PATH=" + search,
                  args.android_runtime / "bin/linker64", harness, args.bridge, main_model, contexts],
                 args.work / "native-query.log", args.root)
    pools = []
    for line in output.splitlines():
        if line.startswith("PUBLIC_NATIVE_POOL\t"):
            _, context, values = line.split("\t")
            pool = list(dict.fromkeys(values.split(","))) if values else []
            if len(pool) > 32 or not all(map(validator.is_han, pool)):
                raise ValueError("Actual native pool violates bound or text validity")
            pools.append(dict(context=context, pool=pool))
    if len(pools) != 200 or [row["context"] for row in pools] != [row["context"] for row in gold]:
        raise ValueError("Actual native queries must match all 200 frozen contexts in order")
    (args.work / "native-pools.tsv").write_text("".join(
        row["context"] + "\t" + ",".join(row["pool"]) + "\n" for row in pools), encoding="utf-8")
    if verify_actual_inputs(args) != inputs:
        raise ValueError("Native execution modified a dependency or language model")
    provenance = dict(scope="public_synthetic_ARM64_bionic_FakeJNI_pools_then_exact_Kotlin_backend",
        benchmark_sha256=benchmark_sha, native_sha256=digest(args.bridge),
        baseline_apk_sha256=digest(args.baseline_apk),
        executed_files_match_actual_baseline_apk=inputs,
        native_harness_sha256=digest(harness), qemu_sha256=digest(args.qemu),
        raw_native_pools_sha256=digest(args.work / "native-pools.tsv"),
        actual_android_shared_libime_execution=True, art_verified=False,
        phone_ui_or_installation_verified=False, phone_latency_verified=False,
        user_intent_accuracy_measured=False)
    (args.work / "native-pools.json").write_text(
        json.dumps(dict(provenance=provenance, cases=pools), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8")
    if args.native_only:
        print(json.dumps(dict(passed=True, native_contexts=200,
                              benchmark_sha256=benchmark_sha,
                              heldout_ranking_evaluated=False,
                              native_pool_file=str(args.work / "native-pools.json")), indent=2))
        return

    baseline_root = args.work / "baseline-source"
    for path in KOTLIN + [ASSET]:
        target = baseline_root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(subprocess.check_output(
            ["git", "-C", str(args.root), "show", args.baseline_commit + ":" + path]))
    baseline = compile_and_query(args, gold, baseline_root, baseline_root / ASSET, "baseline",
                                 dict(**provenance, source_commit=args.baseline_commit))
    current = compile_and_query(args, gold, args.root, args.root / ASSET, "current",
                                dict(**provenance, source_commit=args.source_commit))
    # The standalone scorer independently validates that no failing/missing cases were dropped.
    current_values, current_proof = evaluator.load_candidates(args.work / "current-candidates.json", gold)
    old_values, baseline_proof = evaluator.load_candidates(args.work / "baseline-candidates.json", gold)
    current_score = evaluator.evaluate(gold, current_values)
    baseline_score = evaluator.evaluate(gold, old_values)
    delta = {key: current_score["all"][key] - baseline_score["all"][key]
             for key in ["covered_top5", "covered_first", "empty_offers", "explicit_negative_violating_contexts"]}
    evaluation = dict(schema="axiang-continuation-benchmark-result-v1",
        benchmark_sha256=benchmark_sha, current_provenance=current_proof,
        baseline_provenance=baseline_proof, current=current_score, baseline=baseline_score,
        delta=delta, limitations=[
            "Known public continuation coverage, not user intent accuracy or handset testing.",
            "All 200 rows remain scored; empty offers are misses.",
            "Unmatched suggestions are unjudged, not automatically wrong or unrelated.",
            "Explicit negative rate applies only to three labelled boundary-regression contexts.",
            "No ART, phone UI, latency, or private-editor exclusion measurement."])
    (args.work / "evaluation.json").write_text(
        json.dumps(evaluation, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if verify_actual_inputs(args) != inputs or source_state() != state:
        raise ValueError("Source or exact native/model inputs changed during benchmark")
    if {path: digest(args.root / path) for path in source_hashes} != source_hashes:
        raise ValueError("Compiled benchmark source changed during execution")
    summary = dict(schema=1, passed=True, source_commit=args.source_commit,
        baseline_source_commit=args.baseline_commit, cases=200,
        successful_native_queries=200, successful_current_kotlin_queries=200,
        successful_baseline_kotlin_queries=200,
        benchmark_sha256=benchmark_sha, native_sha256=digest(args.bridge),
        completion_asset_sha256=digest(args.root / ASSET),
        baseline_completion_asset_sha256=digest(baseline_root / ASSET),
        source_files_sha256=source_hashes,
        executed_files_match_actual_baseline_apk=inputs,
        all_dependency_and_model_hashes_unchanged=True,
        current=current_score["all"], baseline=baseline_score["all"], delta=delta,
        current_splits=current_score["splits"], baseline_splits=baseline_score["splits"],
        scope=provenance["scope"], actual_android_shared_libime_execution=True,
        art_verified=False, phone_ui_or_installation_verified=False,
        phone_latency_verified=False, user_intent_accuracy_measured=False,
        note="passed means execution/structure checks passed, not 200 gold continuations were covered")
    (args.work / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(passed=True, cases=200, current=summary["current"],
                         baseline=summary["baseline"], delta=delta,
                         summary=str(args.work / "summary.json")), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
