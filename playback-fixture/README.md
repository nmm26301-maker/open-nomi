# Test-only external playback app

This module is never included in the delivered OpenNomi APK. It plays the
Apache-2.0 public `python/example/test.wav` from alphacep/vosk-api, pinned to
commit `05adbfcc0df27a1535913c6accd4b7fc60ffd59d` and Git blob
`c41144a21710590e568e4e612d2a40baf9a71223`.

The separate package/UID lets the instrumentation test exercise the production
AudioPlaybackCapture path, which deliberately excludes OpenNomi's own audio.
The screen has no subtitles. The test requires real PCM recognition of the
spoken word "zero", a real on-device Chinese translation, and cancellation
without restart from late capture callbacks.

Source and license: https://github.com/alphacep/vosk-api/tree/05adbfcc0df27a1535913c6accd4b7fc60ffd59d
