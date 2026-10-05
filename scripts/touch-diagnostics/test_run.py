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
