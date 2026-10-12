# OpenNomi release requirements

These requirements persist the owner's instructions for every update.

- Preserve package `com.opennomi.android` and the release certificate used by 0.53.0; increment version code for every delivered update. The original `com.opennomi.android.voicetest` has a different certificate and cannot be covered by this key.
- Archive every delivered signed APK in `backups/releases/<version>/`, including every APK part, SHA-256 manifest, `restore.py`, exact built source commit, workflow and validation results. Fetch the committed backup from GitHub and verify byte-identical reconstruction before reporting completion.
- Include the signing backup in each release's `signing/` directory. The encrypted backup must include JKS, alias, store password, key password and certificate. Keep the recovery key separate. Never commit plaintext private keys, passwords or recovery keys, or print them to logs.
- Reuse the existing signing key. Use `scripts/sign-release.py` and `scripts/restore-release.py`; collect all build transfer parts rather than assuming a fixed count.
- Verify unit tests, release lint, and instrumented tests on API 29 and 33. Translation checks must include a real language-model download, translation, reload without network, and the visible translated result.
- Explain first-use model downloads and media capture restrictions in the UI. Do not claim real-phone or Bluetooth headset validation based on simulator tests.
- Repository-backed files are persisted in GitHub; do not duplicate the repository in another file store. Deliver signed installation files through the user-facing file channel.
