#pragma once
#include <atomic>
#include <cstddef>
#include <stdexcept>
#include <string>

namespace diary_local {
constexpr std::size_t MAX_MESSAGE_BYTES = 48 * 1024;
constexpr std::size_t MAX_GRAMMAR_BYTES = 48 * 1024;
constexpr std::size_t MAX_OUTPUT_BYTES = 64 * 1024;

inline void validate_settings(int context, int threads) {
    if (context < 512 || context > 8192 || threads < 1 || threads > 8)
        throw std::invalid_argument("Invalid local inference settings");
}

inline void validate_request(int context, int output, int prompt_tokens,
                             std::size_t system_bytes, std::size_t user_bytes,
                             std::size_t grammar_bytes) {
    if (output < 1 || output > 2048 || prompt_tokens < 1 ||
        prompt_tokens > context - output || system_bytes > MAX_MESSAGE_BYTES ||
        user_bytes > MAX_MESSAGE_BYTES - system_bytes || grammar_bytes < 1 ||
        grammar_bytes > MAX_GRAMMAR_BYTES)
        throw std::invalid_argument("Local inference input exceeds budget");
}

class Cancellation {
    std::atomic<bool> cancelled_{false};
    std::atomic<bool> closed_{false};
public:
    void cancel() { cancelled_.store(true, std::memory_order_relaxed); }
    void close() { closed_.store(true, std::memory_order_relaxed); }
    void begin_generation() const {
        if (requested()) throw std::runtime_error("Session cancellation is terminal");
    }
    bool requested() const { return closed_.load(std::memory_order_relaxed) || cancelled_.load(std::memory_order_relaxed); }
};

// Diary text must not introduce Qwen role boundary tokens into the formatted prompt.
inline std::string protect_chat_markers(const std::string & text) {
    std::string result;
    for (std::size_t i = 0; i < text.size(); ++i) {
        result += text[i];
        if (text[i] == '<' && i + 1 < text.size() && text[i + 1] == '|') result += "\xe2\x81\xa0";
    }
    return result;
}
}
