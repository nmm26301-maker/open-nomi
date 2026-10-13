# OpenNomi 新签名备份（2026-10-12）

这是重新生成的 RSA-3072 发布签名，**不是原签名**。原 0.52.0 APK 未修改，本目录也未生成新的安装包。

`OpenNomi-new-signing.enc.json` 使用 AES-256-GCM 加密备份了新 JKS、密钥别名、密钥库密码、密钥密码和公开证书。解密凭据单独交付，不提交 GitHub。已通过私钥签署/证书验签测试及加密恢复校验，详见 verification.json。

## 恢复

需 Python 3 与 cryptography。在本目录运行：

```sh
python3 decrypt.py --key-file /path/to/OpenNomi-new-signing-recovery-key.json --output-dir restored-new-signing
```

恢复目录包含 JKS、SIGNING-CREDENTIALS.json 和 certificate.der。请妥善保存解密凭据。

## 安装兼容性

Android 不允许用新签名直接覆盖原签名版。后续可以用不同包名并行安装新签名版，保留旧版及其本机数据；或先导出所需数据，再卸载旧版并安装新签名版。后一种方式不能保证旧版未导出的配置、记忆和设备绑定得到恢复。

后续构建应固定使用这套新密钥，不要每次重新生成。若以后找回原密钥，应单独核验和备份，不应把此新密钥标为原密钥。

