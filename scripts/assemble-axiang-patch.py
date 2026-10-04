#!/usr/bin/env python3
"""Reassemble the exact cumulative patch from small, checksum-verified Git objects."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--parts', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads((args.parts / 'manifest.json').read_text())
    pieces = []
    for part in manifest['parts']:
        name = part['file']
        if Path(name).name != name:
            raise ValueError('Patch part must be a filename')
        data = (args.parts / name).read_bytes()
        if len(data) != part['bytes'] or digest(data) != part['sha256']:
            raise ValueError('Patch part failed verification: ' + name)
        pieces.append(data)
    compressed = b''.join(pieces)
    if digest(compressed) != manifest['gzip_sha256']:
        raise ValueError('Reassembled compressed patch checksum mismatch')
    patch = gzip.decompress(compressed)
    if len(patch) != manifest['patch_bytes'] or digest(patch) != manifest['patch_sha256']:
        raise ValueError('Cumulative patch checksum mismatch')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(patch)
    print(f"Verified patch {manifest['patch_sha256']} ({len(patch)} bytes), source {manifest['source_commit']}")


if __name__ == '__main__':
    main()
