#!/usr/bin/env python3
"""Run real host inference with the exact bundled models, not Android UI mocks.

Requires Python packages sherpa-onnx==1.13.8 and numpy. CPU timings are host-only
and are not Android latency, NPU acceleration, or battery-life measurements.
"""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import time
import wave

import numpy as np
import sherpa_onnx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path.cwd())
    parser.add_argument('--cache', type=Path, default=Path.home() / '.cache/axiang-asr')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    if sherpa_onnx.__version__ != '1.13.8':
        raise RuntimeError(f'Use sherpa-onnx==1.13.8, got {sherpa_onnx.__version__}')
    spec = importlib.util.spec_from_file_location('prepare_asr', Path(__file__).with_name('prepare-axiang-asr.py'))
    prepare = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(prepare)
    assets = args.root / 'app/src/main/assets/asr'
    manifest = json.loads((assets / 'SOURCE.json').read_text())
    for name, expected in manifest['apk_files_sha256'].items():
        if name.startswith('assets/asr/'):
            path = args.root / 'app/src/main' / name
            if prepare.sha256(path) != expected:
                raise ValueError(f'Bundled asset checksum mismatch: {path}')
    args.cache.mkdir(parents=True, exist_ok=True)
    archive_path = prepare.fetch(prepare.DOWNLOADS['asr'], args.cache, args.offline)
    started = time.monotonic()
    model = assets / 'zh-14m'
    recognizer = sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(model / 'tokens.txt'), encoder=str(model / 'encoder.int8.onnx'),
        decoder=str(model / 'decoder.onnx'), joiner=str(model / 'joiner.int8.onnx'),
        num_threads=1, sample_rate=16000, feature_dim=80, model_type='zipformer',
        provider='cpu', decoding_method='greedy_search',
    )
    result = {
        'sherpa_onnx_version': sherpa_onnx.__version__, 'provider': 'cpu',
        'num_threads': 1, 'model_sample_rate': 16000, 'model_type': 'zipformer',
        'host_only': True, 'asr_init_seconds': time.monotonic() - started,
        'archive': prepare.DOWNLOADS['asr'], 'asr_tests': [], 'punctuation_tests': [],
    }
    with tarfile.open(archive_path, 'r:bz2') as archive:
        for name in ['0.wav', '1.wav', '8k.wav']:
            member = f'{prepare.ASR_NAME}/test_wavs/{name}'
            wav = archive.extractfile(member).read()
            with wave.open(io.BytesIO(wav)) as audio:
                if audio.getsampwidth() != 2 or audio.getnchannels() != 1:
                    raise ValueError('Expected mono PCM16 official fixture')
                rate = audio.getframerate()
                samples = np.frombuffer(audio.readframes(audio.getnframes()), np.int16).astype(np.float32) / 32768
            stream = recognizer.create_stream()
            started = time.monotonic()
            for position in range(0, len(samples), rate // 10):
                stream.accept_waveform(rate, samples[position:position + rate // 10])
                while recognizer.is_ready(stream):
                    recognizer.decode_stream(stream)
            stream.accept_waveform(rate, np.zeros(rate // 2, np.float32))
            stream.input_finished()
            while recognizer.is_ready(stream):
                recognizer.decode_stream(stream)
            text = recognizer.get_result(stream)
            test = {
                'wav_in_archive': member, 'wav_sha256': hashlib.sha256(wav).hexdigest(),
                'sample_rate': rate, 'duration_seconds': len(samples) / rate,
                'actual': text, 'inference_seconds': time.monotonic() - started,
            }
            if name == '0.wav':
                test['expected'] = '对我做了介绍那么我想说的是大家如果对我的研究感兴趣'
                test['reference'] = manifest['asr']['documentation']
                if text != test['expected']:
                    raise AssertionError(test)
            elif not text.strip():
                raise AssertionError(f'Empty recognition for {name}')
            result['asr_tests'].append(test)
    started = time.monotonic()
    punctuation = sherpa_onnx.OfflinePunctuation(sherpa_onnx.OfflinePunctuationConfig(
        model=sherpa_onnx.OfflinePunctuationModelConfig(
            ct_transformer=str(assets / 'punctuation/model.int8.onnx'), num_threads=1, provider='cpu',
        ),
    ))
    result['punctuation_init_seconds'] = time.monotonic() - started
    for text in ['我们都是木头人不会说话不会动', '明天你有时间吗我们一起去公园吧', result['asr_tests'][0]['actual']]:
        started = time.monotonic()
        output = punctuation.add_punctuation(text)
        test = {'input': text, 'actual': output, 'inference_seconds': time.monotonic() - started}
        if text == '我们都是木头人不会说话不会动':
            test['expected'] = '我们都是木头人，不会说话，不会动。'
            test['reference'] = manifest['punctuation']['documentation']
            if output != test['expected']:
                raise AssertionError(test)
        if output == text:
            raise AssertionError(f'Expected actual punctuation restoration: {test}')
        result['punctuation_tests'].append(test)
    result['passed'] = True
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
