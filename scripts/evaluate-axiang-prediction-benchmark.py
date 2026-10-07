#!/usr/bin/env python3
"""Score exact public top-5 continuation coverage without inventing semantic labels."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

_spec = importlib.util.spec_from_file_location("axiang_prediction_benchmark",
    Path(__file__).with_name("check-axiang-prediction-benchmark.py"))
assert _spec is not None and _spec.loader is not None
_benchmark = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_benchmark)


def load_candidates(path: Path, gold: list[dict]) -> tuple[dict[str, list[str]], dict]:
    raw = path.read_bytes()
    value = json.loads(raw)
    metadata = value.get("provenance", {}) if isinstance(value, dict) else {}
    entries = value["cases"] if isinstance(value, dict) else value
    if not isinstance(entries, list):
        raise ValueError("candidate input must be a list, or an object with cases list")
    by_context = {row["context"]: row for row in gold}
    candidates: dict[str, list[str]] = {}
    for item in entries:
        if not isinstance(item, dict) or item.get("context") not in by_context:
            raise ValueError("unknown/missing candidate context")
        expected = by_context[item["context"]]
        if "id" in item and item["id"] != expected["id"]:
            raise ValueError(f"incorrect ID for {expected['context']}")
        if expected["id"] in candidates:
            raise ValueError(f"duplicate candidate context {expected['context']}")
        values = item.get("candidates")
        if not isinstance(values, list) or len(values) > 5:
            raise ValueError("actual displayed candidate list must have at most five items")
        if len(set(values)) != len(values) or not all(
                isinstance(text, str) and _benchmark.is_han(text) for text in values):
            raise ValueError("candidate list contains duplicate, non-Han, or non-string values")
        candidates[expected["id"]] = values
    if len(candidates) != len(gold):
        missing = [row["id"] for row in gold if row["id"] not in candidates]
        raise ValueError(f"all rows must be scored, missing {missing[:8]}")
    return candidates, dict(candidate_file_sha256=hashlib.sha256(raw).hexdigest(),
                            supplied_provenance=metadata)


def summarize(rows: list[dict]) -> dict:
    count = len(rows)
    labelled = sum(bool(row["forbidden"]) for row in rows)
    violations = sum(bool(row["forbidden_hits"]) for row in rows)
    return dict(cases=count, covered_top5=sum(row["hit_top5"] for row in rows),
                covered_first=sum(row["hit_first"] for row in rows),
                top5_coverage=sum(row["hit_top5"] for row in rows) / count if count else None,
                first_coverage=sum(row["hit_first"] for row in rows) / count if count else None,
                empty_offers=sum(not row["candidates"] for row in rows),
                explicitly_negative_contexts=labelled,
                explicit_negative_violating_contexts=violations,
                explicit_negative_context_violation_rate=violations / labelled if labelled else None,
                unlabelled_suggestion_slots=sum(row["unlabelled_slots"] for row in rows))


def evaluate(gold: list[dict], candidates: dict[str, list[str]]) -> dict:
    scored = []
    for row in gold:
        values = candidates[row["id"]]
        scored.append(dict(**row, candidates=values,
            hit_top5=bool(set(row["acceptable"]) & set(values)),
            hit_first=bool(values and values[0] in row["acceptable"]),
            forbidden_hits=[text for text in values if text in row["forbidden"]],
            unlabelled_slots=sum(text not in row["acceptable"] and text not in row["forbidden"]
                                for text in values)))
    return dict(all=summarize(scored),
                splits={split: summarize([row for row in scored if row["split"] == split])
                        for split in ["development", "heldout"]},
                categories={category: summarize([row for row in scored if row["category"] == category])
                            for category in ["idiom", "literary", "lexical", "dialogue"]},
                cases=scored)


def self_test(gold: list[dict]) -> None:
    perfect = {row["id"]: row["acceptable"][:1] for row in gold}
    first = evaluate(gold, perfect)
    assert first["all"]["covered_top5"] == 200
    assert first["splits"]["heldout"]["cases"] == 160
    assert first["all"]["explicit_negative_violating_contexts"] == 0
    changed = dict(perfect)
    negative = next(row for row in gold if row["forbidden"])
    changed[negative["id"]] = [negative["forbidden"][0]]
    changed[gold[0]["id"]] = []
    report = evaluate(gold, changed)
    assert report["all"]["covered_top5"] == 198
    assert report["all"]["empty_offers"] == 1
    assert report["all"]["explicit_negative_violating_contexts"] == 1
    assert report["all"]["unlabelled_suggestion_slots"] == 0
    changed[gold[0]["id"]] = ["的"]
    report = evaluate(gold, changed)
    assert report["all"]["unlabelled_suggestion_slots"] == 1
    assert report["all"]["explicit_negative_violating_contexts"] == 1


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--benchmark", type=Path, default=_benchmark.DEFAULT_BENCHMARK)
    parser.add_argument("--candidates", type=Path)
    parser.add_argument("--baseline", type=Path, help="the same full corpus queried by the prior version")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    gold, digest = _benchmark.load_benchmark(args.benchmark)
    if args.self_test:
        self_test(gold)
        print("prediction benchmark scorer self-test passed")
    if not args.candidates:
        if args.self_test:
            return
        parser.error("--candidates is required unless only running --self-test")
    candidates, provenance = load_candidates(args.candidates, gold)
    result = dict(schema="axiang-continuation-benchmark-result-v1",
                  benchmark_sha256=digest, **provenance,
                  limitations=["Known public phrase/continuation coverage, not handset intent accuracy.",
                    "Exact accepted alternatives are incomplete semantic labels; unmatched suggestions are unjudged.",
                    "Negative violations use explicitly labelled boundary-regression words only, not an overall unrelated-suggestion rate.",
                    "This does not measure private-editor exclusion or phone/UI latency."],
                  current=evaluate(gold, candidates))
    if args.baseline:
        old, baseline_provenance = load_candidates(args.baseline, gold)
        result["baseline_provenance"] = baseline_provenance
        result["baseline"] = evaluate(gold, old)
        result["delta"] = {
            key: result["current"]["all"][key] - result["baseline"]["all"][key]
            for key in ["covered_top5", "covered_first", "empty_offers", "explicit_negative_violating_contexts"]}
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
        print(json.dumps(dict(benchmark_sha256=digest, current=result["current"]["all"],
                             delta=result.get("delta"), output=str(args.output)),
                         ensure_ascii=False, indent=2))
    else:
        print(rendered, end="")


if __name__ == "__main__":
    main()
