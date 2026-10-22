#!/usr/bin/env bash
set -euo pipefail
mkdir -p ui-captures
# emulator-runner executes each YAML script line in a separate shell. Keep the trap
# in this one script so evidence is collected after tests, including assertion failures.
collect_quality() {
  if adb pull /data/local/tmp/translation-quality.json ui-captures/translation-quality.json; then
    cat ui-captures/translation-quality.json
    printf '\n'
  fi
  if adb pull /data/local/tmp/translation-preview.json ui-captures/translation-preview.json; then
    cat ui-captures/translation-preview.json
    printf '\n'
  fi
}
collect_hymt() {
  collect_quality
  for name in hymt-quality hymt-video; do
    if adb pull "/data/local/tmp/$name.json" "ui-captures/$name.json"; then
      cat "ui-captures/$name.json"
    fi
  done
}
trap collect_hymt EXIT
adb install -r playback-fixture/build/outputs/apk/debug/playback-fixture-debug.apk
gradle :app:connectedDebugAndroidTest -POPENNOMI_ABIS=x86_64 -Pkotlin.incremental=false --no-daemon
for capture in fish-workstation conversation-home conversation-memory translation-workspace video-live-workspace background-subtitles background-preview hymt-workspace hymt-background; do
  adb pull "/data/local/tmp/$capture.png" "ui-captures/$capture.png"
done
