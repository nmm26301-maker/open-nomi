# OpenNomi 0.54.0 签名与密码加密备份

沿用 0.53.0 发布签名，证书 SHA-256：`8132825a68acba5653aba326b84e301bb65b28664af0d0727d7a5f5b41ad1b8e`。这是 2026-10-12 新建发布密钥，与原 voicetest 版不同。

`OpenNomi-new-signing.enc.json` 使用 AES-256-GCM 加密保存 JKS 私钥、别名、密钥库密码、密钥密码和证书。其摘要及初次生成校验在 `verification.json`，本版 APK 签名校验在上一级 `verification.json`。解密钥匙单独保管。

```sh
python3 decrypt.py --key-file /path/to/OpenNomi-new-signing-recovery-key.json --output-dir restored-signing
```

需 Python 3 与 cryptography，恢复输出包含 JKS、SIGNING-CREDENTIALS.json 与 certificate.der。后续发布固定使用此密钥，原 voicetest 版不能用此签名覆盖安装。
