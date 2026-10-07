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
    return {prefix: (single, multi) for prefix, single, multi in records}


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
        self.assertEqual(stats["unique_source_words"], 3)
        self.assertEqual(stats["eligible_source_rows"], 4)
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
                ("一二三四五六七八", 20), ("一二三四甲乙丙丁", 20),
                ("一二三四五六七八九", 30)]
        records, _ = MODEL.completion_records(rows, minimum_weight=10)
        table = decoded(records)
        self.assertEqual(table["工"], (("作",), ()))
        self.assertEqual(table["一二三四"][1], ("五六七八", "甲乙丙丁"))
        for prefix, singles, multis in records:
            self.assertTrue(MODEL.pure_han(prefix))
            self.assertLessEqual(len(prefix), 4)
            for suffix in singles + multis:
                self.assertTrue(MODEL.pure_han(suffix))
                self.assertLessEqual(len(suffix), 4)
                self.assertLessEqual(len(prefix + suffix), 8)
                self.assertNotEqual(prefix + suffix, "一二三四五六七八九")

    def test_isolated_long_prefixes_are_pruned_but_single_prefixes_remain(self):
        records, stats = MODEL.completion_records(
            [("你好世界", 50), ("你好朋友", 30)], minimum_weight=1)
        table = decoded(records)
        self.assertIn("你", table)
        self.assertEqual(table["你好"][1], ("世界", "朋友"))
        self.assertNotIn("你好世", table)
        self.assertNotIn("你好朋", table)
        self.assertEqual(stats["pruned_isolated_non_single_prefixes"], 2)

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
        records = [("工", ("作",), ()), ("学", (), ("习方法",))]
        content = MODEL.encode(records, {"format": MODEL.FORMAT})
        lines = content.decode("utf-8").splitlines()
        self.assertEqual(lines[0], MODEL.MARKER)
        self.assertEqual(lines[1], "# entries: 2")
        self.assertEqual(lines[-2].split("\t"), ["工", "作", ""])
        self.assertEqual(lines[-1].split("\t"), ["学", "", "习方法"])
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


class PublicCompletionResourceTest(unittest.TestCase):
    def test_resource_provenance_codec_and_real_missing_continuations(self):
        content = RESOURCE.read_bytes()
        self.assertLess(len(content), 1_000_000)
        self.assertTrue(content.endswith(b"\n"))
        lines = content.decode("utf-8", errors="strict").splitlines()
        self.assertEqual(lines[0], MODEL.MARKER)
        metadata = json.loads(lines[2][2:])
        self.assertEqual(metadata["format"], MODEL.FORMAT)
        self.assertEqual(metadata["source_revision"], MODEL.REVISION)
        self.assertEqual(metadata["sources_sha256"][MODEL.SOURCE_NAME], MODEL.BASE_SHA256)
        self.assertIn("GPL-3.0", metadata["license"])
        entries = [line.split("\t") for line in lines if line and not line.startswith("#")]
        self.assertEqual(lines[1], f"# entries: {len(entries)}")
        self.assertEqual(metadata["statistics"]["entries"], len(entries))
        self.assertEqual([entry[0].encode() for entry in entries],
                         sorted(set(entry[0].encode() for entry in entries)))
        table = {}
        for entry in entries:
            self.assertEqual(len(entry), 3)
            prefix, single, multi = entry
            self.assertTrue(MODEL.pure_han(prefix))
            self.assertLessEqual(len(prefix), 4)
            groups = [tuple(single.split(",")) if single else (), tuple(multi.split(",")) if multi else ()]
            self.assertLessEqual(len(groups[0]), MODEL.MAX_SINGLE)
            self.assertLessEqual(len(groups[1]), MODEL.MAX_MULTI)
            self.assertLessEqual(len("\t".join(entry).encode()), MODEL.MAX_ROW_BYTES)
            for group, suffixes in enumerate(groups):
                self.assertEqual(len(suffixes), len(set(suffixes)))
                for suffix in suffixes:
                    self.assertTrue(MODEL.pure_han(suffix))
                    if group == 0:
                        self.assertEqual(len(suffix), 1)
                    else:
                        self.assertTrue(2 <= len(suffix) <= 4)
            table[prefix] = groups
        self.assertIn("饭", table["做"][0])
        for suffix in ["什么", "作业", "准备"]:
            self.assertIn(suffix, table["做"][1])
        self.assertIn("习", table["学"][0])
        self.assertIn("作", table["工"][0])
        self.assertIn("什么", table["吃"][1])

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


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-root", type=Path)
    parser.add_argument("--source-manifest", type=Path)
    args, unittest_args = parser.parse_known_args()
    PINNED_DATA_ROOT = args.data_root
    PINNED_MANIFEST = args.source_manifest or (args.data_root / "SOURCE.json" if args.data_root else None)
    unittest.main(argv=[sys.argv[0], *unittest_args])
