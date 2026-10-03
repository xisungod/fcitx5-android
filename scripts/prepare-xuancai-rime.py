#!/usr/bin/env python3
"""Fetch pinned upstream dictionaries; never use a moving branch in an APK build."""
import argparse
import hashlib
import json
from pathlib import Path
import tarfile
import urllib.request
import zipfile

REV = '3aea6d3694fb3d94ec663641f021f788822897ad'
SHA256 = 'a170d79442f7463118fbfde089f62d046710475436e3c5347de2cf1aeaa95b48'
URL = f'https://codeload.github.com/iDvel/rime-ice/tar.gz/{REV}'
parser = argparse.ArgumentParser()
parser.add_argument('--archive', type=Path)
parser.add_argument('--root', type=Path, default=Path.cwd())
parser.add_argument('--zip', type=Path)
args = parser.parse_args()
data = args.archive.read_bytes() if args.archive else urllib.request.urlopen(URL, timeout=120).read()
assert hashlib.sha256(data).hexdigest() == SHA256, 'Rime Ice archive checksum mismatch'
import io
stage = args.root / 'plugin/rime/src/main/cpp/xuancai-rime-ice'
assert not stage.exists(), f'Remove previous staging directory first: {stage}'
files = {
    'others/no_lua_schema/rime_ice.schema.yaml': 'rime_ice.schema.yaml',
    **{name: name for name in [
        'rime_ice.dict.yaml', 'melt_eng.schema.yaml', 'melt_eng.dict.yaml',
        'radical_pinyin.schema.yaml', 'radical_pinyin.dict.yaml',
        'custom_phrase.txt', 'symbols_v.yaml', 'LICENSE', 'README.md'
    ]}
}
with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
    prefix = f'rime-ice-{REV}/'
    for member in archive.getmembers():
        assert member.name.startswith(prefix) or member.name == prefix[:-1]
        name = member.name.removeprefix(prefix)
        if not member.isfile():
            continue
        if name.startswith(('cn_dicts/', 'en_dicts/', 'opencc/')):
            files[name] = name
    for source, dest in files.items():
        assert '..' not in Path(dest).parts and not Path(dest).is_absolute()
        target = stage / dest
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(archive.extractfile(prefix + source).read())

# Counts refer to raw table rows (not unique words or the user's learned vocabulary).
def count_rows(path):
    body = False
    count = 0
    for line in path.read_text().splitlines():
        if line == '...':
            body = True
            continue
        if body and line.strip() and not line.startswith('#'):
            count += 1
    return count
active_tables = ['8105', 'base', 'ext', 'tencent', 'others']
counts = {f'cn_dicts/{name}': count_rows(stage / f'cn_dicts/{name}.dict.yaml') for name in active_tables}
counts.update({f'en_dicts/{name}': count_rows(stage / f'en_dicts/{name}.dict.yaml') for name in ['en_ext', 'en']})
manifest = {
    'project': 'https://github.com/iDvel/rime-ice', 'revision': REV,
    'archive_url': URL, 'archive_sha256': SHA256,
    'schema': 'Official others/no_lua_schema/rime_ice.schema.yaml, copied without modification',
    'license': 'GPL-3.0; see LICENSE and upstream README.md for individual data sources',
    'raw_dictionary_rows': counts,
    'files_sha256': {str(p.relative_to(stage)): hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in sorted(stage.rglob('*')) if p.is_file()}
}
(stage / 'SOURCE.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n')
if args.zip:
    args.zip.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.zip, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for path in sorted(stage.rglob('*')):
            if path.is_file():
                output.write(path, 'rime-ice/' + str(path.relative_to(stage)))
print(json.dumps({'revision': REV, 'raw_dictionary_rows': counts}, ensure_ascii=False))
