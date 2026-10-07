"""Public synthetic checks for labels, fidelity and native-query boundaries."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent


def load_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


touch = load_module("typing_replay", "replay.py")
candidate = load_module("typing_candidate_replay", "candidate_replay.py")


class ReplayInputTest(unittest.TestCase):
    def setUp(self):
        self.document = json.loads((HERE / "synthetic-report.json").read_text())
        self.folder = tempfile.TemporaryDirectory()
        self.path = Path(self.folder.name) / "report.json"

    def tearDown(self):
        self.folder.cleanup()

    def read(self):
        self.path.write_text(json.dumps(self.document))
        return touch.load_report(self.path)

    def test_target_final_candidates_and_calibration_are_removed_before_model(self):
        report, normalized, skipped = self.read()
        self.assertFalse(skipped)
        self.assertEqual(set(normalized), {"trials"})
        for trial in normalized["trials"]:
            self.assertEqual(set(trial), {"ordinal", "taps"})
            for tap in trial["taps"]:
                self.assertEqual(set(tap), {"original", "down_x", "down_y", "density", "cells"})
        changed = copy.deepcopy(self.document)
        for trial in changed["trials"]:
            trial.update(target="other label", target_pinyin="differenttarget", final_pinyin="untrusted",
                         candidate_snapshot={"candidates": ["untrusted"]}, calibration_labels=[{"offset_x": 99}])
        self.document = changed
        self.assertEqual(normalized, self.read()[1])

    def test_v2_stage_and_suggestion_measurements_never_enter_model(self):
        original = self.read()[1]
        self.document["format"] = "axiang-typing-test-v2"
        self.document["trials"][0].update(send_key_ns=[999], touch_search_ns=[222],
            alternative_query_ns=[333], alternative_events=[{"kind": "Selected", "candidate_text": "label"}])
        self.assertEqual(original, self.read()[1])

    def test_mismatched_touch_letter_is_rejected(self):
        self.document["trials"][0]["first_attempt_touches"][0]["original"] = "z"
        with self.assertRaisesRegex(ValueError, "spelling does not match"):
            self.read()

    def test_missing_touch_is_explicitly_skipped_without_guessing(self):
        self.document["trials"][0]["first_attempt_touches"].pop()
        _, normalized, skipped = self.read()
        self.assertEqual(len(normalized["trials"]), 2)
        self.assertEqual(skipped, [{"ordinal": 0, "reason": "touch_count_mismatch"}])

    def test_missing_layout_is_rejected(self):
        self.document["trials"][0]["first_attempt_touches"][0]["layout"] = "unknown"
        with self.assertRaisesRegex(ValueError, "missing geometry"):
            self.read()

    def test_nonfinite_touch_is_rejected(self):
        self.document["trials"][0]["first_attempt_touches"][0]["down_x"] = float("nan")
        with self.assertRaisesRegex(ValueError, "finite number"):
            self.read()

    def test_duplicate_geometry_is_rejected(self):
        cells = self.document["layouts"]["synthetic"]["cells"]
        cells.append(copy.deepcopy(cells[0]))
        with self.assertRaisesRegex(ValueError, "Duplicate key"):
            self.read()

    def test_empty_or_unsupported_first_attempt_is_not_sent(self):
        self.document["trials"][0]["first_attempt_pinyin"] = "HELLO"
        _, _, skipped = self.read()
        self.assertEqual(skipped, [{"ordinal": 0, "reason": "unsupported_first_attempt"}])


class CandidateBoundaryTest(unittest.TestCase):
    def test_native_only_receives_spelling_labels_are_post_query_and_shared_cache_is_frozen(self):
        trial = {"prompt_id": 1, "literal_spelling": "nihaoa", "known_target_han": "你好啊",
                 "known_target_pinyin": "nihaoa", "steps": [{"proposal": None}]}
        doc = {"baseline": {"trials": [trial]}, "current": {"trials": [trial]}}
        calls = []
        def fake_query(spelling, binary, data, limit, timeout):
            calls.append((spelling, binary, data, limit, timeout))
            return {"candidates": [{"rank": 1, "text": "你好啊"}]}
        with patch.object(candidate.existing, "query_native", fake_query):
            results, count = candidate.replay(doc, Path("binary"), Path("data"), 3, 2)
        self.assertEqual(calls, [("nihaoa", Path("binary"), Path("data"), 3, 2)])
        self.assertEqual(count, 1)
        self.assertEqual([row["target_han_rank"] for row in results], [1, 1])

    def test_alternative_is_a_separate_native_query(self):
        doc = {"current": {"trials": [{"prompt_id": 1, "literal_spelling": "shiujianpan",
                 "known_target_han": "手机键盘", "known_target_pinyin": "shoujianpan",
                 "steps": [{"proposal": {"alternativeSpelling": "shoujianpan"}}]}]}}
        calls = []
        def fake_query(spelling, *args):
            calls.append(spelling)
            return {"candidates": []}
        with patch.object(candidate.existing, "query_native", fake_query):
            results, count = candidate.replay(doc, Path("binary"), Path("data"), 3, 2)
        self.assertEqual(calls, ["shiujianpan", "shoujianpan"])
        self.assertEqual(count, 2)
        self.assertEqual([row["path_kind"] for row in results], ["literal", "alternative"])


if __name__ == "__main__":
    unittest.main()
