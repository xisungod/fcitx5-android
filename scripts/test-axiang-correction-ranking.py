#!/usr/bin/env python3
"""Build/run public native ranking regressions without changing shared Rime data.

Requires a previously built host librime 1.16.1 with its Lua plugin, source
headers and the pinned compiled AXiang data. No download or deployment occurs.
The disposable user directory and overridden Lua live under --work. The output
contains hashes and public-case counters, never private input reports or paths.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--rime-source", type=Path, required=True)
    parser.add_argument("--rime-build", type=Path, required=True)
    parser.add_argument("--rime-data", type=Path, required=True)
    parser.add_argument("--prebuilt-data", type=Path,
                        help="Pinned-runtime deployed host fixture for an old APK prism")
    parser.add_argument("--include", type=Path, action="append", default=[])
    parser.add_argument("--cxx", default="g++")
    parser.add_argument("--cc", default="gcc")
    parser.add_argument("--opencc-data", type=Path, required=True,
                        help="Public host OpenCC fixture for downstream conversion checks")
    parser.add_argument("--replay-summary", type=Path,
                        help="Independent public Lua A/B summary from the same Lua/schema")
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--allow-dirty", action="store_true", help="Only for preliminary local validation")
    args = parser.parse_args()
    root, source, build, data = [p.resolve() for p in
                               (args.root, args.rime_source, args.rime_build, args.rime_data)]
    prebuilt = (args.prebuilt_data or data / "build").resolve()
    lua = root / "scripts/rime/xuancai_correction.lua"
    test_sources = {
        "correction_ranking": root / "scripts/check-axiang-correction-ranking.cpp",
        "adjacent_correction": root / "scripts/check-axiang-adjacent-correction.c",
        "rime_smoke": root / "scripts/check-xuancai-rime.c",
    }
    source_hashes = {str(path.relative_to(root)): digest(path) for path in test_sources.values()}
    driver = root / "scripts/test-axiang-correction-ranking.py"
    source_hashes[str(driver.relative_to(root))] = digest(driver)
    lua_bytes = lua.read_bytes()
    lua_hash = hashlib.sha256(lua_bytes).hexdigest()
    library = build / "lib/librime.so.1.16.1"
    required = [lua, *test_sources.values(), library, source / "src/rime_api.h",
                data / "build/rime_ice.schema.yaml", data / "rime_ice.schema.yaml",
                prebuilt / "rime_ice.schema.yaml", prebuilt / "rime_ice.prism.bin"]
    if not all(path.is_file() for path in required):
        parser.error("Pinned source, library, test and compiled schema inputs must exist")
    if args.output.exists():
        parser.error("Output must be a new file")
    commit = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    clean = not subprocess.check_output(["git", "-C", str(root), "status", "--porcelain"], text=True).strip()
    if not clean and not args.allow_dirty:
        parser.error("Freeze source before producing release evidence; preliminary runs may use --allow-dirty")
    args.work.mkdir(parents=True, exist_ok=True)
    opencc = args.opencc_data.resolve()
    opencc_inputs = {name: digest(opencc / name)
                     for name in ("s2t.json", "STCharacters.ocd2", "STPhrases.ocd2")}
    binaries = {}
    for suite, test in test_sources.items():
        binary = args.work / ("check-" + suite)
        compiler, standard = (args.cc, "-std=c11") if test.suffix == ".c" else (args.cxx, "-std=c++17")
        command = [compiler, standard, str(test), "-I", str(source / "src"),
                   "-I", str(build / "src")]
        for include in args.include:
            command += ["-I", str(include.resolve())]
        command += ["-L", str(library.parent), "-lrime", "-Wl,-rpath," + str(library.parent),
                    "-o", str(binary)]
        compiled = subprocess.run(command, text=True, capture_output=True)
        (args.work / (suite + ".compile.log")).write_text(compiled.stdout + compiled.stderr)
        if compiled.returncode:
            raise RuntimeError("Host test compilation failed; inspect --work/" + suite + ".compile.log")
        binaries[suite] = binary
    suites, checks = {}, {}
    engine_version = None
    with tempfile.TemporaryDirectory(prefix="ranking-", dir=args.work) as temporary:
        temporary = Path(temporary)
        shared, user = temporary / "data", temporary / "user"
        shared.mkdir()
        for entry in data.iterdir():
            if entry.name != "lua":
                target = prebuilt if entry.name == "build" else entry
                (shared / entry.name).symlink_to(target, target_is_directory=target.is_dir())
        (shared / "lua").mkdir()
        for entry in (data / "lua").iterdir():
            if entry.name != "xuancai_correction.lua":
                (shared / "lua" / entry.name).symlink_to(entry, target_is_directory=entry.is_dir())
        (shared / "lua/xuancai_correction.lua").write_bytes(lua_bytes)
        for suite, binary in binaries.items():
            user = temporary / (suite + "-user")
            user.mkdir()
            result = subprocess.run([str(binary), str(shared), str(user), str(shared / "build")],
                                    text=True, capture_output=True, timeout=60)
            (args.work / (suite + ".stdout")).write_text(result.stdout)
            (args.work / (suite + ".stderr")).write_text(result.stderr)
            if result.returncode:
                raise RuntimeError("Native regressions failed; inspect --work/" + suite + ".stderr")
            measured = json.loads(result.stdout.strip().splitlines()[-1])
            if suite == "correction_ranking":
                if measured["engine_version"] != "1.16.1":
                    raise RuntimeError("Unexpected native Rime version")
                engine_version = measured["engine_version"]
                counters = measured["tests"]
                checks = {key: value for key, value in measured.items()
                          if key not in ("schema", "engine_version", "tests")}
            else:
                counters = {key: measured.get(key, 0) for key in ("cases", "failures", "errors", "skipped")}
            if counters["cases"] <= 0 or any(counters[key] != 0 for key in ("failures", "errors", "skipped")):
                raise RuntimeError("Native suite did not pass")
            suites[suite] = counters
        # Keep the ordinary ranking/old acceptance/paging/selection suites on
        # the same pinned-runtime deployed schema fixture and APK dictionaries.
        # Only this additional case receives a public host
        # OpenCC fixture to exercise the downstream conversion/learning chain.
        if not (shared / "opencc").exists():
            (shared / "opencc").symlink_to(opencc, target_is_directory=True)
        conversion_user = temporary / "conversion-user"
        conversion_user.mkdir()
        conversion = subprocess.run([str(binaries["correction_ranking"]), str(shared),
                                     str(conversion_user), str(shared / "build"), "conversion"],
                                    text=True, capture_output=True, timeout=60)
        (args.work / "native_conversion_fixture.stdout").write_text(conversion.stdout)
        (args.work / "native_conversion_fixture.stderr").write_text(conversion.stderr)
        if conversion.returncode:
            raise RuntimeError("Native conversion fixture case failed; inspect --work/native_conversion_fixture.stderr")
        converted = json.loads(conversion.stdout)
        suites["native_conversion_fixture"] = converted["tests"]
        checks["native_conversion_fixture"] = {key: value for key, value in converted.items()
                                               if key not in ("schema", "engine_version", "tests")}
    if lua.read_bytes() != lua_bytes or any(digest(root / name) != expected for name, expected in source_hashes.items()):
        raise RuntimeError("Tested source changed during native regression run")
    if subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip() != commit:
        raise RuntimeError("Source commit changed during native regression run")
    tests = {key: sum(suite[key] for suite in suites.values())
             for key in ("cases", "failures", "errors", "skipped")}
    tests["suites"] = suites
    output = {"schema": 2, "source_commit": commit, "source_working_tree_clean": clean,
              "lua_source": {"path": "scripts/rime/xuancai_correction.lua", "sha256": lua_hash},
              "schemas_sha256": {"assets/usr/share/rime-data/" + name: digest(data / name)
                                 for name in ("rime_ice.schema.yaml", "build/rime_ice.schema.yaml")},
              "host_rime": {"engine_version": engine_version, "library_sha256": digest(library)},
              "host_deployed_fixture": {
                  "used": prebuilt != (data / "build").resolve(),
                  "packaged_apk_changed": False,
                  "scope": "Host uses the pinned runtime's deployed schema/prism; all public Lua A/B cases share that fixture",
                  "compiled_schema_sha256": digest(prebuilt / "rime_ice.schema.yaml"),
                  "prism_sha256": digest(prebuilt / "rime_ice.prism.bin")},
              "test_sources_sha256": source_hashes,
              "tests": tests, "checks": checks,
              "host_conversion_fixture": {"packaged_apk_changed": False,
                                          "scope": "One separate host-only OpenCC conversion case; the other three suites use the same host schema fixture without added OpenCC data",
                                          "public_opencc_inputs_sha256": opencc_inputs},
              "test_inputs": "public synthetic spellings; fresh disposable user directory",
              "device_verification": False, "phone_latency_verified": False}
    if args.replay_summary:
        replay = json.loads(args.replay_summary.read_text())
        if replay.get("new_lua_sha256") != lua_hash:
            raise RuntimeError("Independent replay Lua differs from tested source")
        if replay.get("compiled_schema_sha256") != digest(prebuilt / "rime_ice.schema.yaml"):
            raise RuntimeError("Independent replay schema differs from tested data")
        if replay.get("passed") is not True:
            raise RuntimeError("Independent replay did not pass")
        if any(replay.get(key) is not False for key in
               ("unexpected_commit", "targets_sent_to_decoder", "automatic_training",
                "phone_accuracy_claim", "phone_latency_claim")):
            raise RuntimeError("Independent replay contains unsupported behavior or phone claims")
        if replay.get("all_handled_and_input_preserved") is not True:
            raise RuntimeError("Independent replay did not preserve literal input")
        output["independent_replay"] = replay
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"tests": tests, "source_working_tree_clean": clean,
                      "lua_sha256": output["lua_source"]["sha256"]}))


if __name__ == "__main__":
    main()
