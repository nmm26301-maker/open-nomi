# Hy-MT2 realtime translation

OpenNomi uses the official Tencent Hy-MT2 1.8B Q4_K_M GGUF, pinned by revision, exact byte size and SHA-256 in `third-party/hymt2.json`. The model downloads separately (1.13 GB), with Wi-Fi default, resumable transfer and verified file import. Already installed weights work offline. Voice recognition remains the selected Vosk model; Hy-MT2 translates recognized sentences into Chinese.

Inference reuses pinned upstream llama.cpp under the MIT license. A native CPU worker serializes loading, generation and unloading; cancellation requests abort CPU computation and late callbacks cannot update a paused/stopped session. Native UTF-8 fragments are shown as provisional Chinese before the completed translation enters recent history. The final result requires an end-of-generation token; truncated output is rejected. Model chat control tokens are tokenized separately from ordinary user content.

The official 1.25-bit model was tested first with its linked STQ PR. The verified weights failed GGUF tensor-layout validation (`blk.0.attn_k_norm.weight` offset 203154464, expected 203277344). This variant is not shipped as a working option. The implementation uses standard Q4_K_M for compatibility and retains the failed variant's provenance in the model manifest. No claim of a usable 440 MB integration is made.

Validation must include native real-model translation, streaming, semantic probes, cancellation and subsequent reuse, offline reload, actual visible workspace output, and audio from a separate background player. Android simulator timings are not real-phone or Bluetooth latency measurements. Model choice, provider attribution, first-use download size and voice-capture limitations remain visible in the app.

Primary sources:
- https://github.com/Tencent-Hunyuan/Hy-MT2
- https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF
- https://github.com/ggml-org/llama.cpp

Pending: Android builds, actual model tests and signed release validation.
