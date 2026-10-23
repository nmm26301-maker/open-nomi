#include "hymt_core.h"
#include "ggml-backend.h"
#include <algorithm>
#include <chrono>
#include <mutex>
#include <stdexcept>

namespace {
/** @brief 判断最后一个 UTF-8 字符是否完整，避免流式中文出现替代字符。 */
bool completeUtf8(const std::string & value) {
    if (value.empty()) return false;
    size_t lead = value.size() - 1;
    while (lead > 0 && (static_cast<unsigned char>(value[lead]) & 0xc0) == 0x80) --lead;
    const auto byte = static_cast<unsigned char>(value[lead]);
    const size_t width = byte < 0x80 ? 1 : byte < 0xe0 ? 2 : byte < 0xf0 ? 3 : 4;
    return value.size() - lead == width;
}
}

/** @brief 加载经 SHA256 校验的模型，使用保守的 CPU 指令集和有界上下文。 */
HyMtCore::HyMtCore(const std::string & path, int threads) {
    static std::once_flag backend;
    std::call_once(backend, [] { ggml_backend_load_all(); llama_backend_init(); });
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    params.load_mode = LLAMA_LOAD_MODE_MMAP;
    model_ = llama_model_load_from_file(path.c_str(), params);
    if (!model_) throw std::runtime_error("Hy-MT2 模型加载失败，请重新校验模型");
    vocab_ = llama_model_get_vocab(model_);
    auto config = llama_context_default_params();
    config.n_ctx = 1024;
    config.n_batch = 512;
    config.n_ubatch = 128;
    config.n_threads = std::clamp(threads, 1, 4);
    config.n_threads_batch = config.n_threads;
    config.offload_kqv = false;
    config.op_offload = false;
    config.abort_callback = [](void * data) { return static_cast<std::atomic<bool> *>(data)->load(); };
    config.abort_callback_data = &cancelled_;
    context_ = llama_init_from_model(model_, config);
    if (!context_) { llama_model_free(model_); model_ = nullptr; throw std::runtime_error("Hy-MT2 内存不足，请关闭其他应用后重试"); }
}

/** @brief 释放上下文与权重；调用方保证在串行工作线程中执行。 */
HyMtCore::~HyMtCore() {
    if (context_) llama_free(context_);
    if (model_) llama_model_free(model_);
}
/** @brief 请求 CPU 推理中止，可跨线程调用。 */
void HyMtCore::cancel() { cancelled_.store(true); }
/** @brief 在登记新请求前清除旧的取消状态。 */
void HyMtCore::resetCancel() { cancelled_.store(false); }

/** @brief 区分可信模板控制标记与普通待译文本进行分词。 */
std::vector<llama_token> HyMtCore::tokens(const std::string & text, bool special, bool bos) const {
    const auto needed = -llama_tokenize(vocab_, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, bos, special);
    if (needed < 0 || needed > 4096) throw std::runtime_error("原文过长，请分段翻译");
    std::vector<llama_token> result(static_cast<size_t>(needed));
    const auto count = llama_tokenize(vocab_, text.data(), static_cast<int32_t>(text.size()), result.data(), needed, bos, special);
    if (count < 0) throw std::runtime_error("Hy-MT2 分词失败");
    result.resize(static_cast<size_t>(count));
    return result;
}

/** @brief 使用模型自带聊天模板，用户内容中的控制标记按普通文字处理。 */
std::vector<llama_token> HyMtCore::promptTokens(const std::string & content) const {
    const std::string marker = "OPENNOMI_TRANSLATION_BODY_6c47726c";
    const llama_chat_message message{"user", marker.c_str()};
    const auto tmpl = llama_model_chat_template(model_, nullptr);
    std::vector<char> buffer(4096);
    int length = llama_chat_apply_template(tmpl, &message, 1, true, buffer.data(), static_cast<int32_t>(buffer.size()));
    if (length < 0) throw std::runtime_error("Hy-MT2 聊天模板不受支持");
    if (length > static_cast<int>(buffer.size())) {
        buffer.resize(static_cast<size_t>(length));
        length = llama_chat_apply_template(tmpl, &message, 1, true, buffer.data(), length);
    }
    const std::string formatted(buffer.data(), static_cast<size_t>(length));
    const auto at = formatted.find(marker);
    if (at == std::string::npos) throw std::runtime_error("Hy-MT2 模板缺少用户内容");
    auto result = tokens(formatted.substr(0, at), true, true);
    const auto body = tokens(content, false, false);
    const auto suffix = tokens(formatted.substr(at + marker.size()), true, false);
    result.insert(result.end(), body.begin(), body.end());
    result.insert(result.end(), suffix.begin(), suffix.end());
    return result;
}

/** @brief 生成有完整结束标记的译文，流式片段仅用于预览，不接受被截断的结果。 */
std::string HyMtCore::translate(const std::string & instruction, const std::function<void(const std::string &)> & partial) {
    if (cancelled_.load()) throw std::runtime_error("Hy-MT2 已取消");
    auto input = promptTokens(instruction);
    if (input.empty() || input.size() > 740) throw std::runtime_error("原文和上下文过长，请分段翻译");
    size_t shared = 0;
    while (shared < prompt_cache_.size() && shared + 1 < input.size() && prompt_cache_[shared] == input[shared]) ++shared;
    const auto memory = llama_get_memory(context_);
    // 保留完全相同的前缀，删除旧原文的剩余部分和全部生成内容；最后一个提示词始终重新解码。
    if (shared == 0 || !llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(shared), -1)) {
        llama_memory_clear(memory, true);
        shared = 0;
    }
    // 只有完整成功的结果才能重用；取消、解码失败和截断会令下一句从空上下文开始。
    prompt_cache_.clear();
    reused_tokens_ = shared;
    input_tokens_ = input.size();
    auto sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(
        llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(20));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(.6f, 1));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab_), 128, 1.05f, 0.f, 0.f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(.7f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(2026));
    for (size_t offset = shared; offset < input.size(); offset += 512) {
        const auto size = std::min<size_t>(512, input.size() - offset);
        if (cancelled_.load() || llama_decode(context_, llama_batch_get_one(input.data() + offset, static_cast<int>(size))))
            throw std::runtime_error("Hy-MT2 推理已中断");
    }
    std::string output;
    auto last = std::chrono::steady_clock::now();
    for (int count = 0; count < 256; ++count) {
        if (cancelled_.load()) throw std::runtime_error("Hy-MT2 已取消");
        auto token = llama_sampler_sample(sampler.get(), context_, -1);
        if (llama_vocab_is_eog(vocab_, token)) {
            if (output.empty() || !completeUtf8(output)) throw std::runtime_error("Hy-MT2 未返回完整译文");
            if (partial) partial(output);
            prompt_cache_ = input;
            return output;
        }
        std::vector<char> piece(256);
        auto size = llama_token_to_piece(vocab_, token, piece.data(), static_cast<int>(piece.size()), 0, false);
        if (size < 0) { piece.resize(static_cast<size_t>(-size)); size = llama_token_to_piece(vocab_, token, piece.data(), -size, 0, false); }
        if (size < 0) throw std::runtime_error("Hy-MT2 译文解码失败");
        output.append(piece.data(), static_cast<size_t>(size));
        if (output.size() > 16384) throw std::runtime_error("Hy-MT2 译文超过长度限制");
        const auto now = std::chrono::steady_clock::now();
        if (partial && completeUtf8(output) && now - last >= std::chrono::milliseconds(80)) { partial(output); last = now; }
        if (llama_decode(context_, llama_batch_get_one(&token, 1))) throw std::runtime_error("Hy-MT2 推理已中断");
    }
    throw std::runtime_error("Hy-MT2 译文未结束，请缩短原文后重试");
}

