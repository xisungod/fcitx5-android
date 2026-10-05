#!/usr/bin/env python3
"""Build a bounded, public-dictionary-only Pinyin lookup for touch corrections.

Sorted fixed-width shards allow prefix pruning without a Lua table per word.
No user dictionary, input history, guessed spellings or hand-picked phrases enter
the index. The exact Rime translator remains the authority for candidate text.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct

SOURCES = ('base', 'ext', 'tencent', 'xuancai_mobile', 'others')
MIN_LENGTH, MAX_LENGTH = 4, 24
MAGIC = b'AXTI1\0\0\0'


def build(stage: Path):
    codes = {}
    sources = {}
    for name in SOURCES:
        source = stage / 'cn_dicts' / (name + '.dict.yaml')
        sources['cn_dicts/' + source.name] = hashlib.sha256(source.read_bytes()).hexdigest()
        for line in source.read_text().splitlines():
            columns = line.split('\t')
            if len(columns) < 3:
                continue
            word, spelling = columns[:2]
            if not (2 <= len(word) <= 5 and all('\u3400' <= c <= '\u9fff' for c in word)):
                continue
            if not re.fullmatch(r'[a-z]+(?: [a-z]+)+', spelling):
                continue
            code = spelling.replace(' ', '')
            if not MIN_LENGTH <= len(code) <= MAX_LENGTH:
                continue
            try:
                weight = int(columns[2])
            except ValueError:
                continue
            weight = max(1, min(0xffffffff, weight))
            codes[code] = max(codes.get(code, 0), weight)
    output = stage / 'lua' / 'axiang_typo'
    output.mkdir(parents=True, exist_ok=True)
    shards = {}
    for length in range(MIN_LENGTH, MAX_LENGTH + 1):
        records = sorted((code, weight) for code, weight in codes.items() if len(code) == length)
        data = bytearray(MAGIC + struct.pack('<B3xI', length, len(records)))
        for code, weight in records:
            data.extend(code.encode('ascii'))
            data.extend(struct.pack('<I', weight))
        name = f'{length:02d}.bin'
        (output / name).write_bytes(data)
        shards[name] = {'records': len(records), 'bytes': len(data),
                        'sha256': hashlib.sha256(data).hexdigest()}
    manifest = {
        'format': 'AXTI1: 16-byte header; sorted ASCII code + little-endian uint32 source weight',
        'scope': {'min_letters': MIN_LENGTH, 'max_letters': MAX_LENGTH,
                  'min_characters': 2, 'max_characters': 5, 'max_adjacent_substitutions': 2},
        'sources_sha256': sources,
        'source_license': 'Existing pinned Rime Ice dictionary sources; see the enclosing SOURCE.json and LICENSE',
        'records': len(codes), 'total_bytes': sum(x['bytes'] for x in shards.values()),
        'files': shards,
    }
    (output / 'SOURCE.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', type=Path)
    args = parser.parse_args()
    result = build(args.stage)
    print(json.dumps({key: result[key] for key in ('scope', 'records', 'total_bytes')}))
