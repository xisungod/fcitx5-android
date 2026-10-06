#!/usr/bin/env python3
"""Public-data generator regressions; no network, Android or user logs required."""
import importlib.util
import json
from pathlib import Path
import hashlib
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("touch_language", Path(__file__).with_name("build-pinyin-touch-language.py"))
MODEL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODEL)


class PinyinTouchLanguageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        tables = self.root / "cn_dicts"
        tables.mkdir()
        fixture = {
            "8105": "基\tji\t40\n本\tben\t30\n经\tjing\t30\n常\tchang\t30\n会\thui\t30\n好\thao\t20\n你\tni\t20\n啊\ta\t10\n",
            "base": "基本\tji ben\t200\n经常会\tjing chang hui\t500\n你好啊\tni hao a\t100\n",
            "ext": "基本\tji ben\t100\n非法词\tinvalid code\t800\n",
            "tencent": "未注音\t90000\n",
            "xuancai_mobile": "",
            "others": "",
        }
        for name in MODEL.SOURCES:
            (tables / f"{name}.dict.yaml").write_text("# ignored\n---\nname: fixture\n...\n" + fixture[name], encoding="utf-8")
        self.manifest = self.root / "SOURCE.json"
        self.write_manifest()
        self.output = self.root / "model.tsv"

    def tearDown(self):
        self.temp.cleanup()

    def write_manifest(self):
        self.manifest.write_text(json.dumps({
            "revision": MODEL.REVISION, "archive_sha256": MODEL.ARCHIVE_SHA256,
            "files_sha256": {f"cn_dicts/{path.name}": hashlib.sha256(path.read_bytes()).hexdigest()
                             for path in (self.root / "cn_dicts").glob("*.dict.yaml")}
        }), encoding="utf-8")

    def rows(self):
        return {parts[1]: list(map(int, parts[2:]))
                for line in self.output.read_text().splitlines()
                if (parts := line.split("\t"))[0] == "P"}

    def test_deterministic_normalized_positive_small_table(self):
        first = MODEL.build(self.root, self.manifest, self.output)
        content = self.output.read_bytes()
        second = MODEL.build(self.root, self.manifest, self.output)
        self.assertEqual(content, self.output.read_bytes())
        self.assertEqual(first, second)
        for values in self.rows().values():
            self.assertEqual(len(values), 26)
            self.assertEqual(sum(values), MODEL.SCALE)
            self.assertGreater(min(values), 0)
        self.assertLess(first["bytes"], 100_000)

    def test_next_syllable_remains_possible_and_invalid_is_not_forbidden(self):
        MODEL.build(self.root, self.manifest, self.output)
        rows = self.rows()
        # ji + ben is an ordinary dictionary word; a syllable validity rule must
        # never erase b after ji. ch->a is strong, while ch->s still has a floor.
        self.assertGreater(rows["ji"][ord("b") - 97], rows["ji"][ord("x") - 97])
        self.assertGreater(rows["ch"][ord("a") - 97], rows["ch"][ord("s") - 97])
        self.assertGreater(rows["ch"][ord("s") - 97], 0)

    def test_deduplicate_and_skip_unknown_or_unannotated_rows(self):
        result = MODEL.build(self.root, self.manifest, self.output)
        self.assertEqual(result["unique_word_pronunciations"], 11)
        self.assertEqual(result["skipped_unknown_syllables"], 1)
        self.assertEqual(result["retained_rows_by_source"]["cn_dicts/tencent.dict.yaml"], 0)

    def test_checksum_and_revision_are_required(self):
        (self.root / "cn_dicts/base.dict.yaml").write_text("changed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            MODEL.build(self.root, self.manifest, self.output)
        self.write_manifest()
        manifest = json.loads(self.manifest.read_text())
        manifest["revision"] = "moving-branch"
        self.manifest.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "pinned public"):
            MODEL.build(self.root, self.manifest, self.output)

    def test_header_comments_malformed_weights_and_zero_are_handled(self):
        path = self.root / "single.dict.yaml"
        path.write_text("fake\tji\t99\n...\n# comment\tji\t9\n好\thao\t0\n坏\tji\t-1\n坏\tji\tnan\n坏\tJI\t9\n坏\tji  ben\t9\n", encoding="utf-8")
        self.assertEqual(list(MODEL.dictionary_rows(path)), [("好", ("hao",), 1)])


if __name__ == "__main__":
    unittest.main()
