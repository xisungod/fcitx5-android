#!/usr/bin/env python3
"""Validate the public gold set independently of prediction data or implementation."""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BENCHMARK = ROOT / "docs/axiang/prediction-continuation-benchmark.tsv"


def is_han(value: str) -> bool:
    return bool(value) and all(
        0x3400 <= ord(ch) <= 0x4DBF or 0x4E00 <= ord(ch) <= 0x9FFF
        or 0xF900 <= ord(ch) <= 0xFAFF or 0x20000 <= ord(ch) <= 0x323AF
        for ch in value
    )


def load_benchmark(path: Path = DEFAULT_BENCHMARK) -> tuple[list[dict], str]:
    raw = path.read_bytes()
    rows: list[dict] = []
    for number, line in enumerate(raw.decode("utf-8").splitlines(), 1):
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 6:
            raise ValueError(f"line {number}: expected six TSV columns")
        identity, split, category, context, acceptable, forbidden = fields
        alternatives = acceptable.split("|")
        negatives = forbidden.split("|") if forbidden else []
        if split not in {"development", "heldout"}:
            raise ValueError(f"line {number}: invalid split")
        if category not in {"idiom", "literary", "lexical", "dialogue"}:
            raise ValueError(f"line {number}: invalid category")
        if not is_han(context) or not all(map(is_han, alternatives + negatives)):
            raise ValueError(f"line {number}: empty or non-Han text")
        if len(context) > 8 or any(len(value) > 6 for value in alternatives + negatives):
            raise ValueError(f"line {number}: unexpectedly long context/continuation")
        if len(set(alternatives)) != len(alternatives) or set(alternatives) & set(negatives):
            raise ValueError(f"line {number}: duplicate or contradictory labels")
        rows.append(dict(id=identity, split=split, category=category,
                         context=context, acceptable=alternatives, forbidden=negatives))
    if len(rows) != 200:
        raise ValueError(f"expected all 200 rows, got {len(rows)}")
    if len({row['id'] for row in rows}) != 200 or len({row['context'] for row in rows}) != 200:
        raise ValueError("IDs and contexts must be unique")
    if Counter(row["category"] for row in rows) != dict.fromkeys(
            ["idiom", "literary", "lexical", "dialogue"], 50):
        raise ValueError("each category must contain 50 items")
    for index, row in enumerate(rows):
        expected_split = "development" if index % 5 == 0 else "heldout"
        if row["split"] != expected_split:
            raise ValueError(f"{row['id']}: split differs from frozen every-fifth rule")
    for context in ["天青色", "一帆", "我想喝", "你好", "谢谢", "再见"]:
        if not any(row["context"] == context for row in rows):
            raise ValueError(f"missing required regression {context}")
    return rows, hashlib.sha256(raw).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--benchmark", type=Path, default=DEFAULT_BENCHMARK)
    parser.add_argument("--contexts", type=Path,
                        help="write public contexts in frozen order for native querying")
    args = parser.parse_args()
    rows, digest = load_benchmark(args.benchmark)
    if args.contexts:
        args.contexts.write_text("".join(row["context"] + "\n" for row in rows), encoding="utf-8")
    print(json.dumps(dict(schema="axiang-continuation-benchmark-v1", rows=len(rows),
                         splits=dict(Counter(row["split"] for row in rows)),
                         categories=dict(Counter(row["category"] for row in rows)),
                         explicitly_negative_contexts=sum(bool(row["forbidden"]) for row in rows),
                         benchmark_sha256=digest,
                         scope="public known-continuation coverage; not user intent accuracy"),
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
