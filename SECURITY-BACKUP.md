# OpenNomi 安全备份状态

核对日期：2026-11-01。

## 已确认
- README 当前记录 Android 0.67.0，功能与安装说明位于仓库。
- `backups/releases/0.67.0/` 有 131 个 APK 分块、GitHub clone readback 验证记录；记录的 APK SHA-256 为 `e60ba2701a249ab4252f47e1d3c7b0983329a915f4e5f15d8e3e1144e35a0ec5`，大小 96,245,142 字节，历史记录称与原签名 APK 逐字节一致。
- `backups/signing/new-signing-20261012/` 有加密的新签名备份和恢复脚本；该密文已镜像到 [Zizai 私人备份](https://github.com/nmm26301-maker/Zizai/blob/main/backups/security-audit-2026-11-01/OpenNomi/OpenNomi-new-signing.enc.json)。文档明确该签名是**新生成的，不是原签名**，不能直接覆盖安装旧签名版本。
- 另一个名为 `opennomi` 的空仓库不应被当作此项目的备份。

## 待补齐
- [x] 已有 0.67.0 APK 分块归档与 readback 证明（仍需重新运行恢复脚本才能在当前环境独立复核）。
- [ ] 核对 `backups/signing/new-signing-20261012` 的恢复口令；本次 Library 搜索未找到对应恢复密钥文件。
- [ ] 确认 0.67.0 当前 APK 实际使用的证书与历史 0.65/0.66 签名连续性；不要把 2026-10-12 新生成的签名误当原签名。
- [ ] 如计划用新签名发布，必须使用不同包名并行安装，或先设计数据导出/迁移；不得直接覆盖旧签名版。
- [ ] 在干净目录重组 131 个分块，重新计算 SHA-256，并实际验证 APK 签名与安装。

## 结论
0.67.0 的 APK 长期分块归档与历史 readback 证明已存在；未完成的是当前环境重新重组验证、对应原签名恢复口令可用性，以及新旧签名的兼容策略。