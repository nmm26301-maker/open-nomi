#!/usr/bin/env python3
"""Restore the official-model llama.cpp runtime at a pinned commit; optionally fetch verified weights."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request

root = Path(__file__).resolve().parents[1]
spec = json.loads((root / 'third-party/hymt2.json').read_text())
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--model', action='store_true')
args = parser.parse_args()
vendor = root / 'vendor/llama.cpp'
if not (vendor / '.git').exists():
    vendor.mkdir(parents=True, exist_ok=True)
    subprocess.run(['git', 'init', '-q', str(vendor)], check=True)
    subprocess.run(['git', '-C', str(vendor), 'remote', 'add', 'origin',
                    'https://github.com/' + spec['runtime_repository'] + '.git'], check=True)
actual = subprocess.run(['git', '-C', str(vendor), 'rev-parse', '--verify', 'HEAD'],
                        capture_output=True, text=True)
if actual.returncode or actual.stdout.strip() != spec['runtime_commit']:
    subprocess.run(['git', '-C', str(vendor), 'fetch', '--depth=1', 'origin', spec['runtime_commit']], check=True)
    subprocess.run(['git', '-C', str(vendor), 'checkout', '--detach', 'FETCH_HEAD'], check=True)
assert subprocess.check_output(['git', '-C', str(vendor), 'rev-parse', 'HEAD'], text=True).strip() == spec['runtime_commit']
print('Verified pinned llama.cpp runtime:', spec['runtime_commit'], flush=True)
if args.model:
    model = root / 'build/hymt2' / spec['model_file']
    model.parent.mkdir(parents=True, exist_ok=True)
    def valid(path):
        if not path.is_file() or path.stat().st_size != spec['model_bytes']:
            return False
        with path.open('rb') as stream:
            return hashlib.file_digest(stream, 'sha256').hexdigest() == spec['model_sha256']
    if not valid(model):
        partial = model.with_suffix('.download')
        url = ('https://huggingface.co/' + spec['model_repository'] + '/resolve/' +
               spec['model_revision'] + '/' + spec['model_file'])
        with urllib.request.urlopen(url, timeout=120) as response, partial.open('wb') as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
                if output.tell() > spec['model_bytes']:
                    raise ValueError('Model exceeds pinned size')
        assert valid(partial), 'Model checksum mismatch'
        partial.replace(model)
    print('Verified Hy-MT2 weights:', spec['model_sha256'], flush=True)
