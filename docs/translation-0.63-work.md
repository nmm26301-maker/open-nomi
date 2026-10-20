# OpenNomi 0.63.0 translation work

Status: source validated; **no signed installer delivered**. The local execution environment is unavailable, so original-certificate signing and release-backup readback remain pending.

Validated source: [56130dc28359a479671cfcd4eeb720c8526c5278](https://github.com/nmm26301-maker/open-nomi/commit/56130dc28359a479671cfcd4eeb720c8526c5278). Final checks: [workflow 39475774099](https://github.com/nmm26301-maker/open-nomi/actions/runs/39475774099).

## Final changes

- FAST display keeps one latest waiting result and caps the current caption's minimum dwell at 1.2 seconds. It no longer blocks the translation worker behind the old two-caption reading queue. Intermediate results can be superseded on the floating display; completed source and translation remain in the existing last-20 session history. COMPLETE retains readable, ordered display and backpressure. This is a display policy, not a measured end-to-end phone latency guarantee.
- The offline translator expands a narrow English directional contrast into complete imperative clauses. For example, raw ML Kit translates “Turn left, not right.” as “左转，不对。”. Translating “turn to the left. Do not turn to the right.” instead produces “转到左边。 不要转向右边。”. The original source remains the cache/history key. The normalizer is limited to anchored directional commands and leaves questions, negative commands, other statements, names and numbers untouched.
- The delivered 0.62 speech recognizer and pause/grace policy are preserved. Neither a new recognition engine nor a general translation-quality upgrade is claimed. The UI explains the live/readable tradeoff, local translation limits, first downloads and the need to compare model results.

## Validation and measured limits

Release build, unit tests and release lint passed. API 29 and 33 instrumented checks passed, including real language-model download, actual translation, offline reload, visible translated result, external-player audio capture and background subtitle controls.

All six authored semantic probes passed on both APIs: a prohibition, price, date contrast and three directional contrasts. Raw model outputs and expanded inputs are retained in [API 29](validation/0.63-final-api29.json) and [API 33](validation/0.63-final-api33.json). Warm sentence translation took 26–41 ms on these emulators; this excludes initial downloads/preparation and is not real-phone end-to-end latency.

The pinned 11-second public JFK excerpt has 22 reference words. Final actual recognition:

| Model | Mode | API 29 word errors | API 33 word errors |
| --- | --- | ---: | ---: |
| Built-in English | FAST | 2 | 3 |
| Built-in English | COMPLETE | 1 | 1 |
| Enhanced English | FAST | 1 | 1 |
| Enhanced English | COMPLETE | 1 | 1 |

The sample still contains recognition errors. Enhanced decoding also costs substantially more CPU on these emulators. Do not extrapolate one short English recording to films, Japanese, music, accents or overlapping speakers. The three-word test bound is a fixture regression guard, not a product accuracy target. No real-phone, Bluetooth or general accuracy/latency percentage is claimed.

Public audio is pinned by whisper.cpp commit b0a11594aec50892a02cd8d129eee2dfe93a8bb8 and Git blob 3184d372cd2f8b804d3a540c70ec50d927b335d2. The fixture script checks bytes and format before preparing test PCM. The benchmark uses production AudioPhraseClock with deterministic PCM time; its audio positions and decode times are not end-to-end screen latency.

## Rejected experiments retained for review

The initial real-model run [39471111737](https://github.com/nmm26301-maker/open-nomi/actions/runs/39471111737) failed on the “not right” mistranslation on both systems; [failure evidence](validation/0.63-direction-failure.json) is retained.

Simply shortening native utterances increased enhanced-model word errors from 1 to 3–4. Preserving audio features at the old manual boundary also increased enhanced-model errors from 1 to 2. Both speech changes were rejected and reverted. Raw evidence: [short endpoint API 29](validation/0.63-rejected-short-endpoint-api29.json), [API 33](validation/0.63-rejected-short-endpoint-api33.json), [continuity API 29](validation/0.63-rejected-continuity-api29.json), [API 33](validation/0.63-rejected-continuity-api33.json).

## Installer handoff

[Source validation and exact build artifacts](validation/0.63-source-handoff.json) record the final source, jobs, artifact digests and expiry. These artifacts are unsigned verification output. Restore the execution environment, reuse the existing release key and scripts/sign-release.py, then complete the required signed-APK backup/reconstruction checks before delivering an update. No new installer, signing result or release-backup verification is represented by this source validation.

ML Kit limitations: https://developers.google.com/ml-kit/language/translation .
