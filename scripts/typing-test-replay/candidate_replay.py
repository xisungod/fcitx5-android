#!/usr/bin/env python3
"""Independent native Rime replay of frozen literal/alternative spellings.

Run this AFTER pure touch replay. Coordinates are not read and proposals are not
generated here. Expected targets are compared only after the native query. Each
unique spelling has an empty temporary user directory and no committed context.
This cannot reproduce phone user learning, context, touch.4 chip eligibility or
UI timing; it is not a candidate-ranking accuracy benchmark of the installed app.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("axiang_existing_candidate_replay",
                                            HERE.parent / "touch-diagnostics/candidate_replay.py")
existing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(existing)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def replay(document, binary, data, limit, timeout):
    cache, results = {}, []
    for version in ("baseline", "current"):
        if version not in document:
            continue
        for trial in document[version]["trials"]:
            final = trial["steps"][-1].get("proposal") if trial["steps"] else None
            spellings = [("literal", trial["literal_spelling"])]
            if final:
                spellings.append(("alternative", final["alternativeSpelling"]))
            for path_kind, spelling in spellings:
                status, reason = "ok", existing.unsupported_reason(spelling)
                if reason:
                    status = "unsupported_input"
                elif spelling not in cache:
                    started = time.perf_counter_ns()
                    try:
                        native = existing.query_native(spelling, binary, data, limit, timeout)
                        cache[spelling] = {"native": native, "host_query_wall_ns": time.perf_counter_ns() - started}
                    except (RuntimeError, ValueError, OSError, subprocess.TimeoutExpired) as error:
                        cache[spelling] = {"error": str(error)}
                frozen = cache.get(spelling, {})
                if "error" in frozen:
                    status, reason = "native_error", frozen["error"]
                candidates = frozen.get("native", {}).get("candidates", [])
                # No label crosses query_native(). These comparisons are report-only.
                target_han = trial.get("known_target_han")
                rank = next((candidate["rank"] for candidate in candidates if candidate["text"] == target_han), None)
                results.append({"source_version": version, "prompt_id": trial["prompt_id"], "path_kind": path_kind,
                                "spelling": spelling, "status": status, "reason": reason,
                                "known_target_han": target_han, "target_han_rank": rank,
                                "target_pinyin_matches": spelling == trial.get("known_target_pinyin"),
                                "candidates": candidates, "host_query_wall_ns": frozen.get("host_query_wall_ns"),
                                "phone_snapshot": trial.get("recorded_candidate_snapshot"),
                                "original_phone_snapshot_not_equivalent_to_empty_context_query": True})
    return results, len(cache)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--touch-replay", type=Path, required=True)
    parser.add_argument("--rime-binary", type=Path, required=True)
    parser.add_argument("--rime-data", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=20)
    parser.add_argument("--timeout", type=float, default=30)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    if not 1 <= args.limit <= 1000 or not 0 < args.timeout <= 300:
        parser.error("limit must be 1–1000; timeout >0 and <=300")
    if args.output.exists():
        parser.error("Output must be a new file")
    doc = json.loads(args.touch_replay.read_text(encoding="utf-8"))
    if not isinstance(doc, dict) or doc.get("format") != "axiang-pure-touch-replay-v1" or doc.get("targets_sent_to_model") is not False:
        parser.error("Expected labelled evaluation output from the label-free pure touch replay")
    binary, data = args.rime_binary.resolve(), args.rime_data.resolve()
    schema = data / "build/rime_ice.schema.yaml"
    if not binary.is_file() or not schema.is_file():
        parser.error("Existing native exporter and compiled Rime schema must exist")
    results, queries = replay(doc, binary, data, args.limit, args.timeout)
    output = {"format": "axiang-independent-candidate-replay-v1", "results": results,
              "touch_replay_sha256": digest(args.touch_replay), "binary_sha256": digest(binary),
              "schema_sha256": digest(schema), "unique_spelling_queries": queries,
              "candidate_limit": args.limit, "targets_sent_to_decoder": False,
              "touch_coordinates_used": False, "fresh_empty_user_directory_per_unique_spelling": True,
              "committed_context_in_rime": False, "automatic_training": False,
              "limitation": __doc__.strip()}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    args.output.chmod(0o600)
    print(json.dumps({"output": str(args.output), "queries": queries, "results": len(results)}))
    return int(any(row["status"] == "native_error" for row in results))


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError) as error:
        print(f"typing-candidate-replay: {error}", file=sys.stderr)
        raise SystemExit(2)
