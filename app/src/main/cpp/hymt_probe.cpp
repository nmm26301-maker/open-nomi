#include "hymt_core.h"
#include <chrono>
#include <iostream>
#include <stdexcept>

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
            const auto expected = cold.translate(std::string("将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\n") + input, {});
            if (result != expected) throw std::runtime_error("Warm/cold translation mismatch");
            if (std::string(input) != "Do not open the door." && reused == 0) throw std::runtime_error("Expected shared prefix reuse");
            std::cout << "SOURCE: " << input << "\nTRANSLATED: " << result << "\nFIRST_MS: " << first << "\nTOTAL_MS: " << total
                      << "\nREUSED_TOKENS: " << reused << "\nINPUT_TOKENS: " << engine.lastInputTokens() << "\nCOLD_MATCH: true" << std::endl;
        }
        engine.resetCancel();
        bool cancelled = false;
        try { engine.translate("将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\nDo not open the door. The ticket costs 25 dollars. The meeting is tomorrow, not today.",
                [&](const std::string &) { engine.cancel(); }); }
        catch (const std::exception &) { cancelled = true; }
        if (!cancelled) throw std::runtime_error("Cancellation must interrupt generation");
        engine.resetCancel();
        const auto after = engine.translate("将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\nThe door is closed.", {});
        if (after.empty() || engine.lastReusedTokens() != 0) throw std::runtime_error("Cancellation must invalidate prefix reuse");
        std::cout << "CANCELLED_CACHE_CLEARED: true\nAFTER_CANCEL: " << after << std::endl;
        return 0;
    } catch (const std::exception & error) { std::cerr << error.what() << std::endl; return 1; }
}

