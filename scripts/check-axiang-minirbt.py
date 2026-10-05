#!/usr/bin/env python3
"""Compare actual FP32 and INT8 MiniRBT inference on candidate-ranking fixtures.

These diagnostic fixtures measure export correctness and quantization drift.
They are not a held-out input-method accuracy benchmark or phone latency test.
"""
import argparse
import hashlib
import json
from pathlib import Path
import statistics
import time

import numpy as np
import onnxruntime as ort
from transformers import BertTokenizer


INPUT_NAMES = ['input_ids', 'attention_mask', 'token_type_ids', 'masked_position', 'target_id']
CASES = [
    ('今天见到朋友，我对他说：', ['你好啊', '几号啊', '怒号啊']),
    ('这件事你', ['能确定吗', '能确定那', '梦确定那']),
    ('今天是', ['几号啊', '你好啊', '怒号啊']),
    ('唐诗写道：', ['两个黄鹂鸣翠柳', '两个黄看鸣翠柳', '两个黄丽鸣翠柳']),
    ('两个黄鹂鸣翠柳，', ['一行白鹭上青天', '一行白哭上青天', '一行白路上青天']),
    ('我想问问，', ['这个问题怎么解决', '这个吻突怎么解决', '这个问题怎么结觉']),
    ('她是一个', ['小姑娘', '小故娘', '小姑凉']),
    ('', ['你好啊', '几号啊', '怒号啊']),
]


def session(path):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    return ort.InferenceSession(str(path), options, providers=['CPUExecutionProvider'])


def candidate_inputs(tokenizer, context, candidates):
    context_ids = tokenizer.encode(context, add_special_tokens=False)[-48:]
    rows = []
    ranges = []
    for candidate in candidates:
        ids = tokenizer.encode(candidate, add_special_tokens=False)
        if not ids or tokenizer.unk_token_id in ids or len(context_ids) + len(ids) + 2 > 96:
            raise ValueError(f'Unsupported fixture candidate: {candidate}')
        start = len(rows)
        base = [tokenizer.cls_token_id] + context_ids + ids + [tokenizer.sep_token_id]
        for offset, token in enumerate(ids):
            position = 1 + len(context_ids) + offset
            masked = base.copy()
            masked[position] = tokenizer.mask_token_id
            rows.append((masked, position, token))
        ranges.append((start, len(rows)))
    width = max(len(row[0]) for row in rows)
    input_ids = np.zeros((len(rows), width), dtype=np.int64)
    attention_mask = np.zeros_like(input_ids)
    for index, (tokens, _, _) in enumerate(rows):
        input_ids[index, :len(tokens)] = tokens
        attention_mask[index, :len(tokens)] = 1
    inputs = {
        'input_ids': input_ids, 'attention_mask': attention_mask,
        'token_type_ids': np.zeros_like(input_ids),
        'masked_position': np.array([row[1] for row in rows], dtype=np.int64),
        'target_id': np.array([row[2] for row in rows], dtype=np.int64),
    }
    return inputs, ranges


def scores(log_prob, ranges):
    return [float(np.mean(log_prob[start:end])) for start, end in ranges]


def check_models(fp32_path, int8_path, vocab_path, torch_scoring=None):
    tokenizer = BertTokenizer(vocab_file=str(vocab_path), do_lower_case=True)
    started = time.perf_counter()
    fp32 = session(fp32_path)
    fp32_init = time.perf_counter() - started
    started = time.perf_counter()
    int8 = session(int8_path)
    int8_init = time.perf_counter() - started
    expected = [(name, 'tensor(int64)') for name in INPUT_NAMES]
    for runtime in [fp32, int8]:
        if [(value.name, value.type) for value in runtime.get_inputs()] != expected:
            raise AssertionError('Unexpected ONNX interface')
        if [(value.name, value.type) for value in runtime.get_outputs()] != [('log_prob', 'tensor(float)')]:
            raise AssertionError('Unexpected ONNX output')
    cases = []
    int8_errors = []
    torch_errors = []
    batch_errors = {'fp32': [], 'int8': []}
    timings = {'fp32': [], 'int8': []}
    agreeing = 0
    for context, candidates in CASES:
        inputs, ranges = candidate_inputs(tokenizer, context, candidates)
        outputs = {}
        for label, runtime in [('fp32', fp32), ('int8', int8)]:
            runtime.run(['log_prob'], inputs)  # warmup
            started = time.perf_counter()
            for _ in range(3):
                actual = runtime.run(['log_prob'], inputs)[0]
            timings[label].append((time.perf_counter() - started) * 1000 / 3)
            if actual.shape != (inputs['input_ids'].shape[0],) or not np.isfinite(actual).all():
                raise AssertionError('Invalid scores')
            # Dynamic axes, independent rows, and padding must behave correctly.
            one = {name: value[:1] for name, value in inputs.items()}
            singleton = runtime.run(['log_prob'], one)[0]
            batch_errors[label].append(float(np.max(np.abs(singleton - actual[:1]))))
            if label == 'fp32' and not np.allclose(singleton, actual[:1], atol=0.0002):
                raise AssertionError('FP32 batch/singleton discrepancy')
            outputs[label] = actual
        int8_errors.extend(np.abs(outputs['fp32'] - outputs['int8']).tolist())
        if torch_scoring is not None:
            import torch
            with torch.inference_mode():
                actual = torch_scoring(*(torch.from_numpy(inputs[name]) for name in INPUT_NAMES)).numpy()
            torch_errors.extend(np.abs(actual - outputs['fp32']).tolist())
        fp32_scores = scores(outputs['fp32'], ranges)
        int8_scores = scores(outputs['int8'], ranges)
        fp32_best = int(np.argmax(fp32_scores))
        int8_best = int(np.argmax(int8_scores))
        agreeing += fp32_best == int8_best
        cases.append({'context': context, 'candidates': candidates, 'fp32_scores': fp32_scores,
                      'int8_scores': int8_scores, 'fp32_first': candidates[fp32_best],
                      'int8_first': candidates[int8_best], 'batch': inputs['input_ids'].shape[0],
                      'sequence_length': inputs['input_ids'].shape[1]})
    maximum_error = max(int8_errors)
    torch_max = max(torch_errors) if torch_errors else None
    agreement = agreeing / len(CASES)
    result = {
        'host_only': True, 'onnxruntime_version': ort.__version__, 'provider': 'CPUExecutionProvider',
        'num_threads': 1, 'fp32_init_seconds': fp32_init, 'int8_init_seconds': int8_init,
        'fp32_torch_max_abs_error': torch_max, 'int8_fp32_max_abs_error': maximum_error,
        'int8_fp32_mean_abs_error': statistics.mean(int8_errors),
        'top_candidate_agreement': agreement,
        'batch_singleton_max_abs_error': {name: max(values) for name, values in batch_errors.items()},
        'timings_ms_per_fixture': {name: {'median': statistics.median(values), 'max': max(values)}
                                  for name, values in timings.items()},
        'thresholds': {'fp32_torch_max_abs_error': 0.0002, 'int8_fp32_max_abs_error': 0.35,
                       'minimum_top_candidate_agreement': 0.875},
        'cases': cases,
        'tokenizer_fixtures': [
            {'text': text, 'tokens': tokenizer.tokenize(text),
             'ids': tokenizer.encode(text, add_special_tokens=False),
             'ids_with_special_tokens': tokenizer.encode(text, add_special_tokens=True)}
            for text in ['你好啊', 'AXiang 输入法 1.2', 'Café résumé naïve AXiang',
                         'A\u0301Xiàng', '你好，世界！「键盘」', '你好\t世界\n',
                         '中文\u0000\ufffdabc', '[MASK]你好[SEP]', '👋你好𠀀', 'Привет мир']
        ],
    }
    result['passed'] = torch_max is None or torch_max <= 0.0002
    result['quantization_passed'] = (maximum_error <= 0.35 and agreement >= 0.875
                                     and max(batch_errors['int8']) <= 0.08)
    return result


def check_frozen_bundle(directory, reference_report):
    """Verify pinned files and perform FP32 inference against reviewed results."""
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    manifest = json.loads((directory / 'manifest.json').read_text())
    assert manifest['variant'] == 'fp32' and manifest['fine_tuned_for_ime'] is False
    assert digest(reference_report) == manifest['validation']['report_sha256']
    reference = json.loads(reference_report.read_text())
    assert reference['passed'] is True and reference['host_only'] is True
    for name, expected in manifest['files'].items():
        path = directory / name
        assert path.stat().st_size == expected['bytes'] and digest(path) == expected['sha256'], name
    vocabulary = (directory / 'vocab.txt').read_text(encoding='utf-8').split('\n')
    if vocabulary[-1] == '':
        vocabulary.pop()
    assert len(vocabulary) == manifest['vocabulary_size'] == manifest['checkpoint_vocabulary_size'] == 21128
    tokenizer = BertTokenizer(vocab_file=str(directory / 'vocab.txt'), do_lower_case=True)
    for fixture in reference['tokenizer_fixtures']:
        assert tokenizer.tokenize(fixture['text']) == fixture['tokens']
        assert tokenizer.encode(fixture['text'], add_special_tokens=False) == fixture['ids']
        assert tokenizer.encode(fixture['text'], add_special_tokens=True) == fixture['ids_with_special_tokens']
    runtime = session(directory / 'model.onnx')
    assert [(value.name, value.type) for value in runtime.get_inputs()] == [(name, 'tensor(int64)') for name in INPUT_NAMES]
    assert [(value.name, value.type) for value in runtime.get_outputs()] == [('log_prob', 'tensor(float)')]
    cases = []
    max_error = 0.0
    for fixture in reference['cases']:
        inputs, ranges = candidate_inputs(tokenizer, fixture['context'], fixture['candidates'])
        started = time.perf_counter()
        actual = runtime.run(['log_prob'], inputs)[0]
        elapsed = (time.perf_counter() - started) * 1000
        assert actual.shape == (inputs['input_ids'].shape[0],) and np.isfinite(actual).all()
        candidate_scores = scores(actual, ranges)
        error = float(np.max(np.abs(np.array(candidate_scores) - fixture['fp32_scores'])))
        max_error = max(max_error, error)
        assert error <= 0.0005, 'FP32 reproduction changed reviewed scores'
        first = fixture['candidates'][int(np.argmax(candidate_scores))]
        assert first == fixture['fp32_first']
        cases.append({'context': fixture['context'], 'candidates': fixture['candidates'],
                      'scores': candidate_scores, 'first': first, 'reference_max_abs_error': error,
                      'inference_ms': elapsed})
    return {'passed': True, 'host_only': True, 'fine_tuned_for_ime': False,
            'onnxruntime_version': ort.__version__, 'provider': 'CPUExecutionProvider', 'num_threads': 1,
            'model_sha256': digest(directory / 'model.onnx'), 'manifest_sha256': digest(directory / 'manifest.json'),
            'reference_report_sha256': digest(reference_report), 'fp32_reference_max_abs_error': max_error,
            'fp32_reference_tolerance': 0.0005, 'tokenizer_fixtures': len(reference['tokenizer_fixtures']), 'cases': cases}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-dir', type=Path)
    parser.add_argument('--vocab', type=Path)
    parser.add_argument('--frozen-bundle-dir', type=Path)
    parser.add_argument('--reference-report', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.frozen_bundle_dir is not None:
        if args.reference_report is None:
            parser.error('--frozen-bundle-dir requires --reference-report')
        result = check_frozen_bundle(args.frozen_bundle_dir, args.reference_report)
    else:
        if args.model_dir is None or args.vocab is None:
            parser.error('Model comparison requires --model-dir and --vocab')
        result = check_models(args.model_dir / 'model.fp32.onnx', args.model_dir / 'model.int8.onnx', args.vocab)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
