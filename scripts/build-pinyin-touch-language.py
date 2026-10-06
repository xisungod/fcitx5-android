#!/usr/bin/env python3
"""Build AXiang's small, soft next-letter model from pinned public Rime tables.

This is a character transition model, not a second Pinyin decoder. Spaces in
annotated dictionary spellings are removed so ji + ben remains possible after
ji. Missing transitions are interpolated with shorter contexts and a uniform
prior; no next letter is forbidden. Unannotated rows are intentionally skipped
instead of guessing pronunciations. User dictionaries are never inputs.

The generated table is derived from Rime Ice's existing GPL-3.0 data. The source
revision, SHA-256s, retained row counts, and smoothing parameters are embedded in
the output. Generation has no network access and is byte deterministic.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re
from typing import Iterable

REVISION = "3aea6d3694fb3d94ec663641f021f788822897ad"
ARCHIVE_SHA256 = "a170d79442f7463118fbfde089f62d046710475436e3c5347de2cf1aeaa95b48"
SOURCES = ("8105", "base", "ext", "tencent", "xuancai_mobile", "others")
ALPHABET = "abcdefghijklmnopqrstuvwxyz"
SCALE = 32768
UNIFORM_MIX = 0.05
BIGRAM_MIX = 0.60
TRIGRAM_MIX = 0.75
MAX_WEIGHT = 1_000_000
SPELLING = re.compile(r"[a-z]+(?: [a-z]+)*\Z")


def dictionary_rows(path: Path) -> Iterable[tuple[str, tuple[str, ...], int]]:
    """Return only explicit full, lowercase pronunciations after the YAML header."""
    in_body = False
    with path.open(encoding="utf-8") as source:
        for line in source:
            line = line.rstrip("\r\n")
            if line == "...":
                in_body = True
                continue
            if not in_body or not line or line.startswith("#"):
                continue
            columns = line.split("\t")
            if len(columns) < 3 or not columns[0] or not SPELLING.fullmatch(columns[1]):
                continue
            try:
                weight = int(columns[2])
            except ValueError:
                continue
            if weight < 0:
                continue
            yield columns[0], tuple(columns[1].split(" ")), min(MAX_WEIGHT, max(1, weight))


def normalized(counts: Counter[str]) -> list[float]:
    total = sum(counts.values())
    if not total:
        return [1.0 / 26] * 26
    return [counts[letter] / total for letter in ALPHABET]


def interpolate(local: list[float], backoff: list[float], mix: float) -> list[float]:
    return [mix * x + (1.0 - mix) * y for x, y in zip(local, backoff)]


def quantize(probabilities: list[float]) -> list[int]:
    """Largest-remainder quantization; every row sums to SCALE exactly."""
    scaled = [probability * SCALE for probability in probabilities]
    values = [int(value) for value in scaled]
    remainder = SCALE - sum(values)
    order = sorted(range(26), key=lambda index: (-(scaled[index] - values[index]), index))
    for index in order[:remainder]:
        values[index] += 1
    assert len(values) == 26 and min(values) > 0 and sum(values) == SCALE
    return values


def build(data_root: Path, source_manifest: Path, output: Path) -> dict:
    manifest = json.loads(source_manifest.read_text(encoding="utf-8"))
    if manifest.get("revision") != REVISION or manifest.get("archive_sha256") != ARCHIVE_SHA256:
        raise ValueError("Only the pinned public Rime Ice revision may train this model")
    paths = [data_root / "cn_dicts" / f"{name}.dict.yaml" for name in SOURCES]
    hashes = {}
    for path in paths:
        relative = f"cn_dicts/{path.name}"
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if manifest.get("files_sha256", {}).get(relative) != digest:
            raise ValueError(f"Source checksum mismatch: {relative}")
        hashes[relative] = digest

    # The common-character table supplies full-syllable inventory, including the
    # same rare/full spellings the current Rime dictionary already supports.
    syllables = {parts[0] for _, parts, _ in dictionary_rows(paths[0]) if len(parts) == 1}
    if not syllables:
        raise ValueError("The common-character table has no annotated syllables")
    unique = {}
    source_counts = {}
    skipped_unknown_syllables = 0
    for path in paths:
        retained = 0
        for word, parts, weight in dictionary_rows(path):
            if any(part not in syllables for part in parts):
                skipped_unknown_syllables += 1
                continue
            # The same word/pronunciation may occur in multiple public tables.
            # Keep its largest source weight rather than multiplying duplicates.
            key = (word, parts)
            unique[key] = max(unique.get(key, 0), weight)
            retained += 1
        source_counts[f"cn_dicts/{path.name}"] = retained

    transitions = defaultdict(Counter)
    syllable_weights = Counter()
    for (_, parts), weight in sorted(unique.items()):
        code = "".join(parts)
        for part in parts:
            syllable_weights[part] += weight
        for index, letter in enumerate(code):
            transitions["-"][letter] += weight
            transitions["^" if index == 0 else code[index - 1]][letter] += weight
            if index == 1:
                transitions["^" + code[0]][letter] += weight
            elif index >= 2:
                transitions[code[index - 2:index]][letter] += weight

    unigram = normalized(transitions["-"])
    probabilities = {"-": unigram}
    for context in sorted(transitions, key=lambda value: (len(value), value)):
        if context == "-":
            continue
        local = normalized(transitions[context])
        if len(context) == 1:
            probabilities[context] = interpolate(local, unigram, BIGRAM_MIX)
        else:
            probabilities[context] = interpolate(local, probabilities.get(context[-1], unigram), TRIGRAM_MIX)

    metadata = {
        "format": "AXPL1",
        "license": "GPL-3.0-only; derived from the pinned Rime Ice tables; see bundled rime-ice LICENSE/README.md",
        "source_project": "https://github.com/iDvel/rime-ice",
        "source_revision": REVISION,
        "source_archive_sha256": ARCHIVE_SHA256,
        "sources_sha256": hashes,
        "retained_rows_by_source": source_counts,
        "unique_word_pronunciations": len(unique),
        "skipped_unknown_syllables": skipped_unknown_syllables,
        "weight": "integer dictionary weight, clipped to [1,1000000]; duplicate word/pronunciation uses maximum",
        "smoothing": {"bigram_mix": BIGRAM_MIX, "trigram_mix": TRIGRAM_MIX, "uniform_mix": UNIFORM_MIX},
        "probability_scale": SCALE,
        "contexts": len(probabilities),
        "syllables": len(syllables),
        "limitations": "Dictionary weights are ranking heuristics, not calibrated real typing probabilities; no user data or abbreviation invalidation",
    }
    lines = ["# AXPL1\tsoft public-dictionary next-letter probabilities",
             "# " + json.dumps(metadata, sort_keys=True, separators=(",", ":"), ensure_ascii=False),
             "# P\tcontext\t26 positive integer probabilities (a-z), sum=32768; -=global, ^=word start",
             "# S\tfull-syllable\tpublic dictionary weight; inventory only, never a hard input filter"]
    uniform = [1.0 / 26] * 26
    for context in sorted(probabilities):
        values = quantize(interpolate(probabilities[context], uniform, 1.0 - UNIFORM_MIX))
        lines.append("P\t" + context + "\t" + "\t".join(map(str, values)))
    for syllable in sorted(syllables):
        lines.append(f"S\t{syllable}\t{syllable_weights[syllable]}")
    content = ("\n".join(lines) + "\n").encode("utf-8")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(content)
    return {**metadata, "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()}


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-root", type=Path, default=root / "plugin/rime/src/main/cpp/xuancai-rime-ice")
    parser.add_argument("--source-manifest", type=Path, help="Pinned Rime Ice SOURCE.json (defaults to data root)")
    parser.add_argument("--output", type=Path, default=root / "app/src/main/assets/typing/pinyin_touch_model.tsv")
    args = parser.parse_args()
    result = build(args.data_root, args.source_manifest or args.data_root / "SOURCE.json", args.output)
    print(json.dumps(result, sort_keys=True, ensure_ascii=False))


if __name__ == "__main__":
    main()
