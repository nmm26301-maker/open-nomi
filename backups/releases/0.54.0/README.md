# OpenNomi 0.54.0 完整发布备份

本目录保存已签名 APK 的全部分片、校验清单、恢复脚本和本版使用的签名及密码加密备份。

包名 `com.opennomi.android`，版本代码 54，arm64-v8a。沿用 0.53.0 证书，可覆盖升级 0.53.0；与原 `com.opennomi.android.voicetest` 并行安装，旧包数据不自动迁移。

下载整个目录后运行：

```sh
python3 restore.py --output-dir restored
```

脚本校验全部分片和完整 APK 的 SHA-256，拒绝覆盖已有文件。APK 摘要：`4f58d45e0ada98ca2074efe6f8db6c6a49a08b747180cca1a60059e05d3cd379`。

`signing/` 的 AES-256-GCM 备份包含 JKS 私钥、别名、密钥库密码和密钥密码，恢复脚本与签名摘要一起保存。解密钥匙单独保管，不在公开仓库里。各版固定沿用同一套发布签名。

```sh
python3 signing/decrypt.py --key-file /path/to/OpenNomi-new-signing-recovery-key.json --output-dir restored-signing
```

翻译无需 API Key，默认首次通过 Wi-Fi 下载语言模型，下载后可离线使用；LibreTranslate 仍可选。144 项单元测试通过，Android API 29、33 各 19 项测试通过，涵盖实际模型翻译、断网重载翻译与中文界面。Lint 无 Error/Fatal，47 项 Warning。详见 `verification.json` 与 [源码更新](../../../docs/0.54-voice-and-backup.md)。
