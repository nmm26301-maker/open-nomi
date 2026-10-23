#include "hymt_core.h"
#include <chrono>
#include <iostream>
#include <stdexcept>

/** @brief 检查人工样例的关键含义，避免把同义措辞差异误判为翻译错误。 */
bool meaningMatches(const std::string & source, const std::string & result) {
    const auto has = [&](const char * text) { return result.find(text) != std::string::npos; };
    const bool negative = has("不") || has("别") || has("勿");
    if (source == "Do not open the door.") return has("门") && negative;
    if (source == "Do open the door.") return has("开") && has("门") && !negative;
    if (source == "The door is closed.") return has("门") && (has("关") || has("闭"));
    if (source == "Turn left, not right.") return has("左") && has("右") && negative;
    if (source == "The ticket costs 25 dollars.") return has("25") && (has("美元") || has("美金"));
    return source == "The meeting is tomorrow, not today." && has("明天") && has("今天") && negative;
}

/** @brief 用同一生产推理内核输出公开样例与流式延迟，供 CI 核对。 */
int main(int argc, char ** argv) {
    if (argc != 2) return 2;
    try {
        HyMtCore engine(argv[1], 4);
        for (const auto & input : {"Do not open the door.", "Do open the door.", "The door is closed.", "Turn left, not right.", "The ticket costs 25 dollars.", "The meeting is tomorrow, not today."}) {
            engine.resetCancel();
            const auto start = std::chrono::steady_clock::now();
            long first = -1;
            const auto result = engine.translate(std::string("将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\n") + input,
                [&](const std::string &) { if (first < 0) first = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count(); });
            const auto total = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
            const auto reused = engine.lastReusedTokens();
            HyMtCore cold(argv[1], 4);
            const auto cold_start = std::chrono::steady_clock::now();
            const auto expected = cold.translate(std::string("将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\n") + input, {});
            const auto cold_total = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - cold_start).count();
            std::cout << "COLD_TRANSLATED: " << expected << "\nWARM_TRANSLATED: " << result << std::endl;
            if (!meaningMatches(input, result) || !meaningMatches(input, expected)) throw std::runtime_error("Warm/cold semantic probe failed");
            if (std::string(input) != "Do not open the door." && reused == 0) throw std::runtime_error("Expected shared prefix reuse");
            std::cout << "SOURCE: " << input << "\nTRANSLATED: " << result << "\nFIRST_MS: " << first << "\nTOTAL_MS: " << total
                      << "\nREUSED_TOKENS: " << reused << "\nINPUT_TOKENS: " << engine.lastInputTokens() << "\nCOLD_TOTAL_MS: " << cold_total << "\nCOLD_MATCH: " << (result == expected ? "true" : "false") << "\nSEMANTIC_CHECKS: true" << std::endl;
        }
        for (const auto & background : {"The ticket costs 25 dollars.", "The train arrives tomorrow. The ticket costs 25 dollars."}) {
            const auto instruction = std::string("请结合背景信息将以下文本翻译为中文，注意只输出待翻译文本的译文，不要重复背景或额外解释：\n\n【背景信息】\n") + background + "\n\n【待翻译文本】\nDo not open the door.";
            const auto actual = engine.translate(instruction, {});
            HyMtCore cold(argv[1], 4);
            const auto expected = cold.translate(instruction, {});
            std::cout << "CONTEXT_WARM: " << actual << "\nCONTEXT_COLD: " << expected << "\nCONTEXT_REUSED_TOKENS: " << engine.lastReusedTokens() << std::endl;
            if (!meaningMatches("Do not open the door.", actual) || !meaningMatches("Do not open the door.", expected) ||
                actual.find("25") != std::string::npos || expected.find("25") != std::string::npos)
                throw std::runtime_error("Background must not replace or contaminate current input");
        }
        std::cout << "ROLLING_CONTEXT_CHECKS: true" << std::endl;
        engine.resetCancel();
        bool cancelled = false;
        try { engine.translate("将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\nDo not open the door. The ticket costs 25 dollars. The meeting is tomorrow, not today.",
                [&](const std::string &) { engine.cancel(); }); }
        catch (const std::exception &) { cancelled = true; }
        if (!cancelled) throw std::runtime_error("Cancellation must interrupt generation");
        engine.resetCancel();
        const auto after = engine.translate("将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\nThe door is closed.", {});
        if (!meaningMatches("The door is closed.", after) || engine.lastReusedTokens() != 0) throw std::runtime_error("Cancellation must invalidate prefix reuse");
        std::cout << "CANCELLED_CACHE_CLEARED: true\nAFTER_CANCEL: " << after << std::endl;
        return 0;
    } catch (const std::exception & error) { std::cerr << error.what() << std::endl; return 1; }
}

