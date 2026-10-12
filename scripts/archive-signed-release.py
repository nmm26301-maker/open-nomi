#!/usr/bin/env python3
"""Restore a locally signed APK using public ZIP metadata, never a private key."""
import argparse
import base64
import gzip
import hashlib
import json
from pathlib import Path
import re


def load_spec(path):
    raw = gzip.decompress(path.read_bytes())
    if len(raw) > 8 * 1024 * 1024:
        raise ValueError('Oversized signing recipe')
    spec = json.loads(raw)
    if spec['format'] != 'opennomi-zip-copy-v1' or not re.fullmatch(r'\d+\.\d+\.\d+', spec['version']):
        raise ValueError('Invalid release recipe')
    if not isinstance(spec['source_run_id'], int) or not re.fullmatch(r'opennomi-\d+-apk-parts', spec['source_artifact']):
        raise ValueError('Invalid source artifact')
    return spec


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recipe', type=Path, required=True)
    parser.add_argument('--source-parts', type=Path)
    parser.add_argument('--release', type=Path)
    parser.add_argument('--gha-output', type=Path)
    args = parser.parse_args()
    spec = load_spec(args.recipe)
    if args.gha_output:
        with args.gha_output.open('a') as out:
            out.write(f"run_id={spec['source_run_id']}\nartifact={spec['source_artifact']}\nversion={spec['version']}\n")
        return
    if not args.source_parts or not args.release:
        parser.error('Source parts and release directory are required')
    parts = sorted(args.source_parts.glob('opennomi-apk.part*'))
    if not parts or [p.name for p in parts] != [f'opennomi-apk.part{i:02d}' for i in range(len(parts))]:
        raise ValueError('Missing or unordered source APK parts')
    source = b''.join(p.read_bytes() for p in parts)
    if hashlib.sha256(source).hexdigest() != spec['unsigned_sha256']:
        raise ValueError('Unsigned source checksum mismatch')
    signed = bytearray()
    for segment in spec['segments']:
        if set(segment) == {'data'}:
            signed.extend(base64.b64decode(segment['data'], validate=True))
        elif set(segment) == {'offset', 'length'}:
            offset, length = segment['offset'], segment['length']
            if not isinstance(offset, int) or not isinstance(length, int) or offset < 0 or length < 0 or offset + length > len(source):
                raise ValueError('Invalid source slice')
            signed.extend(source[offset:offset + length])
        else:
            raise ValueError('Invalid recipe segment')
        if len(signed) > spec['signed_bytes']:
            raise ValueError('Recipe exceeds signed APK size')
    if len(signed) != spec['signed_bytes'] or hashlib.sha256(signed).hexdigest() != spec['signed_sha256']:
        raise ValueError('Signed APK reconstruction checksum mismatch')
    manifest = json.loads((args.release / 'manifest.json').read_text())
    if manifest['version'] != spec['version'] or manifest['apk_sha256'] != spec['signed_sha256'] or manifest['apk_bytes'] != len(signed):
        raise ValueError('Release manifest differs from signing recipe')
    cursor = 0
    for index, part in enumerate(manifest['parts']):
        name = part['file']
        if part['index'] != index or Path(name).name != name:
            raise ValueError('Invalid release part')
        data = signed[cursor:cursor + part['size']]
        if hashlib.sha256(data).hexdigest() != part['sha256']:
            raise ValueError('Release part checksum mismatch')
        target = args.release / 'apk-parts' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists() and target.read_bytes() != data:
            raise ValueError('Refusing to replace a different archived part')
        target.write_bytes(data)
        cursor += part['size']
    if cursor != len(signed):
        raise ValueError('Release manifest does not cover the complete APK')
    print(f"Archived {spec['version']}: {len(manifest['parts'])} verified signed APK parts")
    print('Signed APK SHA-256:', spec['signed_sha256'])


if __name__ == '__main__':
    main()
