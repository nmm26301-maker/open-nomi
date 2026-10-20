# Translation pipeline work for 0.63.0

Status: source changes submitted for validation; no signed installer delivered yet.

- FAST display no longer blocks the translation worker on the old two-caption reading queue. It keeps one latest waiting caption and caps the current caption's minimum dwell at 1.2 seconds. Intermediate completed results remain in the existing last-20 session history. COMPLETE retains ordered readable display and backpressure.
- Vosk native maximum-duration endpoints replace repeated mid-stream finalResult/reset calls. This retains the native feature pipeline between segments and removes the extra 1.2-second application cutoff grace. Natural silence endpoints remain enabled. This does not replace the acoustic or translation models.
- Added pinned continuous public speech (JFK excerpt in whisper.cpp, commit b0a11594aec50892a02cd8d129eee2dfe93a8bb8, blob 3184d372cd2f8b804d3a540c70ec50d927b335d2) alongside the existing number-word fixture. CI compares the frozen 0.62 manual cutoff with native FAST and COMPLETE for built-in and enhanced English recognizers; records recognized words, reference, WER, decoding time, first segment audio position.
- Four authored translation probes check negation, date, direction and a price using actual ML Kit output. Results and warm translation time are written to translation-quality.json in each emulator artifact, including on assertion failure.
- Word-error threshold is a basic regression guard, not an accuracy percentage claim. A single short English excerpt does not establish performance on films, Japanese, accents, music or overlapping speakers. CPU decode time and first segment position are not end-to-end phone latency.
- Offline translation uses ML Kit: https://developers.google.com/ml-kit/language/translation . Non-English pairs pass through English. Existing explicitly configured online text translation remains available; the update does not automatically send audio or enable a cloud provider.

Pending: successful release build/lint/unit tests, API 29/33 instrumented runs, inspection of actual quality output, original-certificate signing and release backup/readback verification.

Initial CI 39471111737 passed release build, lint and unit tests, but both API 29 and 33 caught the same real ML Kit defect: "Turn left, not right." -> "左转，不对。". This failure is retained in the run logs. A conservative English imperative normalizer now restores the repeated action only for anchored directional contrasts; it leaves questions, negative imperatives, non-directional statements, names and numbers untouched. The model is still ML Kit; this is not a general-purpose translation-quality guarantee. Three directional probes plus the other three semantic probes are checked against actual output, with raw model output retained for comparison.

The evidence collector was moved into one shell script: emulator-runner starts a separate shell for each YAML script line, so the earlier EXIT trap ran before tests. Revised CI must collect the public quality report after the tests.
