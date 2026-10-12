#!/usr/bin/env python3
"""Sign a verified APK with external credentials; never print or commit passwords."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--unsigned-apk', type=Path, required=True)
    parser.add_argument('--unsigned-sha256', required=True)
    parser.add_argument('--credentials', type=Path, required=True)
    parser.add_argument('--certificate-sha256', required=True)
    parser.add_argument('--tools', type=Path, required=True, help='Directory with zipalign and apksigner.jar')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit('Output exists; choose a new path.')
    if hashlib.sha256(args.unsigned_apk.read_bytes()).hexdigest() != args.unsigned_sha256.lower():
        raise SystemExit('Unsigned APK checksum mismatch.')
    credentials = json.loads(args.credentials.read_text())
    expected = args.certificate_sha256.lower()
    if credentials['certificate_sha256'].lower() != expected:
        raise SystemExit('Credential certificate does not match expected certificate.')
    key_name = credentials['keystore']
    if Path(key_name).name != key_name:
        raise SystemExit('Keystore must be beside the credentials file.')
    tools = args.tools.resolve()
    zipalign = tools / 'zipalign'
    zipalign.chmod(zipalign.stat().st_mode | 0o100)
    env = os.environ.copy()
    env['LD_LIBRARY_PATH'] = str(tools / 'lib64') + (':' + env['LD_LIBRARY_PATH'] if env.get('LD_LIBRARY_PATH') else '')
    env['OPENNOMI_SIGN_STORE_PASSWORD'] = credentials['store_password']
    env['OPENNOMI_SIGN_KEY_PASSWORD'] = credentials['key_password']
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='opennomi-sign-') as temp:
        aligned = Path(temp) / 'aligned.apk'
        signed = Path(temp) / 'signed.apk'
        subprocess.run([str(zipalign), '-P', '16', '-f', '4', str(args.unsigned_apk.resolve()), str(aligned)], env=env, check=True)
        signer = ['java', '-jar', str(tools / 'apksigner.jar')]
        subprocess.run(signer + ['sign', '--ks', str(args.credentials.resolve().parent / key_name),
            '--ks-key-alias', credentials['key_alias'], '--ks-pass', 'env:OPENNOMI_SIGN_STORE_PASSWORD',
            '--key-pass', 'env:OPENNOMI_SIGN_KEY_PASSWORD', '--out', str(signed), str(aligned)], env=env, check=True)
        result = subprocess.run(signer + ['verify', '--verbose', '--print-certs', str(signed)],
            env=env, check=True, capture_output=True, text=True)
        actual = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)', result.stdout)
        if actual is None or actual.group(1).lower() != expected:
            raise SystemExit('Signed APK certificate mismatch; output was not published.')
        subprocess.run([str(zipalign), '-c', '-P', '16', '4', str(signed)], env=env, check=True)
        # Exclusive creation prevents a concurrent invocation overwriting a release.
        with args.output.open('xb') as output:
            output.write(signed.read_bytes())
    print(result.stdout.strip())
    print('APK SHA-256:', hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
