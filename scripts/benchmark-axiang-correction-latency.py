#!/usr/bin/env python3
"""Measure public synthetic Lua/Rime latency with a pinned, already built host.

CPU profiling uses a temporary Lua wrapper and disposable user directories.
Uninstrumented A/B timings are reported separately. No model, private typing
report, target text, touch coordinates, learning or deployment is involved.
These host timings are not phone performance measurements.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess

PUBLIC_INPUTS = [
    "nihaoa", "xiaoguniang", "jingchanghui", "jintiantianqihenhao",
    "wozhengzaidazi", "mingtianyiqichifan", "qingbangwokanyixia",
    "zhegewentizenmejiejue", "wanshangzaodianxiuxi", "nixianzaizainali",
    "womenyiqihuijia", "shoujijianpanhenhaoyong", "shurusuduyuelaiyuekuai",
    "jintiangongzuohenshunli", "qinggeiwofagexiaoxi", "womashangjiudaole",
    "zhoumochuqusanbu", "lvsedelvxing", "baochizirandesudu", "zhunimeitiandoukaixin",
    "gaileme", "gaoleme", "baoleme", "haoleme", "jihaoa", "nuhaoa",
    "jibgchsnghui", "xiaoguniabg", "xiaogujiang", "xiaogunuabg",
    "shang", "shabg", "shuruga", "xiaotuniang", "nh", "jch", "jib", "xgn",
    "huilaileme", "shangbanleme", "jintianwomentaolunyixiagongzuoanpai",
    "mingtianxiawusandianwomenkaihui",
]

PROFILE_PREAMBLE = r'''
-- Temporary host-only profiling; this wrapper is never packaged in the APK.
local profile_input, profile_stage = '', ''
local profile_rows = {}
local function profile_record(stage, elapsed)
  local key = profile_input .. '\t' .. stage
  local row = profile_rows[key] or {calls = 0, seconds = 0}
  row.calls, row.seconds = row.calls + 1, row.seconds + elapsed
  profile_rows[key] = row
end
local function profile_component(component, kind)
  return {query = function(_, input, seg)
    local stage = profile_stage .. '_' .. kind
    local started = os.clock()
    local translation = component:query(input, seg)
    profile_record(stage .. '_query', os.clock() - started)
    if not translation then return nil end
    return {iter = function()
      local iterator, state, value = translation:iter()
      return function(...)
        local next_started = os.clock()
        local result = table.pack(iterator(...))
        profile_record(stage .. '_next', os.clock() - next_started)
        return table.unpack(result, 1, result.n)
      end, state, value
    end}
  end}
end
local function profile_write()
  local file = assert(io.open(assert(os.getenv('AXIANG_PUBLIC_LUA_PROFILE')), 'w'))
  for key, row in pairs(profile_rows) do
    file:write(string.format('%s\t%d\t%.9f\n', key, row.calls, row.seconds))
  end
  file:close()
end
'''


def profile_lua(source: str) -> str:
    # All assertions pin the instrumentation sites; fail instead of silently
    # profiling a different implementation after a source change.
    replacements = {
        "local function exact_evidence(input, seg, env)\n":
            "local function exact_evidence(input, seg, env)\n  profile_stage = 'exact'\n",
        "local function short_repair(input, seg, env, head, priority)\n":
            "local function short_repair(input, seg, env, head, priority)\n  profile_stage = 'short'\n",
        "  local matches = typo_index.search(env.typo_index, input, neighbors)\n":
            "  local index_started = os.clock()\n"
            "  local matches = typo_index.search(env.typo_index, input, neighbors)\n"
            "  profile_record('short_index_search', os.clock() - index_started)\n",
        "              local score = distance(input, code, limit)\n":
            "              local distance_started = os.clock()\n"
            "              local score = distance(input, code, limit)\n"
            "              profile_record('phrase_distance', os.clock() - distance_started)\n",
        "function M.func(input, seg, env)\n":
            "function M.func(input, seg, env)\n  profile_input = input\n",
        "  env.commands = {}\n":
            "  env.exact = profile_component(env.exact, 'literal')\n"
            "  env.translator = profile_component(env.translator, 'correction')\n"
            "  env.commands = {}\n",
        "function M.fini(env)\n": "function M.fini(env)\n  profile_write()\n",
    }
    for before, after in replacements.items():
        if source.count(before) != 1:
            raise ValueError("Profiling site changed: " + before.splitlines()[0])
        source = source.replace(before, after)
    source, changed = re.subn(
        r"(local function phrase_repair\(input, seg, env, head, priority(?:, stream)?\)\n)",
        r"\1  profile_stage = 'phrase'\n", source)
    if changed != 1:
        raise ValueError("Phrase repair profiling site changed")
    before, main = source.split("function M.func(input, seg, env)\n", 1)
    native_query = "  local translation = env.translator:query(input, seg)\n"
    if main.count(native_query) != 1:
        raise ValueError("Main native query profiling site changed")
    main = main.replace(native_query, "  profile_stage = 'native'\n" + native_query)
    source = before + "function M.func(input, seg, env)\n" + main
    return PROFILE_PREAMBLE + source


def stats(values: list[float]) -> dict:
    ordered = sorted(values)
    if not ordered:
        return {"count": 0}
    def percentile(fraction):
        index = (len(ordered) - 1) * fraction
        low, high = math.floor(index), math.ceil(index)
        return ordered[low] + (ordered[high] - ordered[low]) * (index - low)
    return {"count": len(ordered), "p50": percentile(.5), "p95": percentile(.95),
            "p99": percentile(.99), "mean": statistics.mean(ordered), "max": ordered[-1]}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--rime-source", type=Path, required=True)
    parser.add_argument("--rime-build", type=Path, required=True)
    parser.add_argument("--rime-data", type=Path, required=True)
    parser.add_argument("--prebuilt-data", type=Path,
                        help="Shared deployed fixture, when the pinned runtime must rebuild an old APK prism")
    parser.add_argument("--expected-version", required=True)
    parser.add_argument("--baseline-lua", type=Path, required=True)
    parser.add_argument("--candidate-lua", type=Path)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--include", type=Path, action="append", default=[])
    parser.add_argument("--trials", type=int, default=3)
    parser.add_argument("--repeats", type=int, default=3)
    args = parser.parse_args()
    root, data, work = args.root.resolve(), args.rime_data.resolve(), args.work.resolve()
    prebuilt = (args.prebuilt_data or data / "build").resolve()
    work.mkdir(parents=True, exist_ok=True)
    if (work / "summary.json").exists():
        parser.error("Use a fresh work directory so benchmark evidence is not overwritten")
    library = args.rime_build.resolve() / ("lib/librime.so." + args.expected_version)
    if not library.is_file():
        parser.error("Pinned host library must already exist")
    benchmark_source_hashes = {name: sha(root / name) for name in (
        "scripts/check-axiang-correction-latency.c",
        "scripts/check-axiang-correction-stream.cpp",
        "scripts/benchmark-axiang-correction-latency.py")}
    library_hash = sha(library)
    schema_hash = sha(prebuilt / "rime_ice.schema.yaml")
    prism_hash = sha(prebuilt / "rime_ice.prism.bin")
    compiler = ["gcc", "-std=c11", str(root / "scripts/check-axiang-correction-latency.c"),
                "-I", str(args.rime_source.resolve() / "src")]
    for include in args.include:
        compiler += ["-I", str(include.resolve())]
    binary = work / "public-latency"
    compiler += ["-L", str(library.parent), "-lrime", "-Wl,-rpath," + str(library.parent),
                 "-o", str(binary)]
    subprocess.run(compiler, check=True)
    stream_binary = work / "public-prefix-stream"
    stream_compiler = ["g++", "-std=c++17", str(root / "scripts/check-axiang-correction-stream.cpp"),
                       "-I", str(args.rime_source.resolve() / "src"),
                       "-I", str(args.rime_build.resolve() / "src")]
    for include in args.include:
        stream_compiler += ["-I", str(include.resolve())]
    stream_compiler += ["-L", str(library.parent), "-lrime", "-Wl,-rpath," + str(library.parent),
                        "-o", str(stream_binary)]
    subprocess.run(stream_compiler, check=True)
    variants = {"baseline": args.baseline_lua.resolve()}
    if args.candidate_lua:
        variants["candidate"] = args.candidate_lua.resolve()
    inputs = {name: path.read_text() for name, path in variants.items()}
    lua_hashes = {name: sha(path) for name, path in variants.items()}
    summaries = {}
    def run(name, trial, instrumented=False, prefix_stream=False):
        label = name + ("-profile" if instrumented else "") + ("-prefixes" if prefix_stream else "") + "-" + str(trial)
        shared, user = work / (label + "-data"), work / (label + "-user")
        shared.mkdir(); user.mkdir()
        for entry in data.iterdir():
            if entry.name != "lua":
                target = prebuilt if entry.name == "build" else entry
                (shared / entry.name).symlink_to(target, target_is_directory=target.is_dir())
        (shared / "lua").mkdir()
        for entry in (data / "lua").iterdir():
            if entry.name != "xuancai_correction.lua":
                (shared / "lua" / entry.name).symlink_to(entry, target_is_directory=entry.is_dir())
        (shared / "lua/xuancai_correction.lua").write_text(
            profile_lua(inputs[name]) if instrumented else inputs[name])
        env = dict(os.environ)
        env["AXIANG_PUBLIC_LUA_PROFILE"] = str(work / (label + ".profile.tsv"))
        command = [str(stream_binary if prefix_stream else binary), str(shared), str(user),
                   str(shared / "build"), args.expected_version]
        command += ([] if prefix_stream else [str(args.repeats)]) + PUBLIC_INPUTS
        with (work / (label + ".stdout.jsonl")).open("w") as stdout, \
             (work / (label + ".stderr")).open("w") as stderr:
            subprocess.run(command, stdout=stdout, stderr=stderr, env=env,
                           check=True, timeout=180)
        return [json.loads(line) for line in (work / (label + ".stdout.jsonl")).read_text().splitlines()]
    rows = {name: [] for name in variants}
    for trial in range(args.trials):
        for name in list(variants) if trial % 2 == 0 else list(reversed(variants)):
            rows[name] += run(name, trial)
            print("uninstrumented", name, trial, flush=True)
    for name in variants:
        for field in ("process_ms", "visible_ms"):
            summaries.setdefault(name, {})[field] = stats([x for row in rows[name] for x in row[field]])
        summaries[name]["first_pass_visible_ms"] = stats([
            x for row in rows[name] if row["repeat"] == 0 for x in row["visible_ms"]])
        summaries[name]["warm_visible_ms"] = stats([
            x for row in rows[name] if row["repeat"] > 0 for x in row["visible_ms"]])
        summaries[name]["length_buckets_visible_ms"] = {
            str(start) + "-" + str(end): stats([value for row in rows[name]
                for index, value in enumerate(row["visible_ms"], 1) if start <= index <= end])
            for start, end in ((1, 8), (9, 15), (16, 24), (25, 64))
        }
        run(name, 0, instrumented=True)
        stages = {}
        for line in (work / (name + "-profile-0.profile.tsv")).read_text().splitlines():
            _, stage, count, seconds = line.split("\t")
            previous = stages.setdefault(stage, {"calls": 0, "cpu_seconds": 0.0})
            previous["calls"] += int(count)
            previous["cpu_seconds"] += float(seconds)
        summaries[name]["instrumented_cpu_stages"] = stages
    signatures = {name: {row["input"]: row["candidates"] for row in values if row["repeat"] == 0}
                  for name, values in rows.items()}
    differences = []
    if "candidate" in rows:
        differences = [{"input": text, "baseline": signatures["baseline"][text],
                        "candidate": signatures["candidate"][text]} for text in PUBLIC_INPUTS
                       if signatures["baseline"][text] != signatures["candidate"][text]]
    stable_candidates = {name: all(row["candidates"] == signatures[name][row["input"]]
                                   for row in values) for name, values in rows.items()}
    prefix_rows = {name: run(name, 0, prefix_stream=True) for name in variants}
    prefix_differences = []
    if "candidate" in prefix_rows:
        baseline_prefixes = {(row["input"], row["prefix"]): row["candidates"]
                             for row in prefix_rows["baseline"]}
        for row in prefix_rows["candidate"]:
            before = baseline_prefixes[(row["input"], row["prefix"])]
            if before != row["candidates"]:
                prefix_differences.append({"input": row["input"], "prefix": row["prefix"],
                                           "baseline": before, "candidate": row["candidates"]})
    if (any(sha(root / name) != expected for name, expected in benchmark_source_hashes.items())
            or any(sha(variants[name]) != expected for name, expected in lua_hashes.items())
            or sha(library) != library_hash or sha(prebuilt / "rime_ice.schema.yaml") != schema_hash
            or sha(prebuilt / "rime_ice.prism.bin") != prism_hash):
        raise RuntimeError("Measured source or native fixture changed during the benchmark")
    summary = {"schema": 1, "host_version": args.expected_version, "host_library_sha256": library_hash,
               "lua_sha256": lua_hashes,
               "benchmark_sources_sha256": benchmark_source_hashes,
               "compiled_schema_sha256": schema_hash,
               "deployed_prism_sha256": prism_hash,
               "packaged_compiled_schema_sha256": sha(data / "build/rime_ice.schema.yaml"),
               "deployed_host_fixture_used": prebuilt != (data / "build").resolve(),
               "public_input_count": len(PUBLIC_INPUTS), "trials": args.trials,
               "repeats_per_trial": args.repeats, "variants": summaries,
               "top32_candidate_differences": differences,
               "top32_candidates_stable_across_repetitions": stable_candidates,
               "prefix_stream_count_per_variant": {name: len(values) for name, values in prefix_rows.items()},
               "prefix_stream_differences": prefix_differences,
               "candidate_invariance_passed": (not differences and not prefix_differences
                                                and all(stable_candidates.values())),
               "all_handled_and_input_preserved": True, "unexpected_commit": False,
               "automatic_training": False, "targets_sent_to_decoder": False,
               "phone_latency_claim": False, "phone_accuracy_claim": False,
               "profiling_is_separate_from_wall_timings": True}
    (work / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
