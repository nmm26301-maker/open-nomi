# OpenNomi release publication authorization

The repository owner explicitly authorized recurring publication on 2026-10-13: “允许，每次都允许，然后继续优化”. This follows the concrete request to publish each OpenNomi update's complete signed APK, validation reports, and encrypted signing/password backup to this public repository's `main` branch.

Within that release scope, subsequent updates may proceed without requesting the same publication permission again. This authorization applies to `nmm26301-maker/open-nomi` release archives and their verification records.

Continue following the release requirements in `AGENTS.md`: retain the release signing identity, increment the version, complete required validation, archive every APK part, and independently fetch the committed archive from GitHub to verify byte-identical restoration. Keep the recovery key separate; never publish plaintext private keys, passwords, or recovery keys.
