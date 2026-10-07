#!/usr/bin/env python3
"""Derive a bounded, read-only next-word completion index from pinned Rime Ice.

This table supplies lexical continuations such as the remainder of a dictionary
word after part of that word has been committed. It is a recall source for the
existing statistical predictor, not a sentence generator or a user dictionary.
All extraction is generic: no prefix-specific mappings or hand-picked phrases.

Only the already packaged base table is used. The extension/Tencent tables have
uniform weights and are deliberately not treated as measured phrase frequency.
Generation is local and byte deterministic. Retain the bundled Rime Ice GPL-3.0
license and source notices when redistributing this derived resource.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
from typing import Iterable

REVISION = "3aea6d3694fb3d94ec663641f021f788822897ad"
ARCHIVE_SHA256 = "a170d79442f7463118fbfde089f62d046710475436e3c5347de2cf1aeaa95b48"
BASE_SHA256 = "19f6f96f5dfe553545f36c979001a12e3f3c0316f4e23f4382960a13e93e7550"
SOURCE_NAME = "cn_dicts/base.dict.yaml"
FORMAT = "AXNPC1"
MARKER = "# AXiang next-word completions v1"
MIN_WEIGHT = 5_000
MAX_SINGLE = 24
MAX_MULTI = 64
MAX_BYTES = 999_999
MAX_ROW_BYTES = 1_024


def pure_han(text: str) -> bool:
    """The first version retains only BMP CJK Unified/Extension-A characters."""
    return bool(text) and all("\u3400" <= char <= "\u4dbf" or "\u4e00" <= char <= "\u9fff"
                              for char in text)


def dictionary_rows(path: Path) -> Iterable[tuple[str, int]]:
    """Ignore YAML/header/comment/unweighted rows; preserve integer rank weight."""
    in_body = False
    with path.open(encoding="utf-8", errors="strict") as source:
        for line in source:
            line = line.rstrip("\r\n")
            if line == "...":
                in_body = True
                continue
            if not in_body or not line or line.startswith("#"):
                continue
            columns = line.split("\t")
            if len(columns) != 3 or not columns[1]:
                continue
            try:
                weight = int(columns[2])
            except ValueError:
                continue
            if weight <= 0 or weight > 0xffff_ffff:
                continue
            yield columns[0], weight


def completion_records(
    rows: Iterable[tuple[str, int]], *, minimum_weight: int = MIN_WEIGHT,
    max_single: int = MAX_SINGLE, max_multi: int = MAX_MULTI,
) -> tuple[list[tuple[str, tuple[str, ...], tuple[str, ...]]], dict]:
    """Deduplicate words before deriving every eligible proper Han prefix.

    Multiple pronunciations do not multiply a word's weight. Separate bounded
    pools prevent plentiful one-character completions from deleting the longer
    continuations before the predictor has a chance to score them.
    """
    if minimum_weight < 1 or not 1 <= max_single <= 64 or not 1 <= max_multi <= 64:
        raise ValueError("Invalid completion pool limits")
    words: dict[str, int] = {}
    eligible_rows = 0
    for word, weight in rows:
        if weight < minimum_weight or not 2 <= len(word) <= 8 or not pure_han(word):
            continue
        eligible_rows += 1
        words[word] = max(weight, words.get(word, 0))
    pools: dict[str, tuple[dict[str, int], dict[str, int]]] = defaultdict(lambda: ({}, {}))
    for word, weight in words.items():
        for length in range(1, min(4, len(word) - 1) + 1):
            suffix = word[length:]
            if not 1 <= len(suffix) <= 4:
                continue
            group = pools[word[:length]][0 if len(suffix) == 1 else 1]
            group[suffix] = max(weight, group.get(suffix, 0))
    records = []
    pruned_isolated_prefixes = 0
    for prefix in sorted(pools, key=lambda text: text.encode("utf-8")):
        singles, multis = pools[prefix]
        if len(prefix) > 1 and len(singles) + len(multis) < 2:
            pruned_isolated_prefixes += 1
            continue
        order = lambda item: (-item[1], item[0].encode("utf-8"))
        single_words = tuple(word for word, _ in sorted(singles.items(), key=order)[:max_single])
        multi_words = tuple(word for word, _ in sorted(multis.items(), key=order)[:max_multi])
        records.append((prefix, single_words, multi_words))
    stats = {
        "eligible_source_rows": eligible_rows,
        "unique_source_words": len(words),
        "pruned_isolated_non_single_prefixes": pruned_isolated_prefixes,
        "entries": len(records),
        "single_completions": sum(len(single) for _, single, _ in records),
        "multi_completions": sum(len(multi) for _, _, multi in records),
    }
    return records, stats


def encode(records: list[tuple[str, tuple[str, ...], tuple[str, ...]]], metadata: dict,
           *, maximum_bytes: int = MAX_BYTES) -> bytes:
    lines = [MARKER, f"# entries: {len(records)}",
             "# " + json.dumps(metadata, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
             "# prefix<TAB>single-Han-suffix comma list<TAB>multi-Han-suffix comma list; empty columns preserved"]
    for prefix, singles, multis in records:
        line = prefix + "\t" + ",".join(singles) + "\t" + ",".join(multis)
        if len(line.encode("utf-8")) > MAX_ROW_BYTES:
            raise ValueError("Completion record exceeds the reader's bounded row size")
        lines.append(line)
    content = ("\n".join(lines) + "\n").encode("utf-8")
    if len(content) > maximum_bytes:
        raise ValueError("Completion resource exceeds the public source byte budget")
    return content


def build(data_root: Path, source_manifest: Path, output: Path) -> dict:
    manifest = json.loads(source_manifest.read_text(encoding="utf-8"))
    if manifest.get("revision") != REVISION or manifest.get("archive_sha256") != ARCHIVE_SHA256:
        raise ValueError("Only the pinned public Rime Ice revision is accepted")
    path = data_root / SOURCE_NAME
    actual_sha = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual_sha != BASE_SHA256 or manifest.get("files_sha256", {}).get(SOURCE_NAME) != actual_sha:
        raise ValueError(f"Pinned public source checksum mismatch: {SOURCE_NAME}")
    records, stats = completion_records(dictionary_rows(path))
    metadata = {
        "format": FORMAT,
        "license": "GPL-3.0; retain bundled usr/share/licenses/rime-ice/LICENSE and upstream data-source notices",
        "source_project": "https://github.com/iDvel/rime-ice",
        "source_revision": REVISION,
        "source_archive_sha256": ARCHIVE_SHA256,
        "sources_sha256": {SOURCE_NAME: actual_sha},
        "generator": "scripts/build-axiang-nextword-completions.py",
        "rules": {"minimum_dictionary_weight": MIN_WEIGHT, "word_han_length": [2, 8],
                  "prefix_han_length": [1, 4], "suffix_han_length": [1, 4],
                  "han_scope": "U+3400..U+4DBF, U+4E00..U+9FFF", "single_pool_limit": MAX_SINGLE,
                  "multi_pool_limit": MAX_MULTI, "non_single_prefix_minimum_distinct_suffixes": 2,
                  "duplicate_word_weight": "maximum", "prefix_order": "UTF-8 ascending",
                  "suffix_order": "dictionary weight descending, UTF-8 ascending for ties",
                  "maximum_resource_bytes": MAX_BYTES, "maximum_record_bytes": MAX_ROW_BYTES},
        "statistics": stats,
        "limitations": "Dictionary weights are ranking heuristics, not probabilities. Recall only; no user data, learned history or neural model. Uniform-weight extension/Tencent tables excluded.",
    }
    content = encode(records, metadata)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(content)
    return {**metadata, "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()}


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-root", type=Path, default=root / "plugin/rime/src/main/cpp/xuancai-rime-ice")
    parser.add_argument("--source-manifest", type=Path, help="Pinned public SOURCE.json; defaults to data root")
    parser.add_argument("--output", type=Path, default=root / "app/src/main/assets/typing/next_word_completions.tsv")
    args = parser.parse_args()
    result = build(args.data_root, args.source_manifest or args.data_root / "SOURCE.json", args.output)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
