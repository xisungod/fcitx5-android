#!/usr/bin/env python3
"""Local regressions for public-dictionary lexical completion extraction.

Synthetic checks exercise ranking, deduplication, codec boundaries and unsafe
inputs. The checked-in real resource must also contain the user's reported
missing continuation, with provenance and no hand-written prefix overrides.
Optional pinned-source arguments verify byte-for-byte reproducibility locally.
"""
import argparse
import hashlib
import importlib.util
import json
from collections import defaultdict
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("nextword_completions", Path(__file__).with_name("build-axiang-nextword-completions.py"))
MODEL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODEL)
RESOURCE = ROOT / "app/src/main/assets/typing/next_word_completions.tsv"
PINNED_DATA_ROOT = None
PINNED_MANIFEST = None


def decoded(records):
    return {prefix: (single, multi) for prefix, single, multi, specific in records}


def specificity(records):
    return {prefix: specific for prefix, single, multi, specific in records}


class CompletionExtractionTest(unittest.TestCase):
    def test_generic_prefixes_and_remaining_suffixes(self):
        rows = [("学习方法", 12), ("学习任务", 10), ("工作", 11), ("吃什么", 15)]
        records, _ = MODEL.completion_records(rows, minimum_weight=1)
        table = decoded(records)
        self.assertEqual(table["学习"], ((), ("方法", "任务")))
        self.assertEqual(table["学"][1], ("习方法", "习任务"))
        self.assertEqual(table["工"], (("作",), ()))
        self.assertEqual(table["吃"], ((), ("什么",)))
        self.assertNotIn("学习方法", table)  # never an empty completion

    def test_duplicate_pronunciations_use_maximum_not_sum(self):
        records, stats = MODEL.completion_records(
            [("学校", 12), ("学校", 12), ("学习", 20), ("学业", 20)],
            minimum_weight=1, max_single=2)
        self.assertEqual(stats["unique_source_words_by_kind"]["base"], 3)
        self.assertEqual(stats["eligible_source_rows_by_kind"]["base"], 4)
        # Adding a duplicate must not promote 校 above the two weight-20 words.
        self.assertEqual(decoded(records)["学"][0], ("业", "习"))

    def test_separate_bounded_pools_preserve_multi_character_recall(self):
        rows = [("甲" + chr(0x4e10 + n), 1000 - n) for n in range(12)]
        rows += [("甲怎样", 20), ("甲事情", 10), ("甲准备", 5)]
        records, _ = MODEL.completion_records(rows, minimum_weight=1, max_single=4, max_multi=2)
        singles, multis = decoded(records)["甲"]
        self.assertEqual(len(singles), 4)
        self.assertEqual(multis, ("怎样", "事情"))

    def test_threshold_lengths_and_non_han_inputs_are_bounded(self):
        rows = [("工作", 20), ("工事", 9), ("工", 30), ("工A", 30),
                ("工，作", 30), ("工\U0001f600", 30), ("工\U00020000", 30), ("工\u4dc0", 30),
                ("一二三四五六七八九十天地", 20), ("一二三四五六甲乙丙丁戊己", 20),
                ("一二三四五六七八九十天地人", 30)]
        records, _ = MODEL.completion_records(rows, minimum_weight=10)
        table = decoded(records)
        self.assertEqual(table["工"], (("作",), ()))
        self.assertEqual(table["一二三四五六"][1], ("七八九十天地", "甲乙丙丁戊己"))
        for prefix, singles, multis, specific in records:
            self.assertTrue(MODEL.pure_han(prefix))
            self.assertLessEqual(len(prefix), MODEL.MAX_PREFIX)
            for suffix in singles + multis:
                self.assertTrue(MODEL.pure_han(suffix))
                self.assertLessEqual(len(suffix), MODEL.MAX_SUFFIX)
                self.assertLessEqual(len(prefix + suffix), MODEL.MAX_WORD)
                self.assertNotEqual(prefix + suffix, "一二三四五六七八九十天地人")

    def test_isolated_source_backed_prefixes_are_preserved(self):
        records, stats = MODEL.completion_records(
            [("你好世界", 50), ("你好朋友", 30)], minimum_weight=1)
        table = decoded(records)
        self.assertIn("你", table)
        self.assertEqual(table["你好"][1], ("世界", "朋友"))
        self.assertEqual(table["你好世"][0], ("界",))
        self.assertEqual(table["你好朋"][0], ("友",))
        self.assertEqual(stats["pruned_isolated_non_single_prefixes"], 0)
        self.assertEqual(stats["isolated_prefixes_retained"], 2)

    def test_order_is_deterministic_with_equal_weights_and_shuffled_rows(self):
        rows = [("学习", 30), ("学校", 20), ("学业", 30), ("工作", 25), ("工事", 25)]
        first, stats = MODEL.completion_records(rows, minimum_weight=1)
        second, stats2 = MODEL.completion_records(reversed(rows), minimum_weight=1)
        self.assertEqual(first, second)
        self.assertEqual(stats, stats2)
        self.assertEqual(MODEL.encode(first, {"format": MODEL.FORMAT}),
                         MODEL.encode(second, {"format": MODEL.FORMAT}))

    def test_yaml_comments_missing_weights_and_invalid_weights_are_excluded(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "fixture.yaml"
            path.write_text("不是词\tbu shi ci\t999\n---\n...\n"
                            "# 注释\tzhu shi\t100\n学习\txue xi\t20\n"
                            "学校\txue xiao\tnan\n学业\txue ye\t-1\n"
                            "学科\txue ke\t0\n学习\txue xi\n"
                            "学问\txue wen\t4294967296\n", encoding="utf-8")
            self.assertEqual(list(MODEL.dictionary_rows(path)), [("学习", 20)])
            path.write_bytes(b"...\n\xff\n")
            with self.assertRaises(UnicodeDecodeError):
                list(MODEL.dictionary_rows(path))

    def test_codec_preserves_empty_columns_and_enforces_byte_budget(self):
        records = [("工", ("作",), (), 0), ("学", (), ("习方法",), 0)]
        content = MODEL.encode(records, {"format": MODEL.FORMAT})
        lines = content.decode("utf-8").splitlines()
        self.assertEqual(lines[0], MODEL.MARKER)
        self.assertEqual(lines[1], "# entries: 2")
        self.assertEqual(lines[-2].split("\t"), ["工", "作", "", "0"])
        self.assertEqual(lines[-1].split("\t"), ["学", "", "习方法", "0"])
        self.assertTrue(content.endswith(b"\n"))
        with self.assertRaisesRegex(ValueError, "byte budget"):
            MODEL.encode(records, {}, maximum_bytes=100)
        with self.assertRaises(ValueError):
            MODEL.completion_records([], minimum_weight=0)

    def test_unpinned_source_cannot_overwrite_existing_resource(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "cn_dicts").mkdir()
            source = root / MODEL.SOURCE_NAME
            source.write_text("...\n学习\txue xi\t6000\n", encoding="utf-8")
            manifest = root / "SOURCE.json"
            manifest.write_text(json.dumps({"revision": MODEL.REVISION,
                "archive_sha256": MODEL.ARCHIVE_SHA256,
                "files_sha256": {MODEL.SOURCE_NAME: hashlib.sha256(source.read_bytes()).hexdigest()}}))
            output = root / "output.tsv"
            output.write_bytes(b"preserve existing data")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                MODEL.build(root, manifest, output)
            self.assertEqual(output.read_bytes(), b"preserve existing data")
            manifest.write_text(json.dumps({"revision": "unreviewed-latest"}))
            with self.assertRaisesRegex(ValueError, "pinned public"):
                MODEL.build(root, manifest, output)

    def test_known_low_weight_anchor_can_recall_unique_tencent_phrase(self):
        records, _ = MODEL.completion_records([("天青色", 575)],
            other_rows={"tencent": [("天青色等烟雨", 100)]})
        self.assertEqual(decoded(records)["天青色"], ((), ("等烟雨",)))
        self.assertEqual(specificity(records)["天青色"], 1)

    def test_uniform_source_weights_cannot_inflate_or_reorder_frequency(self):
        base = [("清风", 575), ("明月", 10_000)]
        first, _ = MODEL.completion_records(base, other_rows={
            "ext": [("清风徐来", 100)], "tencent": [("清风明月", 100)]})
        second, _ = MODEL.completion_records(base, other_rows={
            "ext": [("清风徐来", 900_000)], "tencent": [("清风明月", 1_000_000)]})
        self.assertEqual(first, second)
        self.assertEqual(decoded(first)["清风"][1], ("徐来", "明月"))

    def test_base_primary_source_wins_duplicates_without_additive_mass(self):
        base = [("一帆风顺", 36_520)]
        first, stats = MODEL.completion_records(base, other_rows={
            "ext": [("一帆风顺", 100), ("一帆顺风", 100)],
            "tencent": [("一帆风顺", 100), ("一帆风顺的", 100)]})
        self.assertEqual(decoded(first)["一帆"][1][0], "风顺")
        self.assertEqual(decoded(first)["一帆"][1].count("风顺"), 1)
        self.assertGreater(stats["retained_completions_by_kind"]["base"], 0)
        self.assertEqual(specificity(first)["一帆"], 1)

    def test_uniform_sources_do_not_expand_single_character_anchors(self):
        base = [("做饭", 20_000)]
        first, _ = MODEL.completion_records(base)
        second, _ = MODEL.completion_records(base, other_rows={
            "ext": [("做咖啡", 100), ("做准备", 100)],
            "tencent": [("做作业", 100), ("做什么", 100)]})
        self.assertEqual(decoded(first)["做"], decoded(second)["做"])

    def test_new_sources_cannot_evict_legacy_primary_pools(self):
        prefix = "甲乙"
        base = [(prefix + chr(0x4e10 + n), 10_000 - n) for n in range(36)]
        base += [(prefix + "事" + chr(0x5000 + n), 9_000 - n) for n in range(80)]
        old, _ = MODEL.completion_records(base)
        extra = [(prefix + "扩" + chr(0x6000 + n), 100) for n in range(15)]
        new, _ = MODEL.completion_records(base, other_rows={"ext": extra})
        old_single, old_multi = decoded(old)[prefix]
        new_single, new_multi = decoded(new)[prefix]
        self.assertEqual(new_single[:MODEL.MAX_SINGLE], old_single)
        self.assertEqual(new_multi[:MODEL.MAX_MULTI], old_multi)
        self.assertEqual(len(new_multi), MODEL.MAX_MULTI + MODEL.EXTRA_PER_GROUP)

    def test_specificity_uses_complete_pool_before_extra_quota(self):
        anchor = "天空"
        extra = [(anchor + "电影" + chr(0x4e10 + n), 100) for n in range(15)]
        extra += [(anchor + suffix, 100) for suffix in ["新闻报道来源", "文学作品欣赏", "科技知识应用"]]
        records, _ = MODEL.completion_records([(anchor, 575)], other_rows={"tencent": extra})
        # The first ten kept words all start with 电影. Three other eligible
        # branches outside the quota must still prevent a specificity claim.
        self.assertTrue(all(word.startswith("电影") for word in decoded(records)[anchor][1]))
        self.assertEqual(specificity(records)[anchor], 0)

    def test_two_character_families_are_not_semantic_probabilities(self):
        self.assertEqual(MODEL.continuation_families(["风顺", "风顺的", "风顺的人生", "顺风"]), 2)
        self.assertEqual(MODEL.continuation_families(["的", "一个", "的感觉", "的身体", "的方向"]), 3)
        self.assertEqual(MODEL.continuation_families(["的感觉", "的身体", "的方向", "的消息"]), 4)

    def test_unknown_or_below_threshold_anchors_cannot_admit_uniform_words(self):
        missing, _ = MODEL.completion_records([("天空", 99)],
            other_rows={"tencent": [("天空之城", 100), ("陌生补全", 100)]})
        self.assertNotIn("天空", decoded(missing))
        self.assertNotIn("陌生", decoded(missing))
        admitted, _ = MODEL.completion_records([("天空", 100)],
            other_rows={"tencent": [("天空之城", 100)]})
        self.assertIn("天空", decoded(admitted))

    def test_only_generic_multi_chunks_do_not_get_specific_route(self):
        records, _ = MODEL.completion_records([("天空", 575)],
            other_rows={"tencent": [("天空一个", 100)]})
        self.assertEqual(decoded(records)["天空"][1], ("一个",))
        self.assertEqual(specificity(records)["天空"], 0)

    def test_tencent_two_column_format_is_parsed_without_guessing_pronunciation(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "fixture.yaml"
            path.write_text("...\n天青色等烟雨\t100\n无效条目\tnan\n"
                            "多列条目\tduo lie tiao mu\t100\n", encoding="utf-8")
            self.assertEqual(list(MODEL.dictionary_rows(path, "tencent")), [("天青色等烟雨", 100)])


class PublicCompletionResourceTest(unittest.TestCase):
    def test_resource_provenance_codec_and_real_missing_continuations(self):
        content = RESOURCE.read_bytes()
        self.assertLessEqual(len(content), MODEL.MAX_BYTES)
        self.assertTrue(content.endswith(b"\n"))
        lines = content.decode("utf-8", errors="strict").splitlines()
        self.assertEqual(lines[0], MODEL.MARKER)
        metadata = json.loads(lines[2][2:])
        self.assertEqual(metadata["format"], MODEL.FORMAT)
        self.assertEqual(metadata["source_revision"], MODEL.REVISION)
        self.assertEqual(metadata["sources_sha256"], MODEL.SOURCES_SHA256)
        self.assertIn("GPL-3.0", metadata["license"])
        entries = [line.split("\t") for line in lines if line and not line.startswith("#")]
        self.assertEqual(lines[1], f"# entries: {len(entries)}")
        self.assertEqual(metadata["statistics"]["entries"], len(entries))
        self.assertEqual([entry[0].encode() for entry in entries],
                         sorted(set(entry[0].encode() for entry in entries)))
        table = {}
        for entry in entries:
            self.assertEqual(len(entry), 4)
            prefix, single, multi, specific = entry
            self.assertTrue(MODEL.pure_han(prefix))
            self.assertLessEqual(len(prefix), MODEL.MAX_PREFIX)
            self.assertIn(specific, ("0", "1"))
            groups = [tuple(single.split(",")) if single else (), tuple(multi.split(",")) if multi else ()]
            self.assertLessEqual(len(groups[0]), MODEL.MAX_SINGLE + MODEL.EXTRA_PER_GROUP)
            self.assertLessEqual(len(groups[1]), MODEL.MAX_MULTI + MODEL.EXTRA_PER_GROUP)
            self.assertLessEqual(len("\t".join(entry).encode()), MODEL.MAX_ROW_BYTES)
            for group, suffixes in enumerate(groups):
                self.assertEqual(len(suffixes), len(set(suffixes)))
                for suffix in suffixes:
                    self.assertTrue(MODEL.pure_han(suffix))
                    if group == 0:
                        self.assertEqual(len(suffix), 1)
                    else:
                        self.assertTrue(2 <= len(suffix) <= MODEL.MAX_SUFFIX)
            table[prefix] = groups
        self.assertIn("饭", table["做"][0])
        for suffix in ["什么", "作业", "准备"]:
            self.assertIn(suffix, table["做"][1])
        self.assertIn("习", table["学"][0])
        self.assertIn("作", table["工"][0])
        self.assertIn("什么", table["吃"][1])
        self.assertEqual(table["天青色"][1][0], "等烟雨")
        self.assertEqual(table["一帆"][1][0], "风顺")

    def test_optional_pinned_source_reproduces_checked_in_asset(self):
        if PINNED_DATA_ROOT is None:
            self.skipTest("Pass pinned local source arguments for a real-data regeneration check")
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "generated.tsv"
            first = MODEL.build(PINNED_DATA_ROOT, PINNED_MANIFEST, output)
            self.assertEqual(output.read_bytes(), RESOURCE.read_bytes())
            second = MODEL.build(PINNED_DATA_ROOT, PINNED_MANIFEST, output)
            self.assertEqual(first, second)
            self.assertEqual(output.read_bytes(), RESOURCE.read_bytes())

    def test_optional_v1_base_pools_cannot_be_evicted_by_wider_new_sources(self):
        if PINNED_DATA_ROOT is None:
            self.skipTest("Pass pinned local source arguments for legacy pool verification")
        # Reconstruct the fixed v1 rules independently from the pinned public
        # base table. HEAD is deliberately unused: a published v2 checkout must
        # keep testing against v1, rather than accidentally comparing to itself.
        words = {}
        for word, weight in MODEL.dictionary_rows(PINNED_DATA_ROOT / MODEL.SOURCE_NAME):
            if weight >= 5000 and 2 <= len(word) <= 8 and MODEL.pure_han(word):
                words[word] = max(words.get(word, 0), weight)
        legacy = defaultdict(dict)
        for word, weight in words.items():
            for length in range(1, min(4, len(word) - 1) + 1):
                suffix = word[length:]
                if len(suffix) <= 4:
                    legacy[word[:length]][suffix] = weight
        current = {}
        for line in RESOURCE.read_text(encoding="utf-8").splitlines():
            if line.startswith("#"):
                continue
            prefix, single, multi, flag = line.split("\t")
            current[prefix] = (single.split(",") if single else [], multi.split(",") if multi else [])
        checked = 0
        for prefix, group in legacy.items():
            if len(prefix) > 1 and len(group) < 2:
                continue  # v1's fixed eligibility; v2 may add these sparse keys
            self.assertIn(prefix, current)
            for index, cap in [(0, 24), (1, 64)]:
                old = sorted((suffix for suffix in group if (len(suffix) == 1) == (index == 0)),
                             key=lambda suffix: (-group[suffix], suffix.encode()))[:cap]
                self.assertEqual(current[prefix][index][:len(old)], old,
                                 f"Legacy base pool was changed for prefix {prefix!r}")
            checked += 1
        self.assertEqual(checked, 15_160)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-root", type=Path)
    parser.add_argument("--source-manifest", type=Path)
    args, unittest_args = parser.parse_known_args()
    PINNED_DATA_ROOT = args.data_root
    PINNED_MANIFEST = args.source_manifest or (args.data_root / "SOURCE.json" if args.data_root else None)
    unittest.main(argv=[sys.argv[0], *unittest_args])
