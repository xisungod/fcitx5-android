#!/usr/bin/env python3
"""Local touch diagnostics: annotation, actual-keyboard replay, and bounded reports.

Only the Robolectric harness implements replay. This module never simulates key
routing and never changes Rime/app settings or uploads input data.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
PINYIN = re.compile(r"[a-z]+(?:'[a-z]+)*\Z")


def read_traces(path: Path) -> list[dict]:
    traces = []
    seen = set()
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        if row.get("kind") != "trace":
            continue
        if row.get("schema") != 1 or not isinstance(row.get("trace"), dict):
            raise ValueError(f"Line {line_number}: unsupported trace schema")
        trace = dict(row["trace"])
        trace_id = trace.get("id")
        if not isinstance(trace_id, str) or not trace_id or trace_id in seen:
            raise ValueError(f"Line {line_number}: missing/duplicate trace id")
        seen.add(trace_id)
        trace["_session"] = row.get("session", "")
        trace["_outer"] = row
        traces.append(trace)
    return traces


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_annotations(path: Path | None, source: Path | None = None) -> list[dict]:
    if path is None:
        return []
    doc = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(doc, dict) or type(doc.get("schema")) is not int or doc.get("schema") != 1 or not isinstance(doc.get("annotations"), list):
        raise ValueError("Annotation document must be schema 1 with an annotations array")
    if not isinstance(doc.get("source_sha256"), str) or not re.fullmatch(r"[a-f0-9]{64}", doc["source_sha256"]):
        raise ValueError("Annotation document requires its source_sha256")
    if source is not None and doc["source_sha256"] != digest(source):
        raise ValueError("Annotations were made for a different trace file")
    result = doc["annotations"]
    seen = set()
    for item in result:
        if item.get("id") in seen or not item.get("id"):
            raise ValueError("Missing/duplicate annotation id")
        seen.add(item["id"])
        if item.get("status") not in ("confirmed", "pending"):
            raise ValueError("Annotation status must be confirmed or pending")
        if item.get("provenance") not in ("user_explicit", "synthetic"):
            raise ValueError("Annotation provenance must be user_explicit or synthetic")
        if not isinstance(item.get("trace_ids"), list) or not item["trace_ids"]:
            raise ValueError("Annotation must select an ordered trace segment")
        if item["status"] == "confirmed" and not PINYIN.fullmatch(item.get("target_pinyin", "")):
            raise ValueError("Confirmed target_pinyin must be lowercase pinyin, optional apostrophes")
    return result


def segment(traces: list[dict], ids: list[str]) -> list[dict]:
    positions = {row["id"]: i for i, row in enumerate(traces)}
    if len(set(ids)) != len(ids) or any(i not in positions for i in ids):
        raise ValueError("Segment contains duplicate/unknown trace ids")
    indexes = [positions[i] for i in ids]
    if indexes != list(range(indexes[0], indexes[0] + len(indexes))):
        raise ValueError("Segment must be contiguous and in recorded order")
    rows = [traces[index] for index in indexes]
    if len({row["_session"] for row in rows}) != 1:
        raise ValueError("Segment crosses recording sessions")
    # Input-boundary records are retained outside traces, so use their absolute
    # trace positions in the outer log where available; see extract below.
    if len({row.get("boundary_settling") for row in rows}) != 1:
        raise ValueError("Segment crosses an A/B mode switch")
    if len({bool(row.get("synthetic", False)) for row in rows}) != 1:
        raise ValueError("Segment mixes synthetic and real traces")
    return rows


def selected_ids(value: str, traces: list[dict]) -> list[str]:
    if re.fullmatch(r"\d+:\d+", value):
        first, last = map(int, value.split(":"))
        if first < 1 or last < first or last > len(traces):
            raise ValueError("--ids START:END is a 1-based inclusive range")
        return [row["id"] for row in traces[first - 1:last]]
    return value.split(",")


def action_text(actions: list[dict]) -> tuple[str | None, str | None]:
    """Reconstruct only a fully selected, lowercase pinyin segment.

    Backspaces cannot erase text outside the segment. Commit, cursor, language,
    mode and popup actions aren't guessed into pinyin.
    """
    output = []
    for action in actions:
        kind = action.get("type")
        if action.get("source", "Keyboard") != "Keyboard":
            return None, "Popup actions require an unsupported external selection flow"
        if int(action.get("states", 0)) & 0xffffffff & ~((1 << 29) | (1 << 31)):
            return None, "Modifier state is not ordinary lowercase pinyin"
        if kind == "FcitxKeyAction":
            text = action.get("act", "")
            if len(text) == 1 and ("a" <= text <= "z" or text == "'"):
                output.append(text)
            else:
                return None, f"Unsupported non-pinyin key {text!r}"
        elif kind == "SymAction" and action.get("sym") == 65288:
            if not output:
                return None, "Backspace reaches outside the selected segment"
            output.pop()
        else:
            return None, f"Unsupported action {kind}"
    return "".join(output), None


def down_up_inversions(contacts: list[dict]) -> int:
    complete = [c for c in contacts if "up_t" in c and not c.get("cancelled")]
    return sum((a["down_t"] - b["down_t"]) * (a["up_t"] - b["up_t"]) < 0
               for i, a in enumerate(complete) for b in complete[i + 1:])


def levenshtein(a: str, b: str) -> int:
    previous = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        current = [i]
        for j, y in enumerate(b, 1):
            current.append(min(current[-1] + 1, previous[j] + 1, previous[j - 1] + (x != y)))
        previous = current
    return previous[-1]


def list_command(args) -> None:
    traces = read_traces(args.traces)
    for index, trace in enumerate(traces, 1):
        typed, reason = action_text(trace.get("actions", []))
        settled = sum(d.get("mode") == "settle" for d in trace.get("decisions", []))
        print(json.dumps({"index": index, "id": trace["id"], "session": trace["_session"],
                          "boundary_settling": trace.get("boundary_settling"), "typed": typed,
                          "unsupported": reason, "settle_count": settled,
                          "down_up_inversions": down_up_inversions(trace.get("contacts", [])),
                          "contacts": trace.get("contacts", [])}, ensure_ascii=False))
    print(f"{len(traces)} traces. Trigger counts alone cannot identify intended keys.", file=sys.stderr)


def annotate_command(args) -> None:
    traces = read_traces(args.traces)
    ids = selected_ids(args.ids, traces)
    selected = segment(traces, ids)
    if selected[0].get("synthetic") and args.provenance != "synthetic":
        raise ValueError("Known synthetic traces must be labeled synthetic")
    if args.status == "confirmed" and not PINYIN.fullmatch(args.target_pinyin or ""):
        raise ValueError("Confirmed labels require explicit lowercase --target-pinyin")
    doc = {"schema": 1, "source_sha256": digest(args.traces), "annotations": []}
    if args.output.exists():
        doc["annotations"] = read_annotations(args.output, args.traces)
    item = {"id": args.id or f"segment-{len(doc['annotations']) + 1}", "trace_ids": ids,
            "target_pinyin": args.target_pinyin or "", "target_han": args.target_han or "",
            "status": args.status, "provenance": args.provenance, "note": args.note}
    if any(old["id"] == item["id"] for old in doc["annotations"]):
        raise ValueError("Annotation id exists; choose another id")
    doc["annotations"].append(item)
    args.output.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Saved {args.status} segment {item['id']} ({len(ids)} traces) to {args.output}")


def replay_command(args) -> None:
    if args.output.exists():
        raise ValueError("Replay output already exists; choose a new path")
    traces = read_traces(args.traces)
    selected = segment(traces, selected_ids(args.ids, traces)) if args.ids else traces
    if not selected:
        raise ValueError("No traces to replay")
    command = shlex.split(args.harness_command)
    if not command:
        raise ValueError("--harness-command is empty")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="axiang-touch-replay-") as tmp:
        exported = Path(tmp) / "input.jsonl"
        result = Path(tmp) / "output.jsonl"
        exported.write_text("".join(json.dumps(row["_outer"], ensure_ascii=False) + "\n" for row in selected), encoding="utf-8")
        env = dict(os.environ, AXIANG_TOUCH_REPLAY_INPUT=str(exported), AXIANG_TOUCH_REPLAY_OUTPUT=str(result))
        env.pop("AXIANG_TOUCH_REPLAY_SYNTHETIC", None)
        subprocess.run(command, cwd=args.project_dir, env=env, check=True)
        if not result.exists():
            raise ValueError("Harness did not create output; use the supplied JVM init script and disable task caching")
        rows = [json.loads(line) for line in result.read_text(encoding="utf-8").splitlines() if line.strip()]
        expected = {(row["id"], mode) for row in selected for mode in (True, False)}
        actual = [(row.get("trace_id"), row.get("boundary_settling")) for row in rows]
        if len(actual) != len(expected) or set(actual) != expected:
            raise ValueError("Harness results do not cover both modes exactly once")
        source_sha = digest(args.traces)
        harness_sha = digest(ROOT / "app/src/test/java/org/fcitx/fcitx5/android/TouchDiagnosticReplayTest.kt")
        for row in rows:
            row["source_sha256"] = source_sha
            row["harness_sha256"] = harness_sha
            row["session"] = next(trace["_session"] for trace in selected if trace["id"] == row["trace_id"])
        args.output.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    print(f"Actual TextKeyboard replay: {len(selected)} traces, both modes, current UP submission. {args.output}")


def report_command(args) -> None:
    traces = read_traces(args.traces)
    by_id = {row["id"]: row for row in traces}
    annotations = read_annotations(args.annotations, args.traces)
    rows = [json.loads(line) for line in args.replays.read_text(encoding="utf-8").splitlines() if line.strip()]
    results = {}
    for row in rows:
        key = (row.get("trace_id"), row.get("boundary_settling"))
        if row.get("schema") != 1 or row.get("kind") != "replay" or key in results:
            raise ValueError("Unsupported/duplicate replay result")
        if key[0] not in by_id or type(key[1]) is not bool:
            raise ValueError("Replay result has unknown id or non-boolean mode")
        if row.get("source_sha256") and row["source_sha256"] != digest(args.traces):
            raise ValueError("Replay belongs to a different source trace file")
        results[key] = row
    valid_ids = set()
    mismatched = []
    unsupported = []
    for trace in traces:
        pair = [results.get((trace["id"], mode)) for mode in (True, False)]
        if any(row is None or row.get("status") != "ok" or not row.get("geometry_verified") for row in pair):
            unsupported.append(trace["id"])
        elif not results[(trace["id"], trace["boundary_settling"])].get("matches_recorded_actions"):
            mismatched.append(trace["id"])
        else:
            valid_ids.add(trace["id"])
    metrics = {source: {"confirmed_segments": 0, "on_exact": 0, "off_exact": 0,
                        "on_edits": 0, "off_edits": 0, "target_letters": 0,
                        "off_improved": 0, "off_harmed": 0}
               for source in ("user_explicit", "synthetic")}
    details = []
    labeled_ids = set()
    pending = 0
    for label in annotations:
        selected = segment(traces, label["trace_ids"])
        if selected[0].get("synthetic") and label["provenance"] != "synthetic":
            raise ValueError("Known synthetic traces cannot count as user_explicit")
        if label["status"] != "confirmed":
            pending += 1
            continue
        if labeled_ids.intersection(label["trace_ids"]):
            raise ValueError("Confirmed segments overlap; do not count the same touches twice")
        labeled_ids.update(label["trace_ids"])
        detail = {"id": label["id"], "target_pinyin": label["target_pinyin"],
                  "target_han": label.get("target_han", ""), "provenance": label["provenance"]}
        if not set(label["trace_ids"]).issubset(valid_ids):
            detail["status"] = "excluded_unreproduced_trace"
            details.append(detail)
            continue
        spellings = {}
        for mode in (True, False):
            actions = [action for trace_id in label["trace_ids"] for action in results[(trace_id, mode)].get("actions", [])]
            spellings[mode] = action_text(actions)
        if any(reason for text, reason in spellings.values()):
            detail["status"] = "unsupported_actions"
            detail["reasons"] = [reason for text, reason in spellings.values() if reason]
            details.append(detail)
            continue
        on, off = spellings[True][0], spellings[False][0]
        target = label["target_pinyin"]
        m = metrics[label["provenance"]]
        m["confirmed_segments"] += 1
        m["target_letters"] += len(target)
        m["on_exact"] += on == target
        m["off_exact"] += off == target
        m["on_edits"] += levenshtein(on, target)
        m["off_edits"] += levenshtein(off, target)
        m["off_improved"] += off == target and on != target
        m["off_harmed"] += on == target and off != target
        detail.update(status="ok", on=on, off=off, on_edits=levenshtein(on, target), off_edits=levenshtein(off, target))
        details.append(detail)
    report = {"schema": 1, "source_sha256": digest(args.traces), "traces": len(traces),
              "geometry_or_replay_unsupported": unsupported, "recorded_actions_not_reproduced": mismatched,
              "unlabeled_traces": len(set(by_id) - labeled_ids), "pending_segments": pending,
              "recorded_settle_decisions": sum(d.get("mode") == "settle" for t in traces for d in t.get("decisions", [])),
              "recorded_down_up_inversions": sum(down_up_inversions(t.get("contacts", [])) for t in traces),
              "metrics": metrics, "segments": details,
              "limits": ["Synthetic and explicitly labeled user segments are reported separately.",
                         "No target is inferred from delete/retype or model candidates.",
                         "UP submission and original sampling are unchanged in both replay modes.",
                         "Offline same-trace counterfactuals cannot prove live A/B typing improvement.",
                         "Current pinyin decoding does not consume touch coordinates; this is a source-code finding, not a measured typo rate."]}
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    else:
        print(text, end="")


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="command", required=True)
    listing = sub.add_parser("list", help="List trace indexes, contacts, triggers and release inversions")
    listing.add_argument("traces", type=Path)
    listing.set_defaults(func=list_command)
    annotate = sub.add_parser("annotate", help="Explicitly label one contiguous segment, never auto-confirm")
    annotate.add_argument("traces", type=Path)
    annotate.add_argument("--ids", required=True, help="1-based inclusive START:END or ordered comma-separated UUIDs")
    annotate.add_argument("--id")
    annotate.add_argument("--target-pinyin")
    annotate.add_argument("--target-han")
    annotate.add_argument("--status", choices=("confirmed", "pending"), required=True)
    annotate.add_argument("--provenance", choices=("user_explicit", "synthetic"), default="user_explicit")
    annotate.add_argument("--note", default="")
    annotate.add_argument("--output", type=Path, required=True)
    annotate.set_defaults(func=annotate_command)
    replay = sub.add_parser("replay", help="Invoke production-keyboard Robolectric replay in both boundary modes")
    replay.add_argument("traces", type=Path)
    replay.add_argument("--ids")
    replay.add_argument("--output", type=Path, required=True)
    replay.add_argument("--project-dir", type=Path, default=ROOT)
    replay.add_argument("--harness-command", default="./gradlew -I scripts/touch-diagnostics/jvm-tests.init.gradle -x :app:installFcitxComponent -x :app:deleteFcitxComponentExcludeFiles :app:testDebugUnitTest --tests org.fcitx.fcitx5.android.TouchDiagnosticReplayTest.exportedTraces", help="Tokenized command, executed without a shell")
    replay.set_defaults(func=replay_command)
    report = sub.add_parser("report", help="Count only explicitly confirmed, reproduced segments")
    report.add_argument("traces", type=Path)
    report.add_argument("--replays", type=Path, required=True)
    report.add_argument("--annotations", type=Path)
    report.add_argument("--output", type=Path)
    report.set_defaults(func=report_command)
    return p


def main(argv=None) -> int:
    try:
        args = parser().parse_args(argv)
        args.func(args)
        return 0
    except (ValueError, OSError, json.JSONDecodeError, subprocess.CalledProcessError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
