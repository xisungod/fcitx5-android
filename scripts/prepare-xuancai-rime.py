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
parser.add_argument('--model', type=Path)
parser.add_argument('--grammar-license', type=Path)
args = parser.parse_args()
data = args.archive.read_bytes() if args.archive else urllib.request.urlopen(URL, timeout=120).read()
assert hashlib.sha256(data).hexdigest() == SHA256, 'Rime Ice archive checksum mismatch'
import io
stage = args.root / 'plugin/rime/src/main/cpp/xuancai-rime-ice'
assert not stage.exists(), f'Remove previous staging directory first: {stage}'
files = {
    'rime_ice.schema.yaml': 'rime_ice.schema.yaml',
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
        if name.startswith(('cn_dicts/', 'en_dicts/', 'opencc/', 'lua/')):
            files[name] = name
    for source, dest in files.items():
        assert '..' not in Path(dest).parts and not Path(dest).is_absolute()
        target = stage / dest
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(archive.extractfile(prefix + source).read())

# V15 dictionary expansion. Keep upstream entries/weights unchanged, with the
# common character table first so rare characters cannot displace normal words.
dict_path = stage / 'rime_ice.dict.yaml'
dictionary = dict_path.read_text()
dictionary = dictionary.replace('  # - cn_dicts/41448', '  - cn_dicts/41448', 1)
dictionary = dictionary.replace('  - cn_dicts/others', '  - cn_dicts/xuancai_mobile  # Explicitly annotated mobile/computing additions\n  - cn_dicts/others', 1)
dict_path.write_text(dictionary)
extension_dir = Path(__file__).parent / 'rime'
for name, target in [
    ('xuancai_mobile.dict.yaml', 'cn_dicts/xuancai_mobile.dict.yaml'),
    ('Unicode-LICENSE.txt', 'cn_dicts/Unicode-LICENSE.txt'),
    ('PinyinData-LICENSE.txt', 'cn_dicts/PinyinData-LICENSE.txt'),
    ('xuancai_user.dict.yaml', 'xuancai_user.dict.yaml'),
    ('xuancai_user.schema.yaml', 'xuancai_user.schema.yaml'),
]:
    (stage / target).write_bytes((extension_dir / name).read_bytes())

# Full upstream Lua/schema plus bounded mobile correction and a separately
# compiled personal dictionary: importing personal words never changes rime_ice.
schema_path = stage / 'rime_ice.schema.yaml'
schema = schema_path.read_text()
schema = schema.replace('  dependencies:\n', '  dependencies:\n    - xuancai_user  # Small separately compiled personal dictionary\n', 1)
schema = schema.replace('\ntranslator:\n', '\ntranslator:\n  enable_correction: false\n  contextual_suggestions: true\n  max_homophones: 7\n  max_homographs: 7\n', 1)
schema = schema.replace('  algebra:\n', '  algebra:\n    # Mobile omission tolerance: shuang -> shuag, zheng -> zheg.\n    - derive/^([a-z]*[aeio])ng$/$1g/\n', 1)
schema = schema.replace('    - ascii_composer\n', '    - lua_processor@*xuancai_ascii\n    - ascii_composer\n', 1)
schema = schema.replace('    - script_translator\n', '    - script_translator\n    - script_translator@xuancai_user\n    - lua_translator@*xuancai_correction\n', 1)
schema += '\nxuancai_correction:\n  dictionary: rime_ice\n  prism: rime_ice\n  enable_correction: true\n  enable_user_dict: false\n  enable_completion: false\n  enable_word_completion: false\n  spelling_hints: 32\n  always_show_comments: true\n  initial_quality: 0.2\n'
schema += '\nxuancai_exact:\n  dictionary: rime_ice\n  prism: rime_ice\n  enable_correction: false\n  enable_user_dict: false\n  enable_completion: false\n  enable_word_completion: false\n'
schema += '\nxuancai_user:\n  dictionary: xuancai_user\n  enable_completion: false\n  enable_word_completion: false\n  enable_sentence: false\n  enable_user_dict: false\n  initial_quality: 1.1\n'
schema += '\n# Xuancai mobile settings (user copy takes precedence).\n__patch: xuancai_mobile:/patch\ngrammar:\n  language: zh-hans-t-essay-bgw-compact\n'
schema_path.write_text(schema)
for script in ['xuancai_correction.lua', 'xuancai_ascii.lua']:
    (stage / 'lua' / script).write_bytes((Path(__file__).parent / 'rime' / script).read_bytes())
(stage / 'xuancai_mobile.yaml').write_text('# Managed by Xuancai\npatch: {}\n')
MODEL_URL = 'https://github.com/lotem/rime-octagram-data/releases/download/20260712/zh-hans-t-essay-bgw-compact.gram'
MODEL_SHA = 'd3cb2438c1fdcd6a855dd6ca8f5c1060a29273c6b64c2c2c69af67cd71b6aa7e'
model = args.model.read_bytes() if args.model else urllib.request.urlopen(MODEL_URL, timeout=120).read()
assert hashlib.sha256(model).hexdigest() == MODEL_SHA, 'Grammar model checksum mismatch'
(stage / 'zh-hans-t-essay-bgw-compact.gram').write_bytes(model)
license_dir = stage / 'grammar-license'
license_dir.mkdir()
license_data = args.grammar_license.read_bytes() if args.grammar_license else urllib.request.urlopen('https://raw.githubusercontent.com/lotem/rime-octagram-data/20260712/LICENSE').read()
assert hashlib.sha256(license_data).hexdigest() == 'da7eabb7bafdf7d3ae5e9f223aa5bdc1eece45ac569dc21b3b037520b4464768'
(license_dir / 'LICENSE').write_bytes(license_data)
(license_dir / 'SOURCE.json').write_text(json.dumps({'project': 'https://github.com/lotem/rime-octagram-data', 'tag': '20260712', 'url': MODEL_URL, 'sha256': MODEL_SHA, 'bytes': len(model)}, indent=2) + '\n')

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
active_tables = ['8105', '41448', 'base', 'ext', 'tencent', 'xuancai_mobile', 'others']
counts = {f'cn_dicts/{name}': count_rows(stage / f'cn_dicts/{name}.dict.yaml') for name in active_tables}
counts.update({f'en_dicts/{name}': count_rows(stage / f'en_dicts/{name}.dict.yaml') for name in ['en_ext', 'en']})
manifest = {
    'project': 'https://github.com/iDvel/rime-ice', 'revision': REV,
    'archive_url': URL, 'archive_sha256': SHA256,
    'schema': 'Full upstream rime_ice.schema.yaml and Lua; mobile additions enable correction, pinned grammar model, optional fuzzy patches and a separately compiled personal dictionary',
    'license': 'GPL-3.0; see LICENSE and upstream README.md for individual data sources',
    'extensions': {
        'rare_characters': {'table': 'cn_dicts/41448', 'source': 'Pinned Rime Ice table, derived from Unihan and pinyin-data', 'licenses': {'Unicode': 'cn_dicts/Unicode-LICENSE.txt', 'pinyin-data': 'cn_dicts/PinyinData-LICENSE.txt'}, 'source_urls': ['https://www.unicode.org/Public/16.0.0/ucd/Unihan.zip', 'https://github.com/mozillazg/pinyin-data'], 'ranking': '8105 precedes 41448; upstream weights preserved'},
        'mobile_vocabulary': {'table': 'cn_dicts/xuancai_mobile', 'source': 'Original, explicitly annotated Xuancai vocabulary', 'license': 'GPL-3.0-only'},
        'personal_dictionary': {'file': 'xuancai_user.dict.yaml', 'schema': 'xuancai_user.schema.yaml', 'format': 'UTF-8 word<TAB>space-separated-pinyin<TAB>integer-weight', 'deployment': 'Small independent dictionary; rime_ice.table.bin is reused'},
    },
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
