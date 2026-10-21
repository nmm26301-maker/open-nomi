#!/usr/bin/env python3
"""Fetch committed release bytes anew and preserve verifiable readback evidence."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--version',required=True)
p.add_argument('--destination',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
if not re.fullmatch(r'\d+\.\d+\.\d+',a.version):raise SystemExit('Invalid release version')
if a.destination.exists() or a.output.exists():raise SystemExit('Readback destination must be new')
repository=os.environ.get('GITHUB_REPOSITORY','nmm26301-maker/open-nomi')
if repository!='nmm26301-maker/open-nomi':raise SystemExit('Unexpected release repository')
subprocess.run(['git','clone','--depth','1','--filter=blob:none','--no-checkout','--branch','main',
    'https://github.com/'+repository+'.git',str(a.destination)],check=True)
relative='backups/releases/'+a.version
subprocess.run(['git','-C',str(a.destination),'sparse-checkout','set',relative],check=True)
subprocess.run(['git','-C',str(a.destination),'checkout','main'],check=True)
release=a.destination/relative
subprocess.run(['python3',str(release/'restore.py'),'--output-dir',str(a.output)],check=True)
manifest=json.loads((release/'manifest.json').read_text())
apk=a.output/manifest['apk_file']
digest=hashlib.sha256(apk.read_bytes()).hexdigest()
if digest!=manifest['apk_sha256']:raise SystemExit('Fresh checkout APK hash mismatch')
proof={'repository':repository,'fetched_commit':subprocess.check_output(
    ['git','-C',str(a.destination),'rev-parse','HEAD'],text=True).strip(),
    'release_version':a.version,'from_fresh_github_clone':True,'apk_sha256':digest,
    'manifest_sha256':hashlib.sha256((release/'manifest.json').read_bytes()).hexdigest(),
    'verified_parts':len(manifest['parts']),'workflow_run_id':os.environ.get('GITHUB_RUN_ID'),
    'verified_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
(release/'github-clone-readback-proof.json').write_text(json.dumps(proof,indent=2)+'\n')
print(json.dumps(proof,indent=2))
