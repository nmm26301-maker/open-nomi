# OpenNomi 0.64.0

English on-device video translation in FAST mode can show a provisional translation before Vosk finalizes the utterance. The final recognizer, phrase boundaries, confirmed translation semantics and last-20 history are unchanged from 0.63.

The preview uses the complete-word prefix repeated by two partial callbacks, excludes the newest word, requires at least three words, caps input at 96 characters and limits requests to once per 1200ms. The service warms native English inference before starting audio capture. It requires the already prepared English translator and makes no model download or cloud request. It runs only with no final translation or display backlog. There is at most one scheduled preview coroutine at a time. A preview taking more than 400ms disables further previews for that capture session. An already running native operation can delay a newly arrived final translation until completion; coroutine cancellation or the 30-second timeout cannot cancel the underlying ML Kit Task; it may outlive its caller.

Draft text has its own state fields and the visible label “临时译文 · 识别中，可能改写”. The foreground view and service-owned background overlay show it. Notifications and recent history contain confirmed translations only. Prefix revisions, finalized utterances, configuration resets, pauses, resyncs and sharing stop invalidate old preview tickets; late results cannot resurrect a prior utterance or session.

Validation includes regression cases for retracted negation, identical text across sessions, rate and size bounds, and separate provisional/confirmed display. The actual external-player smoke check requires visible Chinese draft text in the real background overlay before the first confirmed caption, confirms empty history at that point, records first draft, first confirmed and a subsequent utterance draft elapsed times, and checks cleanup after pause and stop. Existing real model download/offline reload checks, public speech recognition benchmark and six semantic probes remain required on API 29 and 33. These checks do not establish general translation accuracy or real-phone/Bluetooth latency.

An intermediate API 33 check exposed an obsolete assertion that still expected the first caption after waiting for later speech. The assertion now checks the current native overlay against actual completed session translations; the first result remains checked in recent history. The failed run and correction are retained in `docs/validation/0.64-layout-assertion-correction.json`.

Source and final validation evidence are recorded with the signed release archive.

Validated source: `a202201216defc8d918279fcbee63e42cbef3d51`. Full CI: https://github.com/nmm26301-maker/open-nomi/actions/runs/39534774548. 197 unit tests, release lint, 24 instrumented tests on each API passed.

| API | First visible draft | First confirmed caption | Subsequent draft | Earlier by |
| --- | ---: | ---: | ---: | ---: |
| 29 | 3711 ms | 4968 ms | 9188 ms | 1257 ms |
| 33 | 3749 ms | 5176 ms | 9444 ms | 1427 ms |

Times run from launching the authored external player after models are prepared; they are one emulator sample per API, include player start/capture/recognition/translation/display, and are not a real-phone performance guarantee. Raw JSON and rendered preview/confirmed-overlay images are retained in the release validation directory. The provisional number-sequence output is not a general translation accuracy assessment.
