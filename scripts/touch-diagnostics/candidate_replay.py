#!/usr/bin/env python3
"""Replay frozen touch-output spellings against native Rime, without labels.

This is deliberately separate from touch replay. It neither changes spelling
nor interprets touch coordinates. Every native query has an empty user directory
and no committed context; results cannot reproduce an installed user's learned
dictionary or context. All timings are host measurements, not handset latency.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile


ASCII_SPELLING = re.compile(r"[a-z]{1,256}\Z")
# Current AXiang date/lunar/UUID commands are not reproducible spelling queries.
NONDETERMINISTIC_COMMANDS = {"rq", "rqen", "rqzh", "sj", "xq", "dt", "ts", "nl", "uuid"}
CONTEXT_LIMITATION = (
    "Fresh empty user dictionary and no committed context. This replay does not "
    "reproduce installed-user learning, preceding text, or phone UI latency."
)


def read_jsonl(path):
    rows = []
    with Path(path).open(encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"{path}:{number}: invalid JSON") from error
            if not isinstance(row, dict):
                raise ValueError(f"{path}:{number}: expected an object")
            rows.append(row)
    return rows


def identifier(value, label):
    if not isinstance(value, str) or not value or len(value) > 256:
        raise ValueError(f"{label} must be a nonempty string of at most 256 characters")
    return value


def load_replays(path):
    rows = read_jsonl(path)
    if not rows:
        raise ValueError("Replay file is empty")
    seen = set()
    for row in rows:
        if type(row.get("schema")) is not int or row["schema"] != 1 or row.get("kind") != "replay":
            raise ValueError("Expected schema=1, kind=replay records")
        trace_id = identifier(row.get("trace_id"), "trace_id")
        if type(row.get("boundary_settling")) is not bool:
            raise ValueError(f"{trace_id}: boundary_settling must be Boolean")
        if not isinstance(row.get("status"), str) or not row["status"]:
            raise ValueError(f"{trace_id}: status is required")
        if "typed" not in row and row["status"] != "ok":
            row["typed"] = ""
        if not isinstance(row.get("typed"), str):
            raise ValueError(f"{trace_id}: typed must be a string")
        if "session" in row:
            identifier(row["session"], "replay session")
        if "synthetic" in row and type(row["synthetic"]) is not bool:
            raise ValueError(f"{trace_id}: synthetic must be Boolean when present")
        key = (trace_id, row["boundary_settling"])
        if key in seen:
            raise ValueError(f"Duplicate replay trace/mode: {key}")
        seen.add(key)
    source_hashes = {row.get("source_sha256") for row in rows}
    if source_hashes != {None}:
        if len(source_hashes) != 1 or not all(isinstance(value, str) and re.fullmatch(r"[a-fA-F0-9]{64}", value)
                                             for value in source_hashes):
            raise ValueError("Replay source_sha256 must be present consistently and refer to the same source file")
    return rows


def load_annotations(path, replay_ids, expected_source_sha256=None):
    if path is None:
        return []
    text = Path(path).read_text(encoding="utf-8")
    try:
        document = json.loads(text)
    except json.JSONDecodeError:
        document = None
    if isinstance(document, dict) and "annotations" in document:
        if type(document.get("schema")) is not int or document["schema"] != 1 or not isinstance(document["annotations"], list):
            raise ValueError("Annotation document requires schema=1 and an annotations list")
        claimed_hash = document.get("source_sha256")
        if not isinstance(claimed_hash, str) or not re.fullmatch(r"[a-fA-F0-9]{64}", claimed_hash):
            raise ValueError("Annotation document requires source_sha256 as a SHA-256 hexadecimal digest")
        if claimed_hash is not None and expected_source_sha256 is not None and claimed_hash.lower() != expected_source_sha256.lower():
            raise ValueError("Annotation source_sha256 does not match the replay source file")
        rows = []
        for item in document["annotations"]:
            if not isinstance(item, dict):
                raise ValueError("Each document annotation must be an object")
            source = {"user_explicit": "manual", "synthetic": "synthetic"}.get(item.get("provenance"), item.get("source"))
            rows.append({**item, "schema": 1, "kind": "annotation", "annotation_id": item.get("id", item.get("annotation_id")),
                         "source": source, "annotation_document_source_sha256": claimed_hash,
                         "annotation_binding_verified": expected_source_sha256 is not None and claimed_hash.lower() == expected_source_sha256.lower()})
    else:
        rows = [{**row, "annotation_binding_verified": False} for row in read_jsonl(path)]
    annotation_ids, covered = set(), set()
    normalized = []
    for row in rows:
        if type(row.get("schema")) is not int or row["schema"] != 1 or row.get("kind") != "annotation":
            raise ValueError("Expected schema=1, kind=annotation records")
        trace_ids = row.get("trace_ids")
        if trace_ids is None and "trace_id" in row:
            trace_ids = [row["trace_id"]]
        if not isinstance(trace_ids, list) or not trace_ids or len(trace_ids) > 512:
            raise ValueError("annotation trace_ids must be a nonempty ordered list, at most 512")
        trace_ids = [identifier(value, "annotation trace_id") for value in trace_ids]
        if len(set(trace_ids)) != len(trace_ids):
            raise ValueError("Repeated trace_id inside annotation")
        if "trace_id" in row and trace_ids != [row["trace_id"]]:
            raise ValueError("trace_id alias is only permitted for a single matching trace_ids entry")
        annotation_id = identifier(row.get("annotation_id", trace_ids[0] if len(trace_ids) == 1 else None),
                                   "annotation_id")
        if annotation_id in annotation_ids:
            raise ValueError(f"Duplicate annotation_id: {annotation_id}")
        if any(value not in replay_ids for value in trace_ids):
            raise ValueError(f"{annotation_id}: annotation references an unknown replay trace")
        if covered.intersection(trace_ids):
            raise ValueError(f"{annotation_id}: overlapping annotations would double-count traces")
        if row.get("status") not in {"confirmed", "pending"}:
            raise ValueError(f"{annotation_id}: status must be confirmed or pending")
        if row.get("source") not in {"manual", "synthetic"}:
            raise ValueError(f"{annotation_id}: source must be manual or synthetic")
        for field in ("target_pinyin", "target_han"):
            if not isinstance(row.get(field, ""), str):
                raise ValueError(f"{annotation_id}: {field} must be a string")
        pinyin, han = row.get("target_pinyin", ""), row.get("target_han", "")
        if row["status"] == "confirmed" and not (pinyin or han):
            raise ValueError(f"{annotation_id}: confirmed annotation needs a target")
        # The native endpoint supports only lowercase ASCII letters. Unsupported
        # targets may be retained as annotation text but never sent to Rime.
        normalized.append({"annotation_id": annotation_id, "trace_ids": trace_ids,
                           "status": row["status"], "source": row["source"],
                           "target_pinyin": pinyin, "target_han": han,
                           "annotation_binding_verified": row["annotation_binding_verified"],
                           **({"annotation_document_source_sha256": row["annotation_document_source_sha256"]}
                              if "annotation_document_source_sha256" in row else {})})
        annotation_ids.add(annotation_id)
        covered.update(trace_ids)
    return normalized


def make_segments(replays, annotations):
    """Join only explicitly selected trace groups; never infer backspace edits."""
    lookup = {(row["trace_id"], row["boundary_settling"]): row for row in replays}
    modes = sorted({row["boundary_settling"] for row in replays}, reverse=True)
    trace_rows = {}
    for row in replays:
        trace_rows.setdefault(row["trace_id"], []).append(row)
    fidelity = {trace_id: baseline_fidelity(rows) for trace_id, rows in trace_rows.items()}
    known_synthetic = {trace_id: any(row.get("synthetic") is True for row in rows)
                       for trace_id, rows in trace_rows.items()}
    positions = {trace_id: index for index, trace_id in enumerate(trace_rows)}
    covered = {trace_id for annotation in annotations for trace_id in annotation["trace_ids"]}
    segments = []
    for annotation in annotations:
        selected_positions = [positions[trace_id] for trace_id in annotation["trace_ids"]]
        if selected_positions != list(range(selected_positions[0], selected_positions[0] + len(selected_positions))):
            raise ValueError(f"{annotation['annotation_id']}: trace_ids must be a contiguous segment in replay source order")
        selected_rows = [row for trace_id in annotation["trace_ids"] for row in trace_rows[trace_id]]
        synthetic_flags = {known_synthetic[trace_id] for trace_id in annotation["trace_ids"]}
        if len(synthetic_flags) > 1:
            raise ValueError(f"{annotation['annotation_id']}: annotated segment mixes synthetic and non-synthetic source traces")
        segment_synthetic = next(iter(synthetic_flags))
        if segment_synthetic and annotation["source"] != "synthetic":
            raise ValueError(f"{annotation['annotation_id']}: known synthetic source traces require synthetic annotation provenance")
        sessions = {row.get("session") for row in selected_rows}
        if len(sessions) > 1:
            raise ValueError(f"{annotation['annotation_id']}: annotated segment crosses sessions or mixes missing session metadata")
        recorded_modes = {row["recorded_mode"] for row in selected_rows if type(row.get("recorded_mode")) is bool}
        if len(recorded_modes) > 1:
            raise ValueError(f"{annotation['annotation_id']}: annotated segment crosses recorded boundary modes")
        for mode in modes:
            source_rows = [lookup.get((trace_id, mode)) for trace_id in annotation["trace_ids"]]
            segment = {"segment_id": annotation["annotation_id"], "annotation_id": annotation["annotation_id"],
                       "trace_ids": annotation["trace_ids"], "boundary_settling": mode,
                       "synthetic": segment_synthetic,
                       "source_replay_statuses": [row["status"] if row is not None else "missing_replay" for row in source_rows],
                       "annotation_status": annotation["status"], "annotation_source": annotation["source"],
                       "target_pinyin": annotation["target_pinyin"], "target_han": annotation["target_han"],
                       **({"session": selected_rows[0]["session"]} if "session" in selected_rows[0] else {}),
                       **({"source_sha256": selected_rows[0]["source_sha256"]} if "source_sha256" in selected_rows[0] else {}),
                       **({"annotation_document_source_sha256": annotation["annotation_document_source_sha256"]}
                          if "annotation_document_source_sha256" in annotation else {})}
            failures = [fidelity[trace_id] for trace_id in annotation["trace_ids"] if fidelity[trace_id] is not None]
            if annotation.get("annotation_binding_verified") is False:
                segment.update(status="unverified_annotation", typed="",
                               reason="Annotation is not bound to the verified replay source hash")
            elif failures:
                status, reason = next((failure for failure in failures if failure[0] == "baseline_fidelity_mismatch"), failures[0])
                segment.update(status=status, typed="".join(row["typed"] for row in source_rows if row is not None),
                               reason=reason, baseline_fidelity_verified=False)
            elif any(row is None for row in source_rows):
                segment.update(status="missing_replay", typed="", reason="Some annotated traces lack this mode")
            elif any(row["status"] != "ok" for row in source_rows):
                segment.update(status="skipped_replay", typed="".join(row["typed"] for row in source_rows),
                               reason="Annotated segment contains a replay whose status is not ok")
            else:
                typed, reason = reconstruct_spelling(source_rows)
                segment.update(status="unsupported_input" if reason else "ready", typed=typed,
                               action_reconstruction=all("actions" in row for row in source_rows),
                               baseline_fidelity_verified=True)
                if reason:
                    segment["reason"] = reason
            segments.append(segment)
    for row in replays:
        if row["trace_id"] in covered:
            continue
        typed, reason = reconstruct_spelling([row])
        failure = fidelity[row["trace_id"]]
        segments.append({"segment_id": row["trace_id"], "trace_ids": [row["trace_id"]],
                         "boundary_settling": row["boundary_settling"], "annotation_status": "unlabeled",
                         "synthetic": known_synthetic[row["trace_id"]],
                         "source_replay_statuses": [row["status"]],
                         "annotation_source": None, "target_pinyin": "", "target_han": "", "typed": typed,
                         **({"session": row["session"]} if "session" in row else {}),
                         **({"source_sha256": row["source_sha256"]} if "source_sha256" in row else {}),
                         "action_reconstruction": "actions" in row,
                         "baseline_fidelity_verified": failure is None,
                         "status": (failure[0] if failure else "skipped_replay" if row["status"] != "ok" else
                                    "unsupported_input" if reason else "ready"),
                         **({"reason": failure[1]} if failure else
                            {"reason": "Touch replay status is not ok"} if row["status"] != "ok" else
                            {"reason": reason} if reason else {})})
    return segments


def baseline_fidelity(rows):
    """A replay must reproduce captured original-mode actions before either arm is queried."""
    if any(row.get("status") == "baseline_fidelity_mismatch" for row in rows):
        return "baseline_fidelity_mismatch", "Original-mode replay did not reproduce captured actions; both modes are excluded"
    recorded_modes = {row.get("recorded_mode") for row in rows if type(row.get("recorded_mode")) is bool}
    if len(recorded_modes) != 1 or any(type(row.get("recorded_mode")) is not bool for row in rows):
        return "baseline_fidelity_unverified", "Replay lacks a consistent captured boundary mode"
    original = next((row for row in rows if row["boundary_settling"] == next(iter(recorded_modes))), None)
    if original is not None and original.get("matches_recorded_actions") is False:
        return "baseline_fidelity_mismatch", "Original-mode replay did not reproduce captured actions; both modes are excluded"
    if original is None or original.get("matches_recorded_actions") is not True or any(row.get("geometry_verified") is not True for row in rows):
        return "baseline_fidelity_unverified", "Verified geometry and matching captured original-mode actions are required for both modes"
    return None


def reconstruct_spelling(rows):
    """Apply captured letter and BackSpace actions within an explicit segment.

    No leading deletion, commit boundary, selection movement or previous
    composition is guessed. Records without actions support older letter-only
    replay output; mixed action/text reconstruction is rejected.
    """
    supplied = ["actions" in row for row in rows]
    if not any(supplied):
        return "".join(row["typed"] for row in rows), None
    if not all(supplied):
        return "", "Cannot mix action replay with legacy typed-only records within a segment"
    letters = []
    for row in rows:
        if not isinstance(row["actions"], list):
            return "".join(letters), "Replay actions must be an ordered list"
        for action in row["actions"]:
            if not isinstance(action, dict):
                return "".join(letters), "Replay action must be an object"
            if action.get("source", "Keyboard") != "Keyboard":
                return "".join(letters), "Only Keyboard-source actions are supported; popup and other action sources are excluded"
            states = action.get("states", 0)
            if type(states) is not int or not -(1 << 31) <= states < (1 << 32) or (states & 0xffffffff) & ~0xa0000000:
                return "".join(letters), "Modified key actions are unsupported in lowercase spelling replay"
            if action.get("type") == "FcitxKeyAction":
                act = action.get("act")
                if not isinstance(act, str) or not re.fullmatch("[a-z]", act):
                    return "".join(letters), "Only single lowercase letter key actions are supported"
                letters.append(act)
            elif action.get("type") == "SymAction" and type(action.get("sym")) is int and action["sym"] == 65288:
                if not letters:
                    return "", "Leading BackSpace would affect composition outside the annotated segment"
                letters.pop()
            else:
                return "".join(letters), "Commit, space/return, selection, layout and other non-letter actions are unsupported in this native replay"
    return "".join(letters), None


def unsupported_reason(typed):
    if not ASCII_SPELLING.fullmatch(typed):
        return "Native replay accepts 1–256 lowercase ASCII letters only; empty, mixed-case, punctuation, backspace and no-op input are unsupported"
    if typed in NONDETERMINISTIC_COMMANDS:
        return "Time-dependent date/lunar/UUID commands are excluded from spelling replay"
    return None


def query_native(typed, binary, data, limit, timeout):
    """No annotation or expected text may cross this function's native boundary."""
    if unsupported_reason(typed):
        raise ValueError("Unsupported native query")
    with tempfile.TemporaryDirectory(prefix="axiang-touch-candidate-") as user:
        completed = subprocess.run([str(binary), str(data), user, str(data / "build"), str(limit), typed],
                                   text=True, capture_output=True, timeout=timeout, check=False)
    if completed.returncode:
        raise RuntimeError(f"Native exporter failed with exit code {completed.returncode}")
    lines = [line for line in completed.stdout.splitlines() if line.strip()]
    if len(lines) != 1:
        raise RuntimeError("Native exporter must return exactly one query record")
    native = json.loads(lines[0])
    if not isinstance(native, dict) or native.get("input") != typed or native.get("raw_input") != typed:
        raise RuntimeError("Native exporter input does not match replay spelling")
    if native.get("input_unchanged") is not True or native.get("all_handled") is not True or native.get("unexpected_commit") is not False:
        raise RuntimeError("Native exporter changed, ignored or committed input")
    if native.get("engine") != "1.12.0" or native.get("schema") != "rime_ice":
        raise RuntimeError("Expected pinned librime 1.12.0 and rime_ice schema")
    candidates = native.get("candidates")
    if not isinstance(candidates, list) or len(candidates) > limit:
        raise RuntimeError("Invalid native candidate array")
    for index, candidate in enumerate(candidates, 1):
        if (not isinstance(candidate, dict) or type(candidate.get("rank")) is not int
                or candidate["rank"] != index or not isinstance(candidate.get("text"), str)
                or not candidate["text"] or not isinstance(candidate.get("comment"), str)
                or type(candidate.get("is_correction")) is not bool):
            raise RuntimeError("Invalid native candidate fields or ranks")
    if type(native.get("candidate_limit")) is not int or native["candidate_limit"] != limit:
        raise RuntimeError("Native exporter candidate limit does not match request")
    return native


def replay_candidates(segments, binary, data, limit=100, timeout=30):
    cache, results = {}, []
    for segment in segments:
        row = {"schema": 1, "kind": "candidate_replay", **segment,
               "fresh_empty_session": True, "committed_context_in_rime": False,
               "targets_sent_to_decoder": False, "context_limitation": CONTEXT_LIMITATION,
               "candidate_limit": limit, "candidates": []}
        if len(row["trace_ids"]) == 1:
            row["trace_id"] = row["trace_ids"][0]
        if row["status"] == "ready":
            reason = unsupported_reason(row["typed"])
            if reason:
                row.update(status="unsupported_input", reason=reason, fresh_empty_session=False)
            else:
                typed = row["typed"]
                if typed not in cache:
                    try:
                        cache[typed] = query_native(typed, binary, data, limit, timeout)
                    except (RuntimeError, ValueError, OSError, subprocess.TimeoutExpired) as error:
                        cache[typed] = {"error": str(error)}
                native = cache[typed]
                if "error" in native:
                    row.update(status="native_error", reason=native["error"])
                else:
                    row.update(status="ok", candidates=native["candidates"], native=native,
                               native_input_preserved=True)
        else:
            row["fresh_empty_session"] = False
        # Targets are consulted only after the label-free native query finished.
        if row["status"] == "ok" and row["annotation_status"] == "confirmed":
            if row["target_pinyin"]:
                row["target_pinyin_matches"] = row["typed"] == row["target_pinyin"]
            if row["target_han"]:
                row["target_han_rank"] = next((candidate["rank"] for candidate in row["candidates"]
                                               if candidate["text"] == row["target_han"]), None)
        results.append(row)
    return results


def summarize(results, limit):
    groups = []
    for mode in sorted({row["boundary_settling"] for row in results}, reverse=True):
        for source in ("manual", "synthetic"):
            rows = [row for row in results if row["boundary_settling"] == mode
                    and row["annotation_status"] == "confirmed" and row["annotation_source"] == source]
            eligible = [row for row in rows if row["status"] == "ok" and row["target_han"]]
            groups.append({"boundary_settling": mode, "annotation_source": source,
                           "confirmed_segments": len(rows), "eligible_target_han_segments": len(eligible),
                           "skipped_segments": sum(row["status"] != "ok" for row in rows),
                           "pinyin_only_segments": sum(row["status"] == "ok" and not row["target_han"] for row in rows),
                           "top1_hits": sum(row.get("target_han_rank") == 1 for row in eligible),
                           "top3_hits": sum(row.get("target_han_rank") is not None and row["target_han_rank"] <= 3 for row in eligible),
                           "topN_hits": sum(row.get("target_han_rank") is not None for row in eligible),
                           "candidate_limit": limit})
    return {"schema": 1, "kind": "candidate_replay_report", "groups": groups,
            "pending_segments": sum(row["annotation_status"] == "pending" for row in results),
            "unlabeled_segments": sum(row["annotation_status"] == "unlabeled" for row in results),
            "unsupported_segments": sum(row["status"] == "unsupported_input" for row in results),
            "native_error_segments": sum(row["status"] == "native_error" for row in results),
            "unverified_annotation_segments": sum(row["status"] == "unverified_annotation" for row in results),
            "baseline_fidelity_excluded_segments": sum(row["status"] in {"baseline_fidelity_mismatch", "baseline_fidelity_unverified"}
                                                       for row in results),
            "context_limitation": CONTEXT_LIMITATION,
            "interpretation": "Counts are for explicitly annotated replay segments. Synthetic annotations are separate. No real-user accuracy or phone latency claim follows from synthetic traces."}


def digest(path):
    value = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--replays", required=True, type=Path, help="schema=1 touch replay JSONL")
    parser.add_argument("--annotations", type=Path, help="Offline viewer annotation JSON document (legacy JSONL also supported)")
    parser.add_argument("--rime-binary", required=True, type=Path, help="Existing ime-benchmark rime-candidates exporter")
    parser.add_argument("--rime-data", required=True, type=Path, help="Compiled AXiang Rime data with build/rime_ice.schema.yaml")
    parser.add_argument("--limit", type=int, default=100, help="Candidate limit, 1–1000 (default 100)")
    parser.add_argument("--timeout", type=float, default=30, help="Host query timeout seconds (default 30)")
    parser.add_argument("--output", required=True, type=Path, help="New candidate replay JSONL file")
    parser.add_argument("--report", type=Path, help="New report JSON file; default OUTPUT.report.json")
    args = parser.parse_args(argv)
    if not 1 <= args.limit <= 1000 or not 0 < args.timeout <= 300:
        parser.error("--limit must be 1–1000 and --timeout must be >0 and <=300")
    data, binary = args.rime_data.resolve(), args.rime_binary.resolve()
    if not binary.is_file() or not (data / "build" / "rime_ice.schema.yaml").is_file():
        parser.error("Exporter binary and compiled build/rime_ice.schema.yaml must exist")
    report_path = args.report or Path(str(args.output) + ".report.json")
    if args.output.exists() or report_path.exists() or args.output.resolve() == report_path.resolve():
        parser.error("Output/report must be distinct new files")
    replays = load_replays(args.replays)
    replay_source_sha256 = replays[0].get("source_sha256")
    annotations = load_annotations(args.annotations, {row["trace_id"] for row in replays}, replay_source_sha256)
    results = replay_candidates(make_segments(replays, annotations), binary, data, args.limit, args.timeout)
    report = summarize(results, args.limit)
    report["provenance"] = {"replays_sha256": digest(args.replays),
                            "annotations_sha256": digest(args.annotations) if args.annotations else None,
                            "binary_sha256": digest(binary),
                            "schema_sha256": digest(data / "build" / "rime_ice.schema.yaml"),
                            "rime_data_dir": str(data), "native_exporter_modified": False,
                            "fresh_empty_user_directory_per_unique_spelling": True,
                            "repeated_identical_spelling_reuses_frozen_result": True,
                            "replay_source_sha256": replay_source_sha256,
                            "annotation_source_hash_matches_replay": (all(annotation.get("annotation_document_source_sha256") == replay_source_sha256
                                                                         for annotation in annotations)
                                                                         if replay_source_sha256 and annotations else None),
                            "targets_sent_to_decoder": False}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in results), encoding="utf-8")
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(results)} segment/mode results to {args.output}; report {report_path}", file=sys.stderr)
    return 1 if any(row["status"] == "native_error" for row in results) else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError) as error:
        print(f"candidate_replay: {error}", file=sys.stderr)
        raise SystemExit(2)
