#pragma once
#include "llama.h"
#include <atomic>
#include <functional>
#include <memory>
#include <string>
#include <vector>

class HyMtCore {
public:
    explicit HyMtCore(const std::string & path, int threads);
    ~HyMtCore();
    HyMtCore(const HyMtCore &) = delete;
    HyMtCore & operator=(const HyMtCore &) = delete;
    void cancel();
    void resetCancel();
    size_t lastReusedTokens() const { return reused_tokens_; }
    size_t lastInputTokens() const { return input_tokens_; }
    std::string translate(const std::string & instruction,
                          const std::function<void(const std::string &)> & partial);
private:
    llama_model * model_ = nullptr;
    llama_context * context_ = nullptr;
    const llama_vocab * vocab_ = nullptr;
    std::atomic<bool> cancelled_{false};
    std::vector<llama_token> prompt_cache_;
    size_t reused_tokens_ = 0;
    size_t input_tokens_ = 0;
    std::vector<llama_token> tokens(const std::string & text, bool special, bool bos) const;
    std::vector<llama_token> promptTokens(const std::string & content) const;
};

