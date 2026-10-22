#include "hymt_core.h"
#include <chrono>
#include <iostream>

/** @brief 用同一生产推理内核输出公开样例与流式延迟，供 CI 核对。 */
int main(int argc, char ** argv) {
    if (argc != 2) return 2;
    try {
        HyMtCore engine(argv[1], 4);
        for (const auto & input : {"Hello world.", "Do not open the door.", "Turn left, not right.", "The ticket costs 25 dollars.", "The meeting is tomorrow, not today."}) {
            engine.resetCancel();
            const auto start = std::chrono::steady_clock::now();
            long first = -1;
            const auto result = engine.translate(std::string("将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\n") + input,
                [&](const std::string &) { if (first < 0) first = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count(); });
            const auto total = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
            std::cout << "SOURCE: " << input << "\nTRANSLATED: " << result << "\nFIRST_MS: " << first << "\nTOTAL_MS: " << total << std::endl;
        }
        return 0;
    } catch (const std::exception & error) { std::cerr << error.what() << std::endl; return 1; }
}
