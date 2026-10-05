#!/usr/bin/env python3
"""Integrity tests. Native Rime is mocked; every trace here is synthetic."""

import copy
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

import candidate_replay as replay


def trace(trace_id="one", typed="nihaoa", mode=True, status="ok"):
    return {"schema": 1, "kind": "replay", "trace_id": trace_id,
            "boundary_settling": mode, "typed": typed, "status": status,
            "recorded_mode": True, "geometry_verified": True, "matches_recorded_actions": True}


def annotation(identifier="one", trace_ids=None, target_han="你好啊", source="synthetic", status="confirmed"):
    return {"annotation_id": identifier, "trace_ids": trace_ids or [identifier],
            "status": status, "source": source, "target_pinyin": "nihaoa", "target_han": target_han}


def native(typed="nihaoa", texts=("你好啊", "怒号啊"), limit=100):
    return {"input": typed, "raw_input": typed, "input_unchanged": True,
            "all_handled": True, "unexpected_commit": False, "engine": "1.12.0", "schema": "rime_ice",
            "candidate_limit": limit, "candidates": [
                {"rank": index + 1, "text": text, "comment": "", "is_correction": False}
                for index, text in enumerate(texts)]}


class CandidateIsolationTest(unittest.TestCase):
    def test_native_receives_only_spelling_and_fresh_empty_user_directory(self):
        calls, user_paths = [], []

        def fake_run(command, **kwargs):
            calls.append(command)
            user = Path(command[2])
            self.assertTrue(user.is_dir())
            self.assertEqual([], list(user.iterdir()))
            user_paths.append(user)
            # Creating native data also verifies every distinct query starts empty.
            (user / "user.yaml").write_text("native data", encoding="utf-8")
            return SimpleNamespace(returncode=0, stdout=json.dumps(native(command[-1])) + "\n", stderr="")

        annotations = [annotation(target_han="只有评测才能知道的答案"), annotation("two", target_han="另一个不能泄漏的答案")]
        segments = replay.make_segments([trace(), trace("two", "xiaoguniabg")], annotations)
        with patch.object(replay.subprocess, "run", side_effect=fake_run):
            result = replay.replay_candidates(segments, Path("/fake/exporter"), Path("/fake/data"))
        self.assertEqual(2, len(calls))
        self.assertNotEqual(user_paths[0], user_paths[1])
        self.assertTrue(all(not path.exists() for path in user_paths))
        for command in calls:
            self.assertEqual(6, len(command))
            self.assertNotIn("只有评测才能知道的答案", " ".join(command))
            self.assertNotIn("另一个不能泄漏的答案", " ".join(command))
        self.assertTrue(all(row["targets_sent_to_decoder"] is False for row in result))
        self.assertTrue(all(row["native_input_preserved"] is True for row in result))

    def test_same_spelling_reuses_frozen_native_candidates_across_modes(self):
        segments = replay.make_segments([trace(mode=True), trace(mode=False)], [annotation()])
        with patch.object(replay, "query_native", return_value=native()) as query:
            result = replay.replay_candidates(segments, Path("/fake/exporter"), Path("/fake/data"))
        query.assert_called_once()
        self.assertEqual([True, False], [row["boundary_settling"] for row in result])
        self.assertEqual(result[0]["candidates"], result[1]["candidates"])

    def test_native_raw_input_change_commit_or_unhandled_rejected(self):
        for field, value in (("raw_input", "jin"), ("input_unchanged", False),
                             ("all_handled", False), ("unexpected_commit", True),
                             ("engine", "other"), ("schema", "other")):
            payload = native()
            payload[field] = value
            with patch.object(replay.subprocess, "run", return_value=SimpleNamespace(
                    returncode=0, stdout=json.dumps(payload), stderr="")):
                with self.assertRaises(RuntimeError, msg=field):
                    replay.query_native("nihaoa", Path("/fake"), Path("/data"), 100, 30)

    def test_native_invalid_candidate_rank_or_target_injection_is_not_repaired(self):
        payload = native(texts=("怒号啊",))
        payload["candidates"][0]["rank"] = 3
        with patch.object(replay.subprocess, "run", return_value=SimpleNamespace(
                returncode=0, stdout=json.dumps(payload), stderr="")):
            with self.assertRaises(RuntimeError):
                replay.query_native("nihaoa", Path("/fake"), Path("/data"), 100, 30)
        with patch.object(replay, "query_native", return_value=native(texts=("怒号啊",))):
            result = replay.replay_candidates(replay.make_segments([trace()], [annotation()]), Path("/fake"), Path("/data"))
        self.assertIsNone(result[0]["target_han_rank"])
        self.assertEqual(["怒号啊"], [row["text"] for row in result[0]["candidates"]])

    def test_unsupported_mixedcase_noop_punctuation_and_commands_never_query(self):
        traces = [trace(str(index), typed) for index, typed in enumerate(("", "NiHao", "你好", "ni'hao", "ni hao", "BackSpace", "rq", "uuid"))]
        with patch.object(replay, "query_native") as query:
            result = replay.replay_candidates(replay.make_segments(traces, []), Path("/fake"), Path("/data"))
        query.assert_not_called()
        self.assertEqual({"unsupported_input"}, {row["status"] for row in result})
        self.assertTrue(all(row["reason"] for row in result))


class SegmentsAndEvaluationTest(unittest.TestCase):
    def test_backspace_is_applied_only_inside_explicit_annotation_segment(self):
        def actions(*items):
            return [{"t": index, "type": "SymAction", "sym": 65288, "source": "Keyboard"}
                    if item == "<BS>" else {"t": index, "type": "FcitxKeyAction", "act": item,
                                            "source": "Keyboard", "states": 1 << 29}
                    for index, item in enumerate(items)]
        rows = [{**trace("one", "nb"), "actions": actions("n", "b")},
                {**trace("two", "ihaoa"), "actions": actions("<BS>", "i", "h", "a", "o", "a")}]
        segment = replay.make_segments(rows, [annotation("word", ["one", "two"])])[0]
        self.assertEqual("ready", segment["status"])
        self.assertEqual("nihaoa", segment["typed"])
        self.assertTrue(segment["action_reconstruction"])
        alone = replay.make_segments([rows[1]], [annotation("two")])[0]
        self.assertEqual("unsupported_input", alone["status"])
        with patch.object(replay, "query_native") as query:
            replay.replay_candidates([alone], Path("/fake"), Path("/data"))
        query.assert_not_called()

    def test_commit_boundary_modified_keys_and_mixed_legacy_actions_rejected(self):
        unsupported = [{"type": "CommitAction", "text": "nihaoa"},
                       {"type": "SymAction", "sym": 32},
                       {"type": "SymAction", "sym": 65293},
                       {"type": "FcitxKeyAction", "act": "n", "states": 1},
                       {"type": "FcitxKeyAction", "act": "N"},
                       {"type": "LayoutSwitchAction"}]
        for action in unsupported:
            segment = replay.make_segments([{**trace(), "actions": [action]}], [annotation()])[0]
            self.assertEqual("unsupported_input", segment["status"], action)
        rows = [{**trace("one", "n"), "actions": [{"type": "FcitxKeyAction", "act": "n"}]},
                trace("two", "ihaoa")]
        self.assertEqual("unsupported_input", replay.make_segments(rows, [annotation("word", ["one", "two"])])[0]["status"])

    def test_popup_letter_action_is_explicitly_excluded_from_native_query(self):
        rows = [{**trace(), "actions": [{"type": "FcitxKeyAction", "act": "n", "source": "Popup", "states": 1 << 29}]}]
        with patch.object(replay, "query_native") as query:
            result = replay.replay_candidates(replay.make_segments(rows, [annotation()]), Path("/fake"), Path("/data"))
        query.assert_not_called()
        self.assertEqual("unsupported_input", result[0]["status"])
        self.assertIn("popup", result[0]["reason"])

    def test_explicit_group_order_reconstructs_word_and_keeps_modes_separate(self):
        rows = [trace("n", "n", True), trace("i", "i", True), trace("h", "haoa", True),
                trace("n", "b", False), trace("i", "i", False), trace("h", "haoa", False)]
        target = annotation("word", ["n", "i", "h"])
        original = copy.deepcopy(rows)
        segments = replay.make_segments(rows, [target])
        self.assertEqual(["nihaoa", "bihaoa"], [row["typed"] for row in segments])
        self.assertEqual(original, rows)
        with patch.object(replay, "query_native", side_effect=lambda typed, *args: native(typed=typed)) as query:
            result = replay.replay_candidates(segments, Path("/fake"), Path("/data"))
        self.assertEqual(["nihaoa", "bihaoa"], [call.args[0] for call in query.call_args_list])
        self.assertTrue(result[0]["target_pinyin_matches"])
        self.assertFalse(result[1]["target_pinyin_matches"])

    def test_pending_and_unlabeled_are_not_scored_and_sources_not_merged(self):
        rows = [trace("manual"), trace("synthetic"), trace("pending"), trace("unknown")]
        labels = [annotation("manual", source="manual"), annotation("synthetic"),
                  annotation("pending", status="pending")]
        with patch.object(replay, "query_native", return_value=native()):
            result = replay.replay_candidates(replay.make_segments(rows, labels), Path("/fake"), Path("/data"))
        report = replay.summarize(result, 100)
        self.assertEqual(1, report["pending_segments"])
        self.assertEqual(1, report["unlabeled_segments"])
        self.assertEqual(2, len(report["groups"]))
        for group in report["groups"]:
            self.assertEqual(1, group["eligible_target_han_segments"])
            self.assertEqual(1, group["top1_hits"])
        for row in result[-2:]:
            self.assertNotIn("target_han_rank", row)

    def test_top1_top3_topN_and_missing_target_are_exact_native_ranks(self):
        labels = [annotation("one", target_han="第一"), annotation("three", target_han="第三"),
                  annotation("last", target_han="第四"), annotation("missing", target_han="不存在")]
        rows = [trace(label["annotation_id"]) for label in labels]
        with patch.object(replay, "query_native", return_value=native(texts=("第一", "第二", "第三", "第四"))):
            result = replay.replay_candidates(replay.make_segments(rows, labels), Path("/fake"), Path("/data"))
        group = next(group for group in replay.summarize(result, 100)["groups"] if group["annotation_source"] == "synthetic")
        self.assertEqual((1, 2, 3), (group["top1_hits"], group["top3_hits"], group["topN_hits"]))
        self.assertEqual(4, group["eligible_target_han_segments"])

    def test_missing_mode_and_failed_touch_replay_are_not_in_denominator(self):
        rows = [trace("one", status="cancelled"), {**trace("other", mode=False), "recorded_mode": False}]
        segments = replay.make_segments(rows, [annotation()])
        with patch.object(replay, "query_native", return_value=native()):
            result = replay.replay_candidates(segments, Path("/fake"), Path("/data"))
        annotated = [row for row in result if row["annotation_status"] == "confirmed"]
        self.assertEqual({"missing_replay", "skipped_replay"}, {row["status"] for row in annotated})
        self.assertTrue(all(group["eligible_target_han_segments"] == 0 for group in replay.summarize(result, 100)["groups"]))

    def test_annotation_document_and_legacy_jsonl_load_without_dropping_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            document = root / "annotations.json"
            document.write_text(json.dumps({"schema": 1, "source_sha256": "a" * 64, "annotations": [
                {"id": "word", "trace_ids": ["one", "two"], "target_pinyin": "nihaoa", "target_han": "你好啊",
                 "status": "confirmed", "provenance": "user_explicit", "note": "explicit target"}]}), encoding="utf-8")
            rows = replay.load_annotations(document, {"one", "two"})
            self.assertEqual("manual", rows[0]["source"])
            self.assertEqual("word", rows[0]["annotation_id"])
            self.assertEqual("a" * 64, rows[0]["annotation_document_source_sha256"])
            with self.assertRaises(ValueError):
                replay.load_annotations(document, {"one", "two"}, "b" * 64)
            self.assertEqual("manual", replay.load_annotations(document, {"one", "two"}, "a" * 64)[0]["source"])
            old = root / "old.jsonl"
            old.write_text(json.dumps({"schema": 1, "kind": "annotation", "trace_id": "one", "status": "pending",
                                       "target_pinyin": "", "target_han": "", "source": "synthetic"}) + "\n", encoding="utf-8")
            self.assertEqual("pending", replay.load_annotations(old, {"one"})[0]["status"])

    def test_overlapping_duplicate_or_unknown_annotations_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "annotations.jsonl"
            for labels in ([annotation(), annotation("two", ["one"])],
                           [annotation("one", ["missing"])], [annotation("one", ["one", "one"])]):
                path.write_text("".join(json.dumps({"schema": 1, "kind": "annotation", **row}) + "\n" for row in labels), encoding="utf-8")
                with self.assertRaises(ValueError):
                    replay.load_annotations(path, {"one", "two"})

    def test_modern_annotation_document_requires_source_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "labels.json"
            for claimed in (None, "", "wrong"):
                doc = {"schema": 1, "annotations": []}
                if claimed is not None:
                    doc["source_sha256"] = claimed
                path.write_text(json.dumps(doc))
                with self.assertRaisesRegex(ValueError, "source_sha256"):
                    replay.load_annotations(path, {"one"})

    def test_unbound_legacy_or_missing_replay_hash_never_queries_or_scores(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "labels.json"
            docs = [{"schema": 1, "kind": "annotation", "trace_id": "one", "status": "confirmed",
                     "target_pinyin": "nihaoa", "target_han": "你好啊", "source": "synthetic"},
                    {"schema": 1, "source_sha256": "a" * 64, "annotations": [{"id": "one", "trace_ids": ["one"],
                     "status": "confirmed", "provenance": "synthetic", "target_pinyin": "nihaoa", "target_han": "你好啊"}]}]
            for doc in docs:
                path.write_text(json.dumps(doc))
                labels = replay.load_annotations(path, {"one"})
                with patch.object(replay, "query_native") as query:
                    result = replay.replay_candidates(replay.make_segments([trace()], labels), Path("/fake"), Path("/data"))
                query.assert_not_called()
                self.assertEqual("unverified_annotation", result[0]["status"])
                self.assertEqual(0, sum(group["eligible_target_han_segments"] for group in replay.summarize(result, 100)["groups"]))

    def test_replay_requires_explicit_schema_and_unique_trace_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "replay.jsonl"
            invalid_schema = trace()
            invalid_schema["schema"] = True
            for rows in ([trace(), trace()], [invalid_schema], [{**trace(), "boundary_settling": 1}],
                         [{**trace(), "source_sha256": "a" * 64}, {**trace("two"), "source_sha256": "b" * 64}],
                         [{**trace(), "source_sha256": "a" * 64}, trace("two")]):
                path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
                with self.assertRaises(ValueError):
                    replay.load_replays(path)

    def test_non_ok_replay_can_omit_typed_without_aborting_other_segments(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "replays.jsonl"
            unsupported = trace("unsupported", status="unsupported_layout")
            del unsupported["typed"]
            path.write_text("".join(json.dumps(row) + "\n" for row in [trace(), unsupported]), encoding="utf-8")
            loaded = replay.load_replays(path)
            self.assertEqual("", loaded[1]["typed"])
            self.assertEqual("unsupported_layout", loaded[1]["status"])
            with patch.object(replay, "query_native", return_value=native()) as query:
                results = replay.replay_candidates(replay.make_segments(loaded, [annotation()]), Path("/fake"), Path("/data"))
            query.assert_called_once()
            self.assertEqual("ok", results[0]["status"])
            self.assertEqual("skipped_replay", results[1]["status"])
            self.assertEqual(["unsupported_layout"], results[1]["source_replay_statuses"])
            del loaded[0]["typed"]
            path.write_text(json.dumps(loaded[0]) + "\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                replay.load_replays(path)

    def test_failed_original_fidelity_excludes_both_modes_from_queries_and_metrics(self):
        for overrides in ({"matches_recorded_actions": False}, {"geometry_verified": False},
                          {"recorded_mode": None}, {"matches_recorded_actions": None}):
            rows = [{**trace(mode=True), **overrides}, trace(mode=False)]
            with patch.object(replay, "query_native") as query:
                result = replay.replay_candidates(replay.make_segments(rows, [annotation()]), Path("/fake"), Path("/data"))
            query.assert_not_called()
            self.assertTrue(all(row["status"].startswith("baseline_fidelity_") for row in result))
            report = replay.summarize(result, 100)
            self.assertEqual(2, report["baseline_fidelity_excluded_segments"])
            self.assertTrue(all(group["eligible_target_han_segments"] == 0 for group in report["groups"]))

    def test_alternative_mode_need_not_match_original_actions_when_baseline_matches(self):
        rows = [trace(mode=True), {**trace(mode=False), "matches_recorded_actions": False}]
        self.assertTrue(all(row["status"] == "ready" for row in replay.make_segments(rows, [annotation()])))

    def test_noncontiguous_reordered_cross_session_or_cross_mode_groups_rejected(self):
        rows = [trace("one"), trace("two"), trace("three")]
        for ids in (["one", "three"], ["two", "one"]):
            with self.assertRaises(ValueError):
                replay.make_segments(rows, [annotation("word", ids)])
        for extra in ({"session": "other"}, {"recorded_mode": False}):
            modified = [{**trace("one"), "session": "original"}, {**trace("two"), "session": "original", **extra}]
            with self.assertRaises(ValueError):
                replay.make_segments(modified, [annotation("word", ["one", "two"])])

    def test_known_synthetic_trace_cannot_inflate_manual_user_stats(self):
        # One arm carrying the source flag makes BOTH arms synthetic.
        rows = [{**trace(mode=True), "synthetic": True}, trace(mode=False)]
        with patch.object(replay, "query_native") as query:
            with self.assertRaises(ValueError):
                replay.replay_candidates(replay.make_segments(rows, [annotation(source="manual")]), Path("/fake"), Path("/data"))
        query.assert_not_called()
        with patch.object(replay, "query_native", return_value=native()):
            results = replay.replay_candidates(replay.make_segments(rows, [annotation()]), Path("/fake"), Path("/data"))
        self.assertTrue(all(row["synthetic"] for row in results))
        report = replay.summarize(results, 100)
        self.assertTrue(all(group["eligible_target_han_segments"] == 0 for group in report["groups"]
                            if group["annotation_source"] == "manual"))
        self.assertEqual(2, sum(group["eligible_target_han_segments"] for group in report["groups"]
                                if group["annotation_source"] == "synthetic"))

    def test_segment_mixing_known_synthetic_and_real_sources_is_rejected(self):
        rows = [{**trace("one"), "synthetic": True}, {**trace("two"), "synthetic": False}]
        for source in ("manual", "synthetic"):
            with self.assertRaises(ValueError):
                replay.make_segments(rows, [annotation("word", ["one", "two"], source=source)])


if __name__ == "__main__":
    unittest.main()
