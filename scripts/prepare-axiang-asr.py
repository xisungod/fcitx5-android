#!/usr/bin/env python3
"""Prepare pinned, bundled offline ASR and punctuation. Run only at build time.

The Android application never downloads these assets. Binary/model outputs are
generated files, not source-controlled dependencies. No Qualcomm/NNAPI provider
is claimed: this package deliberately contains the tested CPU implementation.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import urllib.request
import zipfile

VERSION = '1.13.8'
ASR_NAME = 'sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23'
PUNCT_NAME = 'sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8'
DOWNLOADS = {
    'runtime': {
        'filename': f'sherpa-onnx-{VERSION}.aar',
        'url': f'https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/sherpa-onnx-{VERSION}.aar',
        'sha256': '633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96',
    },
    'asr': {
        'filename': 'cpu-model.tar.bz2',
        'url': f'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/{ASR_NAME}.tar.bz2',
        'sha256': '2cbd71b640d9c37d3784f29367333a4577b0398b62e9deeed418170b081cba8b',
    },
    'punctuation': {
        'filename': 'punctuation-model.tar.bz2',
        'url': f'https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/{PUNCT_NAME}.tar.bz2',
        'sha256': 'c0d5aa5f8eeb686032345e180bedf39319dc2e0556781c6264bcadba8328a6e1',
    },
}


def sha256(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def fetch(spec, cache, offline):
    path = cache / spec['filename']
    if path.exists():
        if sha256(path) != spec['sha256']:
            raise ValueError(f'Cached checksum mismatch: {path}')
        return path
    if offline:
        raise FileNotFoundError(f'Offline build cache is missing {path}')
    partial = path.with_suffix(path.suffix + '.partial')
    try:
        with urllib.request.urlopen(spec['url'], timeout=120) as response, partial.open('wb') as output:
            shutil.copyfileobj(response, output)
        if sha256(partial) != spec['sha256']:
            raise ValueError(f'Download checksum mismatch: {spec["url"]}')
        partial.replace(path)
    finally:
        partial.unlink(missing_ok=True)
    return path


def write_file(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def copy_tar_member(archive, name, destination):
    member = archive.getmember(name)
    if not member.isfile():
        raise ValueError(f'Expected regular archive member: {name}')
    # Exact names only. Never extract archive-provided filesystem paths/links.
    with archive.extractfile(member) as source:
        write_file(destination, source.read())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path.cwd())
    parser.add_argument('--cache', type=Path, default=Path.home() / '.cache/axiang-asr')
    parser.add_argument('--offline', action='store_true', help='Use already verified local archives only')
    args = parser.parse_args()
    root = args.root.resolve()
    if not (root / 'app/build.gradle.kts').is_file():
        parser.error('--root must be the fcitx5 Android source checkout')
    args.cache.mkdir(parents=True, exist_ok=True)
    archives = {key: fetch(spec, args.cache, args.offline) for key, spec in DOWNLOADS.items()}
    assets = root / 'app/src/main/assets/asr'
    jni = root / 'app/src/main/jniLibs/arm64-v8a'
    jar = root / f'app/libs/sherpa-onnx-{VERSION}.jar'
    with zipfile.ZipFile(archives['runtime']) as archive:
        manifest = archive.read('AndroidManifest.xml').decode()
        if 'android:minSdkVersion="21"' not in manifest:
            raise ValueError('Unexpected upstream Android minimum SDK')
        write_file(jar, archive.read('classes.jar'))
        for name in ['libonnxruntime.so', 'libsherpa-onnx-jni.so']:
            data = archive.read(f'jni/arm64-v8a/{name}')
            if data[:6] != b'\x7fELF\x02\x01' or int.from_bytes(data[18:20], 'little') != 183:
                raise ValueError(f'Expected ELF64 little-endian AArch64: {name}')
            write_file(jni / name, data)
    with tarfile.open(archives['asr'], 'r:bz2') as archive:
        for source, target in {
            'encoder-epoch-99-avg-1.int8.onnx': 'encoder.int8.onnx',
            'decoder-epoch-99-avg-1.onnx': 'decoder.onnx',
            'joiner-epoch-99-avg-1.int8.onnx': 'joiner.int8.onnx',
            'tokens.txt': 'tokens.txt',
        }.items():
            copy_tar_member(archive, f'{ASR_NAME}/{source}', assets / 'zh-14m' / target)
    with tarfile.open(archives['punctuation'], 'r:bz2') as archive:
        copy_tar_member(archive, f'{PUNCT_NAME}/model.int8.onnx', assets / 'punctuation/model.int8.onnx')

    notices = Path(__file__).parent / 'asr'
    notice_manifest = json.loads((notices / 'license-sources.json').read_text())
    for name, spec in notice_manifest.items():
        source = notices / 'licenses' / name
        if sha256(source) != spec['sha256']:
            raise ValueError(f'Bundled license checksum mismatch: {name}')
        write_file(assets / 'licenses' / name, source.read_bytes())
    write_file(assets / 'licenses/NOTICE.txt', (notices / 'NOTICE.txt').read_bytes())
    apk_files = {f'lib/arm64-v8a/{name}': sha256(jni / name)
                 for name in ['libonnxruntime.so', 'libsherpa-onnx-jni.so']}
    apk_files.update({f'assets/asr/{p.relative_to(assets)}': sha256(p)
                      for p in sorted(assets.rglob('*')) if p.is_file() and p != assets / 'SOURCE.json'})
    source = {
        'sherpa_onnx_version': VERSION,
        'provider': 'cpu',
        'abi': 'arm64-v8a',
        'runtime_min_sdk': 21,
        'onnxruntime_version': '1.28.2',
        'network_at_runtime': False,
        'downloads': DOWNLOADS,
        'asr': {
            'name': ASR_NAME, 'license': 'Apache-2.0', 'sample_rate': 16000,
            'feature_dim': 80, 'model_type': 'zipformer',
            'upstream': 'https://huggingface.co/marcoyang/sherpa-ncnn-streaming-zipformer-zh-14M-2023-02-23/tree/caf018c3b4ab7fabb313cbf89066283d38324682',
            'documentation': 'https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html',
        },
        'punctuation': {
            'name': PUNCT_NAME, 'license': 'Apache-2.0',
            'upstream': 'https://modelscope.cn/models/iic/punc_ct-transformer_zh-cn-common-vocab272727-pytorch',
            'documentation': 'https://k2-fsa.github.io/sherpa/onnx/punctuation/pretrained_models.html',
            'model': 'asr/punctuation/model.int8.onnx',
        },
        'runtime_licensing': 'Sherpa Apache-2.0; ONNX Runtime MIT and third-party notices; bundled JNI also includes eSpeak NG GPL-3.0-or-later. See licenses/NOTICE.txt.',
        'license_sources': notice_manifest,
        'compile_jar': {'file': str(jar.relative_to(root)), 'sha256': sha256(jar)},
        'apk_files_sha256': apk_files,
    }
    write_file(assets / 'SOURCE.json', (json.dumps(source, ensure_ascii=False, indent=2) + '\n').encode())
    print(json.dumps({'provider': 'cpu', 'version': VERSION, 'abi': 'arm64-v8a',
                      'apk_files': len(apk_files), 'model_bytes': sum(p.stat().st_size for p in assets.rglob('*.onnx')),
                      'manifest': str(assets / 'SOURCE.json')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
