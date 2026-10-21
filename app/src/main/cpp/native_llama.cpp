#include "runtime_contract.hpp"
#include "llama.h"
#include "chat.h"
#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <algorithm>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
using diary_local::Cancellation;
struct Session {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    common_chat_templates_ptr templates;
    int fd = -1;
    int context_tokens = 0;
    Cancellation cancellation;
    std::mutex operation;
    ~Session() {
        if (context) llama_free(context);
        templates.reset();
        if (model) llama_model_free(model);
        if (fd >= 0) ::close(fd);
    }
};
std::mutex registry_mutex;
std::mutex open_mutex;
std::unordered_map<jlong, std::shared_ptr<Session>> sessions;
jlong next_handle = 1;
std::once_flag backend_once;

void discard_log(ggml_log_level, const char *, void *) {}
bool should_abort(void * value) { return static_cast<Cancellation *>(value)->requested(); }

std::shared_ptr<Session> session(jlong handle) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto found = sessions.find(handle);
    if (found == sessions.end()) throw std::runtime_error("Local session is closed");
    return found->second;
}

std::string bytes(JNIEnv * env, jbyteArray input, std::size_t maximum) {
    if (!input) throw std::invalid_argument("Missing local input");
    const auto length = env->GetArrayLength(input);
    if (length < 0 || static_cast<std::size_t>(length) > maximum)
        throw std::invalid_argument("Local input exceeds budget");
    std::string result(static_cast<std::size_t>(length), '\0');
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte *>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot read local input");
    return result;
}

std::vector<llama_token> prompt_tokens(Session & current, const std::string & system, const std::string & user) {
    if (system.size() > diary_local::MAX_MESSAGE_BYTES ||
        user.size() > diary_local::MAX_MESSAGE_BYTES - system.size())
        throw std::invalid_argument("Local prompt exceeds budget");
    common_chat_templates_inputs input;
    input.messages = {common_chat_msg{"system", diary_local::protect_chat_markers(system)},
                      common_chat_msg{"user", diary_local::protect_chat_markers(user)}};
    input.use_jinja = true;
    input.enable_thinking = false;
    input.add_generation_prompt = true;
    const auto formatted = common_chat_templates_apply(current.templates.get(), input).prompt;
    if (formatted.empty() || formatted.size() > 2 * diary_local::MAX_MESSAGE_BYTES)
        throw std::invalid_argument("Local template exceeds budget");
    const auto * vocab = llama_model_get_vocab(current.model);
    int count = llama_tokenize(vocab, formatted.data(), static_cast<int>(formatted.size()), nullptr, 0, true, true);
    if (count >= 0) throw std::runtime_error("Cannot tokenize local prompt");
    std::vector<llama_token> result(static_cast<std::size_t>(-count));
    count = llama_tokenize(vocab, formatted.data(), static_cast<int>(formatted.size()), result.data(),
                           static_cast<int>(result.size()), true, true);
    if (count <= 0) throw std::runtime_error("Cannot tokenize local prompt");
    result.resize(static_cast<std::size_t>(count));
    return result;
}

void report_error(JNIEnv * env) {
    if (env->ExceptionCheck()) return;
    try { throw; }
    catch (const std::invalid_argument &) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Local inference input or token budget is invalid");
    } catch (const std::bad_alloc &) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Local inference memory allocation failed");
    } catch (...) {
        // Never propagate upstream messages: they can contain prompt text or model paths.
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Local inference failed or was cancelled");
    }
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_lifemanager_data_diary_ai_local_NativeLlamaBindings_open(
        JNIEnv * env, jobject, jint fd, jint context_tokens, jint threads) {
    try {
        diary_local::validate_settings(context_tokens, threads);
        std::lock_guard<std::mutex> opening(open_mutex);
        {
            std::lock_guard<std::mutex> lock(registry_mutex);
            if (!sessions.empty()) throw std::runtime_error("A local model is already open");
        }
        std::call_once(backend_once, [] { llama_log_set(discard_log, nullptr); llama_backend_init(); });
        auto current = std::make_shared<Session>();
        struct stat info{};
        if (fd < 0 || fstat(fd, &info) != 0 || !S_ISREG(info.st_mode) || info.st_size <= 0 ||
            (fcntl(fd, F_GETFL) & O_ACCMODE) != O_RDONLY)
            throw std::invalid_argument("Model requires a regular read-only descriptor");
        current->fd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
        if (current->fd < 0) throw std::runtime_error("Cannot duplicate local model descriptor");
        auto model_settings = llama_model_default_params();
        model_settings.n_gpu_layers = 0;
        model_settings.use_mmap = true;
        model_settings.use_mlock = false;
        const auto path = std::string("/proc/self/fd/") + std::to_string(current->fd);
        current->model = llama_model_load_from_file(path.c_str(), model_settings);
        if (!current->model) throw std::runtime_error("Cannot load local model");
        char architecture[64]{};
        if (llama_model_meta_val_str(current->model, "general.architecture", architecture, sizeof(architecture)) <= 0 ||
            std::string(architecture) != "qwen3")
            throw std::invalid_argument("Unsupported local model architecture");
        if (!llama_model_chat_template(current->model, nullptr))
            throw std::invalid_argument("Model has no chat template");
        current->templates = common_chat_templates_init(current->model, "");
        auto settings = llama_context_default_params();
        settings.n_ctx = static_cast<uint32_t>(context_tokens);
        settings.n_batch = 128;
        settings.n_ubatch = 128;
        settings.n_threads = threads;
        settings.n_threads_batch = threads;
        settings.offload_kqv = false;
        settings.abort_callback = should_abort;
        settings.abort_callback_data = &current->cancellation;
        current->context = llama_init_from_model(current->model, settings);
        if (!current->context) throw std::runtime_error("Cannot allocate local context");
        current->context_tokens = static_cast<int>(llama_n_ctx(current->context));
        std::lock_guard<std::mutex> lock(registry_mutex);
        const jlong handle = next_handle++;
        sessions.emplace(handle, current);
        return handle;
    } catch (...) { report_error(env); return 0; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_lifemanager_data_diary_ai_local_NativeLlamaBindings_countTokens(
        JNIEnv * env, jobject, jlong handle, jbyteArray system, jbyteArray user) {
    try {
        auto current = session(handle);
        std::lock_guard<std::mutex> lock(current->operation);
        return static_cast<jint>(prompt_tokens(*current, bytes(env, system, diary_local::MAX_MESSAGE_BYTES),
                                                          bytes(env, user, diary_local::MAX_MESSAGE_BYTES)).size());
    } catch (...) { report_error(env); return 0; }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_lifemanager_data_diary_ai_local_NativeLlamaBindings_generate(
        JNIEnv * env, jobject, jlong handle, jbyteArray system, jbyteArray user, jbyteArray grammar, jint output_tokens) {
    try {
        auto current = session(handle);
        std::lock_guard<std::mutex> lock(current->operation);
        current->cancellation.begin_generation();
        const auto system_text = bytes(env, system, diary_local::MAX_MESSAGE_BYTES);
        const auto user_text = bytes(env, user, diary_local::MAX_MESSAGE_BYTES);
        const auto grammar_text = bytes(env, grammar, diary_local::MAX_GRAMMAR_BYTES);
        auto tokens = prompt_tokens(*current, system_text, user_text);
        diary_local::validate_request(current->context_tokens, output_tokens, static_cast<int>(tokens.size()),
                                      system_text.size(), user_text.size(), grammar_text.size());
        if (grammar_text.find('\0') != std::string::npos) throw std::invalid_argument("Invalid local grammar");
        auto * vocab = llama_model_get_vocab(current->model);
        using Sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
        Sampler sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        auto * constrained = llama_sampler_init_grammar(vocab, grammar_text.c_str(), "root");
        if (!sampler || !constrained) throw std::invalid_argument("Invalid local grammar");
        llama_sampler_chain_add(sampler.get(), constrained);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        llama_kv_self_clear(current->context);
        for (std::size_t offset = 0; offset < tokens.size(); offset += 128) {
            if (current->cancellation.requested()) throw std::runtime_error("Cancelled");
            const int amount = static_cast<int>(std::min<std::size_t>(128, tokens.size() - offset));
            if (llama_decode(current->context, llama_batch_get_one(tokens.data() + offset, amount)) != 0)
                throw std::runtime_error("Prefill failed");
        }
        std::string result;
        bool eos = false;
        int generated = 0;
        for (; generated < output_tokens; ++generated) {
            if (current->cancellation.requested()) throw std::runtime_error("Cancelled");
            auto token = llama_sampler_sample(sampler.get(), current->context, -1);
            if (llama_vocab_is_eog(vocab, token)) { eos = true; break; }
            char piece[512];
            int size = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
            if (size < 0 || result.size() + static_cast<std::size_t>(size) > diary_local::MAX_OUTPUT_BYTES)
                break;
            result.append(piece, static_cast<std::size_t>(size));
            if (llama_decode(current->context, llama_batch_get_one(&token, 1)) != 0)
                throw std::runtime_error("Decode failed");
        }
        if (current->cancellation.requested()) throw std::runtime_error("Cancelled");
        jbyteArray text = env->NewByteArray(static_cast<jsize>(result.size()));
        if (!text) return nullptr;
        env->SetByteArrayRegion(text, 0, static_cast<jsize>(result.size()), reinterpret_cast<const jbyte *>(result.data()));
        auto type = env->FindClass("com/example/lifemanager/data/diary/ai/local/NativeLlama$Output");
        if (!type) return nullptr;
        auto constructor = env->GetMethodID(type, "<init>", "([BIIZ)V");
        if (!constructor) return nullptr;
        return env->NewObject(type, constructor, text, static_cast<jint>(tokens.size()), generated, static_cast<jboolean>(eos));
    } catch (...) { report_error(env); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_lifemanager_data_diary_ai_local_NativeLlamaBindings_cancel(JNIEnv *, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto found = sessions.find(handle);
    if (found != sessions.end()) found->second->cancellation.cancel();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_lifemanager_data_diary_ai_local_NativeLlamaBindings_close(JNIEnv *, jobject, jlong handle) {
    std::shared_ptr<Session> current;
    {
        std::lock_guard<std::mutex> lock(registry_mutex);
        auto found = sessions.find(handle);
        if (found == sessions.end()) return;
        current = found->second;
        current->cancellation.close();
        sessions.erase(found);
    }
    // Shared ownership keeps the model alive until any executing operation exits.
}
