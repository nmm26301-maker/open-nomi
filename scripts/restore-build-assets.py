#!/usr/bin/env python3
"""Restore exact pinned native libraries and offline models before building."""
import hashlib
import io
import json
from pathlib import Path
import time
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = json.loads((ROOT / 'third-party/build-assets.json').read_text())


def valid(path, expected):
    return path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == expected


def download(url):
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                return response.read()
        except Exception:
            if attempt == 2:
                raise
            time.sleep(2 * (attempt + 1))


def install(relative, data):
    expected = MANIFEST['files'][relative]
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError('SHA-256 mismatch: ' + relative)
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    print('Verified:', relative, flush=True)


for relative, url in MANIFEST['downloads'].items():
    if not valid(ROOT / relative, MANIFEST['files'][relative]):
        install(relative, download(url))

model_root = 'app/src/main/assets/playback-model/'
missing = [p for p, digest in MANIFEST['files'].items()
           if p.startswith(model_root) and not valid(ROOT / p, digest)]
if missing:
    with zipfile.ZipFile(io.BytesIO(download(MANIFEST['model_zip']))) as archive:
        # Only allow explicitly listed paths; never extract arbitrary archive entries.
        for relative in missing:
            member = MANIFEST['model_prefix'] + relative[len(model_root):]
            install(relative, archive.read(member))

for relative, digest in MANIFEST['files'].items():
    if not valid(ROOT / relative, digest):
        raise ValueError('Missing or changed build input: ' + relative)
print('All offline build assets verified.', flush=True)
