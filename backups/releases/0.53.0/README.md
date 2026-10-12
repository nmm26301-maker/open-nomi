# OpenNomi 0.53.0 完整发布备份

已签名安装包按分片归档，合并后与交付文件字节完全一致。完整下载本目录，运行 `python3 restore.py --output-dir restored`。脚本先校验所有分片及完整 SHA-256，拒绝覆盖已有文件。

`signing/` 同时加密备份本版需要的 JKS 私钥、别名、密钥库密码和密钥密码。使用单独保存的 `OpenNomi-new-signing-recovery-key.json` 配合 `signing/decrypt.py` 恢复。恢复钥匙不提交仓库。

包名 com.opennomi.android，新签名；与原包名 voicetest 版并行安装，本机数据不自动迁移。后续同包名同签名版本可覆盖升级。实际验证记录在 verification.json。
