import argparse
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from contextlib import redirect_stdout

SPEC = importlib.util.spec_from_file_location('touch_run', Path(__file__).with_name('run.py'))
run = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(run)


def key(letter):
    return {'type': 'FcitxKeyAction', 'act': letter, 'source': 'Keyboard', 'states': 1 << 29}


def motion(time, action, pointer_ids, action_index=0):
    return {'t': time, 'action': action, 'action_index': action_index,
            'pointers': [{'id': pid} for pid in pointer_ids]}


def overlapping_contacts(reverse_up=True, second_down=0, last_up=30):
    # IDs differ from pointer-array indexes, and pointer array order can change.
    first_up, final_up = (3, 7) if reverse_up else (7, 3)
    events = [motion(0, 0, [7]), motion(second_down, 5, [3, 7], 0),
              motion(20, 6, [7, 3], [7, 3].index(first_up)), motion(last_up, 1, [final_up])]
    contacts = [{'pointer_id': pid, 'down_t': 0 if pid == 7 else second_down,
                 'up_t': 20 if pid == first_up else last_up} for pid in (7, 3)]
    return contacts, events


class DiagnosticTests(unittest.TestCase):
    def test_backspace_uses_only_selected_segment(self):
        self.assertEqual(('ab', None), run.action_text([key('a'), key('x'), {'type': 'SymAction', 'sym': 65288}, key('b')]))
        text, reason = run.action_text([{'type': 'SymAction', 'sym': 65288}, key('a')])
        self.assertIsNone(text)
        self.assertIn('outside', reason)

    def test_english_commit_and_modifier_are_not_silently_scored(self):
        for actions in ([key('A')], [{'type': 'CommitAction', 'text': 'a'}],
                        [dict(key('a'), states=1)], [dict(key('a'), source='Popup')]):
            self.assertIsNone(run.action_text(actions)[0])

    def test_reverse_up_is_counted_separately(self):
        contacts = [{'down_t': 0, 'up_t': 24}, {'down_t': 8, 'up_t': 16}]
        self.assertEqual(1, run.down_up_inversions(contacts))
        self.assertEqual(0, run.down_up_inversions([dict(contacts[0], cancelled=True), contacts[1]]))

    def test_equal_timestamp_down_uses_event_ordinal_and_pointer_id(self):
        contacts, events = overlapping_contacts()
        self.assertEqual(0, run.down_up_inversions(contacts))
        self.assertEqual(1, run.down_up_inversions(contacts, events))
        self.assertEqual({'inversions': 1, 'method': 'event_ordinal'},
                         run.down_up_order_evidence(contacts, events))

    def test_equal_timestamp_up_also_uses_event_ordinal(self):
        contacts, events = overlapping_contacts(second_down=8, last_up=20)
        self.assertEqual(0, run.down_up_inversions(contacts))
        self.assertEqual(1, run.down_up_inversions(contacts, events))

    def test_overlapping_same_order_is_not_an_inversion(self):
        contacts, events = overlapping_contacts(reverse_up=False)
        self.assertEqual({'inversions': 0, 'method': 'event_ordinal'},
                         run.down_up_order_evidence(contacts, events))

    def test_missing_events_preserve_timestamp_only_fallback(self):
        contacts, _ = overlapping_contacts(second_down=8)
        for events in (None, []):
            self.assertEqual({'inversions': 1, 'method': 'timestamp_only'},
                             run.down_up_order_evidence(contacts, events))

    def test_cancelled_contact_does_not_count_as_completed_order(self):
        contacts, events = overlapping_contacts()
        contacts[1]['cancelled'] = True
        self.assertEqual(0, run.down_up_inversions(contacts, events))
        cancelled = [{'pointer_id': 7, 'down_t': 0, 'cancelled': True},
                     {'pointer_id': 3, 'down_t': 0, 'cancelled': True}]
        self.assertEqual({'inversions': 0, 'method': 'event_ordinal'},
                         run.down_up_order_evidence(cancelled, events[:2] + [motion(20, 3, [7, 3])]))

    def test_reused_pointer_id_is_a_new_contact_after_up(self):
        events = [motion(0, 0, [7]), motion(8, 5, [7, 3], 1), motion(12, 6, [7, 3], 1),
                  motion(16, 5, [7, 3], 1), motion(20, 6, [7, 3], 0), motion(24, 1, [3])]
        contacts = [{'pointer_id': 7, 'down_t': 0, 'up_t': 20},
                    {'pointer_id': 3, 'down_t': 8, 'up_t': 12},
                    {'pointer_id': 3, 'down_t': 16, 'up_t': 24}]
        self.assertEqual({'inversions': 1, 'method': 'event_ordinal'},
                         run.down_up_order_evidence(contacts, events))

    def test_invalid_event_order_is_identified_before_timestamp_fallback(self):
        contacts, original = overlapping_contacts(second_down=8)
        for bad_events in (
            {},
            [original[0], dict(original[1], action_index=4), *original[2:]],
            [original[0], dict(original[1], pointers=[{'id': 7}, {'id': 7}]), *original[2:]],
            [original[0], original[1], dict(original[2], t=4), original[3]],
            original[1:], original[:-1],
        ):
            with self.subTest(events=bad_events):
                evidence = run.down_up_order_evidence(contacts, bad_events)
                self.assertEqual(1, evidence['inversions'])
                self.assertEqual('timestamp_only', evidence['method'])
                self.assertTrue(evidence['event_order_error'])
        mismatched = [dict(contacts[0], up_t=31), contacts[1]]
        self.assertIn('UP time', run.down_up_order_evidence(mismatched, original)['event_order_error'])

    def test_segment_must_be_contiguous_same_session_same_mode(self):
        traces = [{'id': str(i), '_session': 'one', 'boundary_settling': True} for i in range(3)]
        self.assertEqual(2, len(run.segment(traces, ['0', '1'])))
        for ids in (['0', '2'], ['1', '0'], ['0', '0'], ['missing']):
            with self.assertRaises(ValueError):
                run.segment(traces, ids)
        traces[1]['_session'] = 'two'
        with self.assertRaises(ValueError):
            run.segment(traces, ['0', '1'])
        traces[1]['_session'] = 'one'
        traces[1]['boundary_settling'] = False
        with self.assertRaises(ValueError):
            run.segment(traces, ['0', '1'])

    def test_annotation_requires_explicit_target_and_binds_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / 'traces.jsonl'
            source.write_text(json.dumps({'schema': 1, 'kind': 'trace', 'session': 'one', 'trace': {'id': 'a', 'boundary_settling': True}}) + '\n')
            output = Path(tmp) / 'labels.json'
            args = argparse.Namespace(traces=source, ids='1:1', id='label', target_pinyin=None,
                                      target_han='你好', status='confirmed', provenance='user_explicit', note='', output=output)
            with self.assertRaises(ValueError):
                run.annotate_command(args)
            args.target_pinyin = 'nihao'
            with redirect_stdout(io.StringIO()):
                run.annotate_command(args)
            self.assertEqual('nihao', run.read_annotations(output, source)[0]['target_pinyin'])
            source.write_text(source.read_text() + '\n')
            with self.assertRaises(ValueError):
                run.read_annotations(output, source)

    def test_annotation_document_requires_valid_source_hash(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'labels.json'
            for claimed in (None, '', 'bad'):
                doc = {'schema': 1, 'annotations': []}
                if claimed is not None:
                    doc['source_sha256'] = claimed
                path.write_text(json.dumps(doc))
                with self.assertRaisesRegex(ValueError, 'source_sha256'):
                    run.read_annotations(path)

    def report_fixture(self, tmp, baseline_matches=True, status='confirmed', provenance='user_explicit'):
        source = Path(tmp) / 'traces.jsonl'
        source.write_text(json.dumps({'schema': 1, 'kind': 'trace', 'session': 'one', 'trace': {
            'id': 'a', 'boundary_settling': True, 'actions': [key('g')], 'decisions': [{'mode': 'settle'}]}}) + '\n')
        labels = Path(tmp) / 'labels.json'
        labels.write_text(json.dumps({'schema': 1, 'source_sha256': run.digest(source), 'annotations': [{
            'id': 's', 'trace_ids': ['a'], 'status': status, 'provenance': provenance, 'target_pinyin': 'f', 'target_han': ''}]}))
        replays = Path(tmp) / 'replay.jsonl'
        replays.write_text(''.join(json.dumps({'schema': 1, 'kind': 'replay', 'trace_id': 'a', 'boundary_settling': mode,
            'status': 'ok', 'geometry_verified': True, 'matches_recorded_actions': baseline_matches if mode else False,
            'actions': [key('g' if mode else 'f')]}) + '\n' for mode in (True, False)))
        args = argparse.Namespace(traces=source, annotations=labels, replays=replays, output=Path(tmp) / 'report.json')
        run.report_command(args)
        return json.loads(args.output.read_text())

    def test_only_confirmed_reproduced_segments_count(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = self.report_fixture(tmp)
            m = report['metrics']['user_explicit']
            self.assertEqual(1, m['confirmed_segments'])
            self.assertEqual(1, m['off_improved'])
            self.assertEqual(0, m['on_exact'])
            self.assertEqual(1, m['off_exact'])
        with tempfile.TemporaryDirectory() as tmp:
            report = self.report_fixture(tmp, baseline_matches=False)
            self.assertEqual(['a'], report['recorded_actions_not_reproduced'])
            self.assertEqual(0, report['metrics']['user_explicit']['confirmed_segments'])

    def test_report_identifies_event_ordinal_count_without_scoring_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.report_fixture(tmp, status='pending')
            source = Path(tmp) / 'traces.jsonl'
            row = json.loads(source.read_text())
            row['trace']['contacts'], row['trace']['events'] = overlapping_contacts()
            source.write_text(json.dumps(row) + '\n')
            args = argparse.Namespace(traces=source, annotations=None, replays=Path(tmp) / 'replay.jsonl',
                                      output=Path(tmp) / 'report.json')
            run.report_command(args)
            report = json.loads(args.output.read_text())
            self.assertEqual(1, report['recorded_down_up_inversions'])
            self.assertEqual({'event_ordinal': 1, 'timestamp_only': 0}, report['recorded_down_up_order_methods'])
            self.assertEqual([], report['recorded_down_up_order_event_errors'])
            self.assertEqual(0, report['metrics']['user_explicit']['confirmed_segments'])

    def test_pending_delete_retype_is_not_a_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = self.report_fixture(tmp, status='pending')
            self.assertEqual(1, report['pending_segments'])
            self.assertEqual(1, report['unlabeled_traces'])
            self.assertEqual(0, report['metrics']['user_explicit']['confirmed_segments'])

    def test_synthetic_counts_are_never_user_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = self.report_fixture(tmp, provenance='synthetic')
            self.assertEqual(1, report['metrics']['synthetic']['confirmed_segments'])
            self.assertEqual(0, report['metrics']['user_explicit']['confirmed_segments'])

    def test_known_synthetic_cannot_be_mislabeled_as_user_data(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.report_fixture(tmp)
            source = Path(tmp) / 'traces.jsonl'
            row = json.loads(source.read_text()); row['trace']['synthetic'] = True
            source.write_text(json.dumps(row) + '\n')
            labels = Path(tmp) / 'labels.json'
            doc = json.loads(labels.read_text()); doc['source_sha256'] = run.digest(source)
            labels.write_text(json.dumps(doc))
            with self.assertRaisesRegex(ValueError, 'synthetic'):
                run.report_command(argparse.Namespace(traces=source, annotations=labels,
                    replays=Path(tmp) / 'replay.jsonl', output=None))

    def test_wrong_replay_source_and_duplicate_modes_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.report_fixture(tmp)
            source, labels, replays = [Path(tmp) / name for name in ('traces.jsonl', 'labels.json', 'replay.jsonl')]
            lines = replays.read_text().splitlines()
            row = json.loads(lines[0]); row['source_sha256'] = 'wrong'
            replays.write_text(json.dumps(row) + '\n' + lines[1] + '\n')
            with self.assertRaises(ValueError):
                run.report_command(argparse.Namespace(traces=source, annotations=labels, replays=replays, output=None))

    def test_replay_exports_acyclic_original_trace_and_validates_harness_handshake(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / 'trace.jsonl'
            source.write_text(json.dumps({'schema': 1, 'kind': 'trace', 'session': 'one',
                                         'trace': {'id': 'tap', 'boundary_settling': True}}) + '\n')
            output = Path(tmp) / 'replay.jsonl'
            args = argparse.Namespace(traces=source, ids=None, output=output,
                                      harness_command='fake-harness --an-argument', project_dir=Path(tmp))
            def fake_run(command, cwd, env, check):
                self.assertEqual(['fake-harness', '--an-argument'], command)
                exported = json.loads(Path(env['AXIANG_TOUCH_REPLAY_INPUT']).read_text())
                self.assertNotIn('_outer', exported['trace'])
                Path(env['AXIANG_TOUCH_REPLAY_OUTPUT']).write_text(''.join(json.dumps({
                    'schema': 1, 'kind': 'replay', 'trace_id': 'tap', 'boundary_settling': mode,
                    'status': 'ok'}) + '\n' for mode in (True, False)))
            with patch.object(run.subprocess, 'run', side_effect=fake_run), redirect_stdout(io.StringIO()):
                run.replay_command(args)
            rows = [json.loads(line) for line in output.read_text().splitlines()]
            self.assertEqual(run.digest(source), rows[0]['source_sha256'])
            self.assertEqual('one', rows[0]['session'])

    def test_edit_distance_is_letter_error_not_semantic_accuracy(self):
        self.assertEqual(2, run.levenshtein('jibgchsnghui', 'jingchanghui'))
        self.assertEqual(0, run.levenshtein('jch', 'jch'))


if __name__ == '__main__':
    unittest.main()
