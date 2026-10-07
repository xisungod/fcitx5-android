#!/usr/bin/env python3
"""Derive a bounded, read-only next-word completion index from pinned Rime Ice.

This table supplies lexical continuations such as the remainder of a dictionary
word after part of that word has been committed. It is a recall source for the
existing statistical predictor, not a sentence generator or a user dictionary.
All extraction is generic: no prefix-specific mappings or hand-picked phrases.

The already packaged base, extension and Tencent tables supply distinct recall
sources. Uniform extension/Tencent weights are never treated as frequency or
added to the base ranking weights. Sparse known phrases remain available.
Generation is local and byte deterministic. Retain the bundled Rime Ice GPL-3.0
license and source notices when redistributing this derived resource.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
from typing import Iterable

REVISION = "3aea6d3694fb3d94ec663641f021f788822897ad"
ARCHIVE_SHA256 = "a170d79442f7463118fbfde089f62d046710475436e3c5347de2cf1aeaa95b48"
BASE_SHA256 = "19f6f96f5dfe553545f36c979001a12e3f3c0316f4e23f4382960a13e93e7550"
SOURCE_NAME = "cn_dicts/base.dict.yaml"
SOURCES_SHA256 = {
    SOURCE_NAME: BASE_SHA256,
    "cn_dicts/ext.dict.yaml": "f3843fecd2ec69ab823360383e8a07b4a69f4189133a87186f1702b350c1db2e",
    "cn_dicts/tencent.dict.yaml": "858a641cef8b22d5c7966efcada3332c780ef4ac7b188318d221b450e673eca5",
}
SOURCE_KINDS = ("base", "ext", "tencent")
FORMAT = "AXNPC2"
MARKER = "# AXiang next-word completions v2"
MIN_WEIGHT = 5_000
ANCHOR_MIN_WEIGHT = 100
MAX_SINGLE = 24
MAX_MULTI = 64
EXTRA_PER_GROUP = 10
MAX_PREFIX = 6
MAX_SUFFIX = 6
MAX_WORD = 12
MAX_BYTES = 16 * 1024 * 1024
MAX_ROW_BYTES = 2_048
LOW_INFORMATION_PHRASES = frozenset({"一个", "一些", "一点", "一种", "这个", "那个", "这种", "那种"})
LOW_INFORMATION_SINGLES = frozenset({"的", "了", "一", "个", "得", "过", "着", "和", "与", "不",
    "在", "是", "也", "又", "就", "把", "比", "之", "其", "于", "及", "而", "或", "所", "等"})


def pure_han(text: str) -> bool:
    """The first version retains only BMP CJK Unified/Extension-A characters."""
    return bool(text) and all("\u3400" <= char <= "\u4dbf" or "\u4e00" <= char <= "\u9fff"
                              for char in text)


def dictionary_rows(path: Path, kind: str = "base") -> Iterable[tuple[str, int]]:
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
            expected_columns = 2 if kind == "tencent" else 3
            if len(columns) != expected_columns or not columns[1]:
                continue
            try:
                weight = int(columns[-1])
            except ValueError:
                continue
            if weight <= 0 or weight > 0xffff_ffff:
                continue
            yield columns[0], weight


def completion_records(
    rows: Iterable[tuple[str, int]], *, minimum_weight: int = MIN_WEIGHT,
    max_single: int = MAX_SINGLE, max_multi: int = MAX_MULTI,
    other_rows: dict[str, Iterable[tuple[str, int]]] | None = None,
) -> tuple[list[tuple[str, tuple[str, ...], tuple[str, ...], int]], dict]:
    """Preserve base pools and append bounded continuations from three sources.

    Multiple pronunciations do not multiply a word's weight. Separate bounded
    pools prevent plentiful one-character completions from deleting the longer
    continuations before the predictor has a chance to score them.
    """
    if minimum_weight < 1 or not 1 <= max_single <= 64 or not 1 <= max_multi <= 64:
        raise ValueError("Invalid completion pool limits")
    supplied = {"base": rows, **(other_rows or {})}
    if "base" in (other_rows or {}) or set(supplied) - set(SOURCE_KINDS):
        raise ValueError("Unsupported dictionary source")
    words: dict[str, dict[str, int]] = {}
    eligible_rows = Counter()
    for kind in SOURCE_KINDS:
        unique = {}
        for word, weight in supplied.get(kind, ()):
            if weight < 1 or not 2 <= len(word) <= MAX_WORD or not pure_han(word):
                continue
            eligible_rows[kind] += 1
            unique[word] = max(weight, unique.get(word, 0))
        words[kind] = unique
    base = words["base"]
    # Old-range high-weight pools preserve v1 ordering/quota exactly. Wider
    # suffixes enter the additional quota, so they cannot push old entries out.
    # New five/six-Han anchors have no legacy pool and use the full base quota.
    primary: dict[str, dict[str, tuple[str, int]]] = defaultdict(dict)
    wider_base: dict[str, dict[str, tuple[str, int]]] = defaultdict(dict)
    strong_prefixes = set()
    for word, weight in base.items():
        if weight < minimum_weight:
            continue
        for length in range(max(1, len(word) - MAX_SUFFIX), min(MAX_PREFIX, len(word) - 1) + 1):
            prefix, suffix = word[:length], word[length:]
            strong_prefixes.add(prefix)
            legacy_range = len(word) <= 8 and length <= 4 and len(suffix) <= 4
            pool = primary if legacy_range or length >= 5 else wider_base
            pool[prefix][suffix] = ("base", weight)
    known_anchors = {word for word, weight in base.items() if weight >= ANCHOR_MIN_WEIGHT}
    allowed = known_anchors | strong_prefixes
    # The only newly admitted single-Han continuations come from the strong
    # base dictionary's wider range. Low-frequency and uniform sources require
    # anchors of at least two Han; their one-character suffixes remain legal.
    extras: dict[str, dict[str, tuple[str, int]]] = defaultdict(dict, wider_base)
    for kind in SOURCE_KINDS:
        for word, weight in words[kind].items():
            if kind == "base" and weight < ANCHOR_MIN_WEIGHT:
                continue
            # Uniform-weight sources never expand single-character anchors.
            for length in range(max(2, len(word) - MAX_SUFFIX), min(MAX_PREFIX, len(word) - 1) + 1):
                prefix, suffix = word[:length], word[length:]
                if prefix not in allowed or suffix in primary.get(prefix, {}):
                    continue
                # Source priority, not accumulated uniform weight, chooses a
                # duplicate. Word-level pronunciation duplicates already use max.
                if suffix not in extras[prefix]:
                    extras[prefix][suffix] = (kind, weight)
    records = []
    retained_sources = Counter()
    specific_count = 0
    isolated_count = 0
    for prefix in sorted(primary.keys() | extras.keys(), key=lambda text: text.encode("utf-8")):
        main = primary.get(prefix, {})
        extra = extras.get(prefix, {})
        main_order = lambda item: (-item[1][1], item[0].encode("utf-8"))
        def extra_order(item):
            suffix, (kind, weight) = item
            # Standalone base-word support is a secondary ranking heuristic,
            # never P(word|prefix), and never adds extension weight to frequency.
            return (SOURCE_KINDS.index(kind), -weight if kind == "base" else 0,
                    -base.get(suffix, 0), len(suffix), suffix.encode("utf-8"))
        groups = []
        for single, limit in ((True, max_single), (False, max_multi)):
            old = sorted(((suffix, value) for suffix, value in main.items()
                          if (len(suffix) == 1) == single), key=main_order)[:limit]
            added = sorted(((suffix, value) for suffix, value in extra.items()
                            if (len(suffix) == 1) == single), key=extra_order)[:EXTRA_PER_GROUP]
            groups.append(tuple(suffix for suffix, _ in old + added))
            retained_sources.update(kind for _, (kind, _) in old + added)
        singles, multis = groups
        all_suffixes = main.keys() | extra.keys()
        isolated_count += len(all_suffixes) == 1
        family_count = continuation_families(all_suffixes)
        rare_anchor = ANCHOR_MIN_WEIGHT <= base.get(prefix, 0) < minimum_weight
        strong_prefix = prefix in strong_prefixes
        informative = any(suffix not in LOW_INFORMATION_PHRASES for suffix in multis)
        # This is a conservative lexical routing flag, not calibrated semantic
        # confidence: a literal multi-Han prefix, <=3 two-Han suffix families in
        # the complete pre-quota source pool, and known rare/strong evidence.
        specific = int(len(prefix) >= 2 and informative and family_count <= 3 and
                       (rare_anchor or strong_prefix))
        specific_count += specific
        records.append((prefix, singles, multis, specific))
    stats = {
        "eligible_source_rows_by_kind": dict(eligible_rows),
        "unique_source_words_by_kind": {kind: len(values) for kind, values in words.items()},
        "pruned_isolated_non_single_prefixes": 0,
        "isolated_prefixes_retained": isolated_count,
        "entries": len(records),
        "single_completions": sum(len(single) for _, single, _, _ in records),
        "multi_completions": sum(len(multi) for _, _, multi, _ in records),
        "retained_completions_by_kind": dict(retained_sources),
        "specific_continuation_prefixes": specific_count,
    }
    return records, stats


def continuation_families(suffixes: Iterable[str]) -> int:
    """Count the first two Han characters of all eligible source continuations.

    A single-character suffix is its own family. Generic particles and the
    display's grammatical chunks do not establish informative families. The
    complete pre-quota pool is counted, so source quotas never manufacture
    specificity. The count deliberately saturates at four, which means >3.
    """
    families = set()
    for suffix in suffixes:
        if suffix in LOW_INFORMATION_SINGLES or suffix in LOW_INFORMATION_PHRASES:
            continue
        families.add(suffix[:2])
        if len(families) > 3:
            return 4
    return len(families)


def encode(records: list[tuple[str, tuple[str, ...], tuple[str, ...], int]], metadata: dict,
           *, maximum_bytes: int = MAX_BYTES) -> bytes:
    lines = [MARKER, f"# entries: {len(records)}",
             "# " + json.dumps(metadata, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
             "# prefix<TAB>single-Han-suffix CSV<TAB>multi-Han-suffix CSV<TAB>specific-route 0|1; empty columns preserved"]
    for prefix, singles, multis, specific in records:
        line = prefix + "\t" + ",".join(singles) + "\t" + ",".join(multis) + "\t" + str(specific)
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
    hashes = {}
    for name, pinned_sha in SOURCES_SHA256.items():
        actual_sha = hashlib.sha256((data_root / name).read_bytes()).hexdigest()
        if actual_sha != pinned_sha or manifest.get("files_sha256", {}).get(name) != actual_sha:
            raise ValueError(f"Pinned public source checksum mismatch: {name}")
        hashes[name] = actual_sha
    records, stats = completion_records(dictionary_rows(data_root / SOURCE_NAME), other_rows={
        kind: dictionary_rows(data_root / f"cn_dicts/{kind}.dict.yaml", kind) for kind in ("ext", "tencent")})
    metadata = {
        "format": FORMAT,
        "license": "GPL-3.0; retain bundled usr/share/licenses/rime-ice/LICENSE and upstream data-source notices",
        "source_project": "https://github.com/iDvel/rime-ice",
        "source_revision": REVISION,
        "source_archive_sha256": ARCHIVE_SHA256,
        "sources_sha256": hashes,
        "generator": "scripts/build-axiang-nextword-completions.py",
        "rules": {"minimum_primary_base_weight": MIN_WEIGHT, "minimum_secondary_base_weight": ANCHOR_MIN_WEIGHT,
                  "word_han_length": [2, MAX_WORD],
                  "prefix_han_length": [1, MAX_PREFIX], "suffix_han_length": [1, MAX_SUFFIX],
                  "han_scope": "U+3400..U+4DBF, U+4E00..U+9FFF", "single_pool_limit": MAX_SINGLE,
                  "multi_pool_limit": MAX_MULTI, "additional_pool_limit_per_group": EXTRA_PER_GROUP,
                  "final_single_pool_limit": MAX_SINGLE + EXTRA_PER_GROUP,
                  "final_multi_pool_limit": MAX_MULTI + EXTRA_PER_GROUP,
                  "additional_anchor": "wide high-weight base proper prefix 1..6; low-weight base/ext/tencent: prefix >=2 Han and exact base word with weight >=100 OR high-weight base proper prefix",
                  "new_single_character_source": "only wider-range high-weight base; low-weight base/ext/tencent single-character expansion forbidden",
                  "isolated_prefixes": "retained when eligible by source/anchor rules",
                  "duplicate_word_weight": "maximum", "prefix_order": "UTF-8 ascending",
                  "primary_suffix_order": "base dictionary weight descending, UTF-8 ascending for ties",
                  "legacy_pool_preservation": "v1 word<=8/prefix<=4/suffix<=4 primary quotas and order retained; wider base continuations join additional quota; new prefix5..6 uses base primary quotas",
                  "additional_suffix_order": "base >ext >tencent; base weight only; standalone base suffix support descending; length; UTF-8",
                  "uniform_weights": "ext/tencent ignored for ranking frequency, never summed",
                  "specific_route": "prefix >=2 Han; informative multi; <=3 suffix first-two-Han families (single char is own family) across all eligible matches before quotas, ignoring explicit generic particles/chunks; rare exact base anchor weight 100..4999 OR primary-base proper prefix; not semantic probability",
                  "generic_display_gate": "specific-route may precede only an explicitly generic model-first candidate, never all first candidates",
                  "maximum_resource_bytes": MAX_BYTES, "maximum_record_bytes": MAX_ROW_BYTES},
        "statistics": stats,
        "limitations": "Dictionary weights/source specificity are ranking heuristics, not probabilities. Read-only lexical recall, not generated sentences; no user data, learned history or neural model. Uniform-weight extension/Tencent words supply bounded new recall without frequency inflation.",
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
