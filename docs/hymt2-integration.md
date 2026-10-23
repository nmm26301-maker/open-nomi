# Hy-MT2 realtime translation

OpenNomi uses the official Tencent Hy-MT2 1.8B Q4_K_M GGUF, pinned by revision, exact byte size and SHA-256 in `third-party/hymt2.json`. The model downloads separately (1.13 GB), with Wi-Fi default, resumable transfer and verified file import. Already installed weights work offline. Voice recognition remains the selected Vosk model; Hy-MT2 translates recognized sentences into Chinese.

Inference reuses pinned upstream llama.cpp under the MIT license. A native CPU worker serializes loading, generation and unloading; cancellation requests abort CPU computation and late callbacks cannot update a paused/stopped session. Native UTF-8 fragments are shown as provisional Chinese before the completed translation enters recent history. The final result requires an end-of-generation token; truncated output is rejected. Model chat control tokens are tokenized separately from ordinary user content.

The official 1.25-bit model was tested first with its linked STQ PR. The verified weights failed GGUF tensor-layout validation (`blk.0.attn_k_norm.weight` offset 203154464, expected 203277344). This variant is not shipped as a working option. The implementation uses standard Q4_K_M for compatibility and retains the failed variant's provenance in the model manifest. No claim of a usable 440 MB integration is made.

Validation must include native real-model translation, streaming, semantic probes, cancellation and subsequent reuse, offline reload, actual visible workspace output, and audio from a separate background player. Android simulator timings are not real-phone or Bluetooth latency measurements. Model choice, provider attribution, first-use download size and voice-capture limitations remain visible in the app.

Primary sources:
- https://github.com/Tencent-Hunyuan/Hy-MT2
- https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF
- https://github.com/ggml-org/llama.cpp

Validation passed for source `a804be881cd8bbd496e38c4c0178d9070094ea99` in [Android CI](https://github.com/nmm26301-maker/open-nomi/actions/runs/39881492665): 201 unit tests, release lint, and 26 instrumentation checks on each of API 29 and API 33. Both devices downloaded the actual pinned model, streamed complete UTF-8, preserved the four authored semantic probes, cancelled then translated uncached input, reloaded weights offline, rendered the real text result, and translated audio captured from a separate player UID into a visible background overlay. Pause/resume and stop cleanup were checked.

API 29 emulator: first text chunk 3482–4655 ms, completed authored sentences 3991–5332 ms; background captured speech first streamed text 29730 ms and first final caption 40755 ms after player launch. These are recorded observations, not phone performance guarantees; a first chunk may contain only punctuation. Real-time lag remains possible.

API 33 emulator: first text chunk 3270–4741 ms, completed authored sentences 3825–6307 ms; background captured speech first streamed text 22410 ms and first final caption 50342 ms after player launch. These are recorded observations, not phone performance guarantees; a first chunk may contain only punctuation. Real-time lag remains possible.

The Linux native probe passed in [CI](https://github.com/nmm26301-maker/open-nomi/actions/runs/39832756341); its first `Hello world.` output remained English. The small sample set does not establish general translation accuracy. Standard Q4 weights replace the unusable 1.25-bit attempt; the 440 MB variant remains unsupported. Real-phone, Bluetooth and broad-language validation remain unfinished. Actual reports and screenshots are under `backups/releases/0.65.0/validation/`.
