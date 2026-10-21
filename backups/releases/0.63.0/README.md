# OpenNomi 0.63.0

Signed arm64 installer, package com.opennomi.android, version code 63. Reuses the certificate of 0.53–0.62. The older com.opennomi.android.voicetest package has a different signing identity.

Run python3 restore.py --output-dir restored after downloading this complete directory, including every apk-parts file. The manifest validates every part and the final APK.

FAST display follows newer results without stalling translation; completed results remain in last-20 session history. Narrow English directional contrasts are expanded before offline translation. Recognition remains the 0.62 implementation; general accuracy and real-phone/Bluetooth latency are not claimed. First model downloads and playback-capture restrictions still apply.

The signing directory contains only the encrypted existing key/password backup and its recovery tool. Keep the recovery key separate. Source, CI, actual quality evidence and signing validation are recorded alongside this archive. GitHub fresh-clone readback and local byte-identical reconstruction both passed. The encrypted signing backup was recovered privately and its key, alias, passwords and certificate matched the signing inputs; see github-readback-verification.json.
