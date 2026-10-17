import argparse,base64,hashlib,json,io,zipfile
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

p=argparse.ArgumentParser();p.add_argument('--key-file',type=Path,required=True);p.add_argument('--output-dir',type=Path,required=True);a=p.parse_args()
root=Path(__file__).resolve().parent;key=json.loads(a.key_file.read_text());enc=root/'OpenNomi-new-signing.enc.json'
if hashlib.sha256(enc.read_bytes()).hexdigest()!=key['backup_sha256']:raise SystemExit('备份校验失败')
data=json.loads(enc.read_text());raw=AESGCM(base64.b64decode(key['key_base64'])).decrypt(base64.b64decode(data['nonce_base64']),base64.b64decode(data['ciphertext_base64']),data['format'].encode())
if a.output_dir.exists():raise SystemExit('请选择一个尚不存在的输出目录，避免覆盖已有签名')
a.output_dir.mkdir(mode=0o700,parents=True)
with zipfile.ZipFile(io.BytesIO(raw)) as z:
 for name in z.namelist():
  if Path(name).name!=name:raise SystemExit('非法归档路径')
  dest=a.output_dir/name;dest.write_bytes(z.read(name));dest.chmod(0o600)
print('新签名密钥及密码文件已恢复。此密钥不能覆盖原签名安装版。')

