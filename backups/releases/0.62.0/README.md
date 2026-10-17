# OpenNomi 0.62.0 完整发布备份

包名 com.opennomi.android，版本代码 62，沿用 0.53–0.61 发布证书，支持覆盖升级这些版本。
本版完善切换应用后的悬浮翻译、位置与字号、暂停标记、通知显示和暂停／继续控制。用法和限制见 ../../../docs/0.62-background-subtitles.md。

下载整个目录后运行 python3 restore.py --output-dir restored，核验全部 APK 分片、总大小及 SHA-256。
精确源码、原始单元和 API 29/33 报告、工作流见 manifest.json、verification.json 和 validation/。
signing/ 是包含 JKS、别名、密码和证书的 AES-GCM 加密备份；恢复钥匙单独保管，禁止提交。
恢复命令：python3 signing/decrypt.py --key-file /path/to/OpenNomi-new-signing-recovery-key.json --output-dir restored-signing。
