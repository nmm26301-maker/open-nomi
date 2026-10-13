#!/usr/bin/env python3
"""Reassemble a byte-identical signed APK from the adjacent verified manifest."""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir',type=Path,default=Path('restored'))
    args=parser.parse_args()
    root=Path(__file__).resolve().parent
    manifest=json.loads((root/'manifest.json').read_text())
    name=manifest['apk_file']
    if Path(name).name!=name:
        raise SystemExit('Invalid output filename.')
    args.output_dir.mkdir(parents=True,exist_ok=True)
    output=args.output_dir/name
    if output.exists():
        raise SystemExit('Output already exists; choose a new output directory.')
    digest=hashlib.sha256();written=0
    with tempfile.TemporaryFile() as assembled:
        for index,part in enumerate(manifest['parts']):
            filename=part['file']
            if part['index']!=index or Path(filename).name!=filename:
                raise SystemExit('Invalid or unordered part manifest.')
            path=root/'apk-parts'/filename
            if not path.is_file():
                raise SystemExit('Missing APK part: '+filename+'. Download the complete release folder first.')
            data=path.read_bytes()
            if len(data)!=part['size'] or hashlib.sha256(data).hexdigest()!=part['sha256']:
                raise SystemExit('APK part checksum mismatch: '+filename)
            assembled.write(data);digest.update(data);written+=len(data)
        if written!=manifest['apk_bytes'] or digest.hexdigest()!=manifest['apk_sha256']:
            raise SystemExit('Complete APK checksum mismatch; no output written.')
        assembled.seek(0)
        with output.open('xb') as destination:
            while chunk:=assembled.read(1024*1024):
                destination.write(chunk)
    print('Restored verified signed APK:',output)
    print('APK SHA-256:',digest.hexdigest())
    print('Certificate SHA-256:',manifest['certificate_sha256'])


if __name__=='__main__':
    main()
