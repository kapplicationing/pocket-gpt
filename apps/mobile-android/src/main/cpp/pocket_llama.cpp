#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cctype>
#include <cerrno>
#include <cmath>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <fcntl.h>
#include <fstream>
#include <functional>
#include <mutex>
#include <sstream>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace {
constexpr const char * TAG = "PocketLlamaJNI";
constexpr int DEFAULT_CONTEXT_SIZE = 2048;
constexpr int DEFAULT_BATCH_SIZE = 512;
constexpr int DEFAULT_PROMPT_DECODE_BATCH_SIZE = 512;
constexpr int CPU_PROMPT_DECODE_BATCH_CAP = 128;
constexpr int CPU_PREFILL_ONLY_BATCH_CAP = 128;
constexpr int CPU_PREFILL_ONLY_PROMPT_DECODE_BATCH_CAP = 64;
constexpr int CACHE_POLICY_OFF = 0;
constexpr int CACHE_POLICY_PREFIX_REUSE = 1;
constexpr int CACHE_POLICY_PREFIX_REUSE_STRICT = 2;
constexpr jint STREAM_STATUS_COMPLETED = 0;
constexpr jint STREAM_STATUS_MAX_TOKENS = 1;
constexpr jint STREAM_STATUS_CANCELLED = 2;
constexpr jint STREAM_STATUS_CALLBACK_ERROR = 3;
constexpr jint STREAM_STATUS_UTF8_STREAM_ERROR = 4;
constexpr jint STREAM_STATUS_RUNTIME_ERROR = 5;

std::mutex g_mutex;
enum class BackendInitMode {
    NONE,
    CPU_ONLY,
    GPU_ENABLED,
};
BackendInitMode g_backend_init_mode = BackendInitMode::NONE;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
llama_sampler * g_sampler = nullptr;
llama_model * g_draft_model = nullptr;
llama_context * g_draft_context = nullptr;
llama_sampler * g_draft_sampler = nullptr;
std::atomic<bool> g_cancel_requested{false};
constexpr size_t MAX_TARGET_PREFIX_STATE_BYTES = 192u * 1024u * 1024u;
constexpr size_t MAX_DRAFT_PREFIX_STATE_BYTES = 48u * 1024u * 1024u;
bool g_model_uses_recurrent_memory = false;
bool g_model_uses_hybrid_memory = false;
bool g_prefix_cache_prefill_only_mode = false;

mtmd_context * g_mtmd_ctx = nullptr;

struct PrefixCacheSlot {
    std::vector<llama_token> target_prompt_tokens;
    std::vector<llama_token> draft_prompt_tokens;
    std::vector<uint8_t> target_seq_state;
    std::vector<uint8_t> draft_seq_state;
    std::string cache_key;
    uint64_t last_used_epoch = 0;
    bool valid = false;
};

struct PrefixCacheTelemetry {
    uint64_t target_hits = 0;
    uint64_t target_misses = 0;
    uint64_t draft_hits = 0;
    uint64_t draft_misses = 0;
    uint64_t store_state_success = 0;
    uint64_t store_state_failure = 0;
    uint64_t store_state_over_budget = 0;
    uint64_t restore_state_success = 0;
    uint64_t restore_state_failure = 0;
    uint64_t restore_state_over_budget = 0;
    int32_t last_slot = -1;
    size_t last_reused_tokens = 0;
    bool last_cache_hit = false;
    std::string last_stage;
    std::string last_store_reason;
    std::string last_restore_reason;
};

struct MmapTelemetry {
    std::string last_label;
    long long last_bytes = 0;
    int last_result = 0;
    long long last_duration_ms = 0;
    uint64_t readahead_count = 0;
};

std::array<PrefixCacheSlot, 2> g_prefix_cache_slots;
int32_t g_active_prefix_cache_slot = -1;
uint64_t g_prefix_cache_epoch = 0;
PrefixCacheTelemetry g_prefix_cache_telemetry;
MmapTelemetry g_mmap_telemetry;
int32_t g_prompt_decode_batch_size = DEFAULT_PROMPT_DECODE_BATCH_SIZE;
int32_t g_runtime_context_size = DEFAULT_CONTEXT_SIZE;
float g_sampling_temperature = 0.7f;
int32_t g_sampling_top_k = 40;
float g_sampling_top_p = 0.95f;
float g_sampling_min_p = 0.0f;
float g_sampling_typical_p = 1.0f;
int32_t g_sampling_repeat_last_n = 64;
float g_sampling_repeat_penalty = 1.0f;
float g_sampling_frequency_penalty = 0.0f;
float g_sampling_presence_penalty = 0.0f;
int32_t g_sampling_mirostat = 0;
float g_sampling_mirostat_tau = 5.0f;
float g_sampling_mirostat_eta = 0.1f;
float g_sampling_xtc_threshold = 0.1f;
float g_sampling_xtc_probability = 0.0f;
int32_t g_sampling_seed = -1;
int32_t g_runtime_n_keep = 128;
bool g_model_use_mmap = true;
bool g_model_use_mlock = false;
int32_t g_model_layer_count = 0;
uint64_t g_model_size_bytes = 0;
bool g_last_flash_attn_requested = false;
bool g_last_flash_attn_gpu_ops = false;
bool g_last_flash_attn_active = false;
bool g_last_quantized_kv_cache = false;
std::string g_last_kv_cache_mode = "f16";
std::string g_last_kv_cache_fallback_reason;
bool g_last_opencl_flash_guard_applied = false;
bool g_last_opencl_quant_kv_guard_applied = false;
std::string g_last_model_quantization = "unknown";
std::string g_active_model_quantization = "unknown";
std::string g_backend_profile = "auto";
std::string g_active_backend = "cpu";
uint64_t g_active_backend_memory_bytes = 0;
std::string g_opencl_icd_filenames;
std::string g_opencl_icd_source = "unset";
std::string g_last_backend_error_code;
std::string g_last_backend_error_detail;
bool g_llama_logging_installed = false;
float g_speculative_acceptance_rate = 0.65f;
bool g_speculative_enabled = false;
int32_t g_speculative_max_draft_tokens = 6;
int32_t g_speculative_min_draft_tokens = 2;
uint64_t g_context_shift_count = 0;
uint64_t g_context_rebuild_count = 0;

struct LoadProgressContext {
    JNIEnv * env = nullptr;
    jobject callback = nullptr;
    jmethodID on_progress = nullptr;
    float offset = 0.0f;
    float scale = 1.0f;
    float last_progress = 0.0f;
    bool cancelled = false;
};

int32_t clamp_i32(int32_t value, int32_t min_value, int32_t max_value) {
    return std::max(min_value, std::min(value, max_value));
}

const char * compiled_gpu_backends();
const char * backend_init_mode_label(BackendInitMode mode);
const char * model_memory_mode_label();
const char * prefix_cache_mode_label();

int32_t resolve_threads(jint requested_threads) {
    const auto hardware_threads = std::thread::hardware_concurrency();
    const int32_t fallback = static_cast<int32_t>(
        std::max(2u, std::min(hardware_threads == 0 ? 4u : hardware_threads, 8u)));
    if (requested_threads <= 0) {
        return fallback;
    }
    return clamp_i32(static_cast<int32_t>(requested_threads), 1, 16);
}

int32_t resolve_batch(jint requested_batch) {
    if (requested_batch <= 0) {
        return DEFAULT_BATCH_SIZE;
    }
    return clamp_i32(static_cast<int32_t>(requested_batch), 32, 2048);
}

int32_t resolve_context_size(jint requested_ctx) {
    if (requested_ctx <= 0) {
        return DEFAULT_CONTEXT_SIZE;
    }
    return clamp_i32(static_cast<int32_t>(requested_ctx), 512, 32768);
}

bool model_has_no_kv_cache() {
    return g_model_uses_recurrent_memory && !g_model_uses_hybrid_memory;
}

bool should_run_native_warmup(bool gpu_ops_active) {
    // Speed-first policy:
    // Native warmup is most valuable when GPU kernels/shader paths are active.
    // On CPU-only device paths it adds visible load latency without enough
    // first-turn return.
    return gpu_ops_active;
}

int warmup_context(llama_context * ctx, const llama_model * model) {
    const llama_vocab * vocab = llama_model_get_vocab(model);
    std::vector<llama_token> tokens;
    const llama_token bos = llama_vocab_bos(vocab);
    const llama_token eos = llama_vocab_eos(vocab);
    if (bos != LLAMA_TOKEN_NULL) {
        tokens.push_back(bos);
    }
    if (eos != LLAMA_TOKEN_NULL) {
        tokens.push_back(eos);
    }
    if (tokens.empty()) {
        tokens.push_back(0);
    }

    int result = 0;
    if (llama_model_has_encoder(model)) {
        result = llama_encode(ctx, llama_batch_get_one(tokens.data(), tokens.size()));
        llama_token decoder_start = llama_model_decoder_start_token(model);
        if (decoder_start == LLAMA_TOKEN_NULL) {
            decoder_start = bos != LLAMA_TOKEN_NULL ? bos : tokens.front();
        }
        tokens.assign(1, decoder_start);
    }
    if (result == 0 && llama_model_has_decoder(model)) {
        result = llama_decode(ctx, llama_batch_get_one(tokens.data(), tokens.size()));
    }
    llama_memory_clear(llama_get_memory(ctx), true);
    llama_synchronize(ctx);
    llama_perf_context_reset(ctx);
    return result;
}

void clear_prefix_cache_slots_locked();
bool decode_tokens_from_offset(
    llama_context * ctx,
    const std::vector<llama_token> & tokens,
    size_t start_index);

int32_t resolve_speculative_max_draft(int remaining_tokens) {
    const int configured_max = std::max(0, std::min(g_speculative_max_draft_tokens, remaining_tokens - 1));
    if (configured_max <= 0) {
        return 0;
    }
    const float adaptive_factor = std::clamp(0.25f + g_speculative_acceptance_rate, 0.35f, 1.0f);
    const int adaptive_max = static_cast<int>(std::lround(static_cast<float>(configured_max) * adaptive_factor));
    return std::max(g_speculative_min_draft_tokens, std::min(configured_max, adaptive_max));
}

void log_speculative_metrics(size_t accepted_drafts, size_t drafted_tokens, int adaptive_max_draft, int remaining_tokens) {
    if (drafted_tokens > 0) {
        const float instant_acceptance = static_cast<float>(accepted_drafts) / static_cast<float>(drafted_tokens);
        g_speculative_acceptance_rate = std::clamp(
            (g_speculative_acceptance_rate * 0.8f) + (instant_acceptance * 0.2f),
            0.0f,
            1.0f);
    }
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "SPECULATIVE|accepted=%zu|drafted=%zu|acceptance_rate=%.3f|adaptive_max=%d|remaining=%d",
        accepted_drafts,
        drafted_tokens,
        static_cast<double>(g_speculative_acceptance_rate),
        adaptive_max_draft,
        remaining_tokens);
}

bool madvise_model_readahead(const std::string & path, const char * label) {
    const auto started_at = std::chrono::steady_clock::now();
    if (path.empty()) {
        return false;
    }
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        const auto duration_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started_at).count();
        g_mmap_telemetry.last_label = label != nullptr ? label : "";
        g_mmap_telemetry.last_bytes = 0;
        g_mmap_telemetry.last_result = -1;
        g_mmap_telemetry.last_duration_ms = duration_ms;
        __android_log_print(
            ANDROID_LOG_WARN,
            TAG,
            "MMAP|stage=readahead_open_failed|label=%s|errno=%d|detail=%s|duration_ms=%lld",
            label,
            errno,
            std::strerror(errno),
            duration_ms);
        return false;
    }

    struct stat info {};
    if (fstat(fd, &info) != 0 || info.st_size <= 0) {
        const int saved_errno = errno;
        close(fd);
        const auto duration_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started_at).count();
        g_mmap_telemetry.last_label = label != nullptr ? label : "";
        g_mmap_telemetry.last_bytes = 0;
        g_mmap_telemetry.last_result = -1;
        g_mmap_telemetry.last_duration_ms = duration_ms;
        __android_log_print(
            ANDROID_LOG_WARN,
            TAG,
            "MMAP|stage=readahead_stat_failed|label=%s|errno=%d|detail=%s|duration_ms=%lld",
            label,
            saved_errno,
            std::strerror(saved_errno),
            duration_ms);
        return false;
    }

    void * mapping = mmap(nullptr, static_cast<size_t>(info.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
    if (mapping == MAP_FAILED) {
        const int saved_errno = errno;
        close(fd);
        const auto duration_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started_at).count();
        g_mmap_telemetry.last_label = label != nullptr ? label : "";
        g_mmap_telemetry.last_bytes = static_cast<long long>(info.st_size);
        g_mmap_telemetry.last_result = -1;
        g_mmap_telemetry.last_duration_ms = duration_ms;
        __android_log_print(
            ANDROID_LOG_WARN,
            TAG,
            "MMAP|stage=readahead_mmap_failed|label=%s|errno=%d|detail=%s|duration_ms=%lld",
            label,
            saved_errno,
            std::strerror(saved_errno),
            duration_ms);
        return false;
    }

    int advise_result = 0;
#if defined(POSIX_MADV_WILLNEED)
    advise_result = posix_madvise(mapping, static_cast<size_t>(info.st_size), POSIX_MADV_WILLNEED);
#else
    advise_result = madvise(mapping, static_cast<size_t>(info.st_size), MADV_WILLNEED);
#endif
    munmap(mapping, static_cast<size_t>(info.st_size));
    close(fd);
    const auto duration_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started_at).count();
    g_mmap_telemetry.last_label = label != nullptr ? label : "";
    g_mmap_telemetry.last_bytes = static_cast<long long>(info.st_size);
    g_mmap_telemetry.last_result = advise_result;
    g_mmap_telemetry.last_duration_ms = duration_ms;
    g_mmap_telemetry.readahead_count += 1;
    __android_log_print(
        advise_result == 0 ? ANDROID_LOG_INFO : ANDROID_LOG_WARN,
        TAG,
        "MMAP|stage=readahead|label=%s|bytes=%lld|result=%d|duration_ms=%lld",
        label,
        static_cast<long long>(info.st_size),
        advise_result,
        duration_ms);
    return advise_result == 0;
}

llama_model * load_model_with_mmap_retry(
    const std::string & path,
    llama_model_params * params,
    const char * label) {
    if (params == nullptr) {
        return nullptr;
    }
    llama_model * model = llama_model_load_from_file(path.c_str(), *params);
    auto * progress_context = static_cast<LoadProgressContext *>(params->progress_callback_user_data);
    if (model != nullptr || !params->use_mmap || (progress_context != nullptr && progress_context->cancelled)) {
        return model;
    }
    __android_log_print(
        ANDROID_LOG_WARN,
        TAG,
        "MMAP|stage=load_retry_without_mmap|label=%s|main_gpu=%d|gpu_layers=%d",
        label,
        static_cast<int>(params->main_gpu),
        static_cast<int>(params->n_gpu_layers));
    llama_model_params retry_params = *params;
    retry_params.use_mmap = false;
    model = llama_model_load_from_file(path.c_str(), retry_params);
    if (model != nullptr) {
        *params = retry_params;
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "MMAP|stage=load_retry_without_mmap_success|label=%s",
            label);
    }
    return model;
}

double read_status_value_mb(const char * key) {
    if (key == nullptr || *key == '\0') {
        return -1.0;
    }
    std::ifstream status("/proc/self/status");
    if (!status.is_open()) {
        return -1.0;
    }
    std::string line;
    while (std::getline(status, line)) {
        if (line.rfind(key, 0) != 0) {
            continue;
        }
        std::istringstream parser(line.substr(line.find(':') + 1));
        long value_kb = 0;
        parser >> value_kb;
        if (value_kb > 0) {
            return static_cast<double>(value_kb) / 1024.0;
        }
        break;
    }
    return -1.0;
}

double read_peak_rss_mb() {
    return read_status_value_mb("VmHWM:");
}

double read_current_rss_mb() {
    return read_status_value_mb("VmRSS:");
}

bool runtime_is_released_locked() {
    return g_model == nullptr &&
        g_context == nullptr &&
        g_sampler == nullptr &&
        g_draft_model == nullptr &&
        g_draft_context == nullptr &&
        g_draft_sampler == nullptr;
}

bool report_load_progress(float progress, void * user_data) {
    auto * context = static_cast<LoadProgressContext *>(user_data);
    if (context == nullptr || context->env == nullptr || context->callback == nullptr || context->on_progress == nullptr) {
        return true;
    }
    const float clamped = std::clamp(progress, 0.0f, 1.0f);
    const float scaled = std::clamp(context->offset + (clamped * context->scale), 0.0f, 1.0f);
    const float monotonic = std::max(context->last_progress, scaled);
    context->last_progress = monotonic;
    const jboolean should_continue = context->env->CallBooleanMethod(
        context->callback,
        context->on_progress,
        monotonic);
    if (context->env->ExceptionCheck()) {
        context->env->ExceptionClear();
        context->cancelled = true;
        return false;
    }
    if (should_continue != JNI_TRUE) {
        context->cancelled = true;
        return false;
    }
    return true;
}

bool configure_progress_callback(
    JNIEnv * env,
    jobject callback,
    float offset,
    float scale,
    LoadProgressContext * progress_context,
    llama_model_params * model_params) {
    if (progress_context == nullptr || model_params == nullptr) {
        return false;
    }
    progress_context->env = env;
    progress_context->callback = callback;
    progress_context->offset = offset;
    progress_context->scale = scale;
    if (callback == nullptr || env == nullptr) {
        model_params->progress_callback = nullptr;
        model_params->progress_callback_user_data = nullptr;
        return false;
    }
    jclass callback_class = env->GetObjectClass(callback);
    if (callback_class == nullptr) {
        model_params->progress_callback = nullptr;
        model_params->progress_callback_user_data = nullptr;
        return false;
    }
    progress_context->on_progress = env->GetMethodID(callback_class, "onProgress", "(F)Z");
    env->DeleteLocalRef(callback_class);
    if (progress_context->on_progress == nullptr) {
        model_params->progress_callback = nullptr;
        model_params->progress_callback_user_data = nullptr;
        return false;
    }
    model_params->progress_callback = report_load_progress;
    model_params->progress_callback_user_data = progress_context;
    return true;
}

bool is_out_of_memory_errno(int error_code) {
    return error_code == ENOMEM;
}

void trim_prompt_tokens_for_context(std::vector<llama_token> * prompt_tokens, size_t max_prompt_tokens) {
    if (prompt_tokens == nullptr || prompt_tokens->size() <= max_prompt_tokens) {
        return;
    }
    const size_t original_size = prompt_tokens->size();
    if (max_prompt_tokens <= 1) {
        prompt_tokens->assign(prompt_tokens->end() - 1, prompt_tokens->end());
        return;
    }
    size_t preserve_head = max_prompt_tokens > 384
        ? std::min<size_t>(128, max_prompt_tokens - 1)
        : std::max<size_t>(1, std::min<size_t>(128, max_prompt_tokens / 3));
    preserve_head = std::min(preserve_head, max_prompt_tokens - 1);
    const size_t keep_tail = max_prompt_tokens - preserve_head;
    std::vector<llama_token> trimmed;
    trimmed.reserve(max_prompt_tokens);
    trimmed.insert(trimmed.end(), prompt_tokens->begin(), prompt_tokens->begin() + static_cast<std::ptrdiff_t>(preserve_head));
    trimmed.insert(trimmed.end(), prompt_tokens->end() - static_cast<std::ptrdiff_t>(keep_tail), prompt_tokens->end());
    *prompt_tokens = std::move(trimmed);
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "PROMPT_TRIM|original=%zu|max=%zu|preserve_head=%zu|keep_tail=%zu|trimmed=%zu",
        original_size,
        max_prompt_tokens,
        preserve_head,
        keep_tail,
        prompt_tokens->size());
}

llama_pos resolve_n_keep_tokens(size_t tracked_tokens) {
    if (tracked_tokens == 0) {
        return 0;
    }
    const llama_pos max_keep = static_cast<llama_pos>(tracked_tokens > 0 ? tracked_tokens - 1 : 0);
    return std::clamp(static_cast<llama_pos>(g_runtime_n_keep), static_cast<llama_pos>(0), max_keep);
}

llama_pos resolve_context_shift_discard(llama_pos context_tokens, int reserved_tokens, llama_pos n_keep) {
    const llama_pos runtime_ctx = static_cast<llama_pos>(std::max(1, g_runtime_context_size));
    if (context_tokens + reserved_tokens < runtime_ctx) {
        return 0;
    }
    const llama_pos available = std::max<llama_pos>(0, context_tokens - n_keep);
    if (available <= 0) {
        return 0;
    }
    const llama_pos overflow = std::max<llama_pos>(0, (context_tokens + reserved_tokens) - runtime_ctx);
    const llama_pos window = std::max<llama_pos>(1, (runtime_ctx - n_keep) / 2);
    return std::min(available, std::max(overflow, window));
}

void erase_token_window(std::vector<llama_token> * tokens, llama_pos n_keep, llama_pos discard) {
    if (tokens == nullptr || discard <= 0) {
        return;
    }
    const size_t keep_index = static_cast<size_t>(std::max<llama_pos>(0, n_keep));
    if (keep_index >= tokens->size()) {
        return;
    }
    const size_t erase_count = std::min(static_cast<size_t>(discard), tokens->size() - keep_index);
    tokens->erase(
        tokens->begin() + static_cast<std::ptrdiff_t>(keep_index),
        tokens->begin() + static_cast<std::ptrdiff_t>(keep_index + erase_count));
}

bool shift_context_window_locked(
    llama_context * context,
    llama_pos * context_tokens,
    std::vector<llama_token> * tracked_tokens,
    llama_pos n_keep,
    llama_pos discard,
    const char * label) {
    if (context == nullptr || context_tokens == nullptr || discard <= 0) {
        return false;
    }
    llama_memory_t memory = llama_get_memory(context);
    if (!llama_memory_can_shift(memory)) {
        __android_log_print(
            ANDROID_LOG_WARN,
            TAG,
            "CONTEXT_SHIFT|label=%s|status=unsupported",
            label);
        return false;
    }
    const llama_pos context_tokens_before = std::max<llama_pos>(0, *context_tokens);
    const llama_pos max_discard = std::max<llama_pos>(0, context_tokens_before - n_keep);
    const llama_pos safe_discard = std::min(discard, max_discard);
    if (safe_discard <= 0) {
        __android_log_print(
            ANDROID_LOG_WARN,
            TAG,
            "CONTEXT_SHIFT|label=%s|status=noop|n_keep=%d|discard=%d|context_tokens=%d",
            label,
            static_cast<int>(n_keep),
            static_cast<int>(discard),
            static_cast<int>(context_tokens_before));
        return false;
    }
    // This runtime uses a single active sequence (seq 0). Using seq_id = -1 for
    // partial-range trims can fail on recurrent / hybrid memory backends.
    if (!llama_memory_seq_rm(memory, 0, n_keep, n_keep + safe_discard)) {
        __android_log_print(
            ANDROID_LOG_WARN,
            TAG,
            "CONTEXT_SHIFT|label=%s|status=seq_rm_failed|n_keep=%d|discard=%d",
            label,
            static_cast<int>(n_keep),
            static_cast<int>(safe_discard));
        return false;
    }
    const llama_pos shift_from = n_keep + safe_discard;
    if (shift_from < context_tokens_before) {
        llama_memory_seq_add(memory, -1, shift_from, -1, -safe_discard);
    }
    erase_token_window(tracked_tokens, n_keep, safe_discard);
    *context_tokens = std::max(n_keep, context_tokens_before - safe_discard);
    g_context_shift_count += 1;
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "CONTEXT_SHIFT|label=%s|n_keep=%d|discard=%d|remaining=%d",
        label,
        static_cast<int>(n_keep),
        static_cast<int>(safe_discard),
        static_cast<int>(*context_tokens));
    return true;
}

void rebuild_sampler_history_locked(
    llama_sampler * sampler,
    const std::vector<llama_token> & tracked_tokens) {
    if (sampler == nullptr) {
        return;
    }
    llama_sampler_reset(sampler);
    for (const llama_token token : tracked_tokens) {
        llama_sampler_accept(sampler, token);
    }
}

bool rebuild_context_from_tokens_locked(
    llama_context * context,
    llama_sampler * sampler,
    llama_pos * context_tokens,
    const std::vector<llama_token> & tracked_tokens,
    const char * label) {
    if (context == nullptr || context_tokens == nullptr) {
        return false;
    }
    llama_memory_clear(llama_get_memory(context), false);
    if (!tracked_tokens.empty() && !decode_tokens_from_offset(context, tracked_tokens, 0)) {
        __android_log_print(
            ANDROID_LOG_WARN,
            TAG,
            "CONTEXT_SHIFT|label=%s|status=rebuild_decode_failed|tokens=%zu",
            label,
            tracked_tokens.size());
        return false;
    }
    rebuild_sampler_history_locked(sampler, tracked_tokens);
    *context_tokens = static_cast<llama_pos>(tracked_tokens.size());
    g_context_rebuild_count += 1;
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "CONTEXT_SHIFT|label=%s|status=rebuild_success|tokens=%zu",
        label,
        tracked_tokens.size());
    return true;
}

bool shift_or_rebuild_context_window_locked(
    llama_context * context,
    llama_sampler * sampler,
    llama_pos * context_tokens,
    std::vector<llama_token> * tracked_tokens,
    llama_pos n_keep,
    llama_pos discard,
    const char * label) {
    if (discard <= 0 || tracked_tokens == nullptr) {
        return false;
    }
    if (shift_context_window_locked(context, context_tokens, tracked_tokens, n_keep, discard, label)) {
        return true;
    }
    erase_token_window(tracked_tokens, n_keep, discard);
    return rebuild_context_from_tokens_locked(context, sampler, context_tokens, *tracked_tokens, label);
}

std::string lowercase_copy(const std::string & value) {
    std::string lower(value.size(), '\0');
    std::transform(value.begin(), value.end(), lower.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return lower;
}

std::string safe_string(const char * value) {
    return value != nullptr ? value : "";
}

int parse_adreno_generation(const std::string & text) {
    const std::string lower = lowercase_copy(text);
    size_t marker = lower.find("adreno");
    while (marker != std::string::npos) {
        size_t cursor = marker + 6;
        while (cursor < lower.size() && !std::isdigit(static_cast<unsigned char>(lower[cursor]))) {
            cursor += 1;
        }
        if (cursor + 2 < lower.size() &&
            std::isdigit(static_cast<unsigned char>(lower[cursor])) &&
            std::isdigit(static_cast<unsigned char>(lower[cursor + 1])) &&
            std::isdigit(static_cast<unsigned char>(lower[cursor + 2]))) {
            return lower[cursor] - '0';
        }
        marker = lower.find("adreno", marker + 6);
    }
    return 0;
}

std::string extract_opencl_version(const std::string & description) {
    const std::string lower = lowercase_copy(description);
    const size_t marker = lower.find("opencl");
    if (marker == std::string::npos) {
        return "";
    }
    size_t start = marker + 6;
    while (start < lower.size() && !std::isdigit(static_cast<unsigned char>(lower[start]))) {
        start += 1;
    }
    if (start >= lower.size()) {
        return "";
    }
    size_t end = start;
    while (end < lower.size()) {
        const unsigned char ch = static_cast<unsigned char>(lower[end]);
        if (!(std::isdigit(ch) || ch == '.')) {
            break;
        }
        end += 1;
    }
    if (end <= start) {
        return "";
    }
    return description.substr(start, end - start);
}

bool contains_quant_token(const std::string & normalized_filename, const std::string & token) {
    size_t pos = normalized_filename.find(token);
    while (pos != std::string::npos) {
        const bool left_ok = pos == 0 ||
            !std::isalnum(static_cast<unsigned char>(normalized_filename[pos - 1]));
        const size_t right_pos = pos + token.size();
        const bool right_ok = right_pos >= normalized_filename.size() ||
            !std::isalnum(static_cast<unsigned char>(normalized_filename[right_pos]));
        if (left_ok && right_ok) {
            return true;
        }
        pos = normalized_filename.find(token, pos + 1);
    }
    return false;
}

// Keys drive attention weights and use at least as much precision as values.
// Returns {type_k, type_v} pair.
struct kv_type_pair { ggml_type type_k; ggml_type type_v; };

kv_type_pair resolve_quantized_kv_types(jint preset_code) {
    switch (preset_code) {
        case 2:  // AGGRESSIVE — keys Q8_0, values Q4_0 (asymmetric)
            return { GGML_TYPE_Q8_0, GGML_TYPE_Q4_0 };
        case 1:  // BALANCED — keys Q8_0, values Q8_0
            return { GGML_TYPE_Q8_0, GGML_TYPE_Q8_0 };
        case 0:  // SAFE
        default:
            return { GGML_TYPE_F16, GGML_TYPE_F16 };
    }
}

llama_flash_attn_type resolve_flash_attn_type(jint code, bool force_disabled) {
    if (force_disabled) {
        return LLAMA_FLASH_ATTN_TYPE_DISABLED;
    }
    switch (code) {
        case 1:
            return LLAMA_FLASH_ATTN_TYPE_ENABLED;
        case 2:
            return LLAMA_FLASH_ATTN_TYPE_DISABLED;
        case 0:
        default:
            return LLAMA_FLASH_ATTN_TYPE_AUTO;
    }
}

std::string extract_quantization_tag_from_path(const std::string & model_path) {
    if (model_path.empty()) {
        return "unknown";
    }
    std::string filename = model_path.substr(model_path.find_last_of("/\\") + 1);
    if (!filename.empty()) {
        const size_t dot = filename.find_last_of('.');
        if (dot != std::string::npos) {
            filename = filename.substr(0, dot);
        }
    }
    const std::string normalized = lowercase_copy(filename);
    static const std::array<const char *, 16> kQuantTokens = {
        "q6_k",
        "q5_k_m",
        "q5_k_s",
        "q4_k_m",
        "q4_k_s",
        "q8_0",
        "q4_1",
        "q4_0",
        "iq4_nl",
        "iq4_xs",
        "iq3_xs",
        "iq3_s",
        "iq2_xs",
        "iq2_s",
        "f16",
        "f32",
    };
    for (const char * token : kQuantTokens) {
        if (contains_quant_token(normalized, token)) {
            return token;
        }
    }
    return "unknown";
}

void apply_android_backend_profile_env() {
    const char * env_profile = std::getenv("POCKETGPT_BACKEND_PROFILE");
    const std::string resolved = lowercase_copy(env_profile != nullptr ? env_profile : g_backend_profile);
    if (resolved == "hexagon" || resolved == "opencl" || resolved == "cpu" || resolved == "auto") {
        g_backend_profile = resolved;
    } else {
        g_backend_profile = "auto";
    }
}

bool file_exists(const char * path) {
    if (path == nullptr || *path == '\0') {
        return false;
    }
    struct stat st {};
    return stat(path, &st) == 0 && S_ISREG(st.st_mode);
}

void apply_android_opencl_icd_hint_env() {
    const char * env_filenames = std::getenv("OCL_ICD_FILENAMES");
    if (env_filenames != nullptr && *env_filenames != '\0') {
        const std::string resolved = env_filenames;
        if (g_opencl_icd_source == "android_vendor_lib" && resolved == g_opencl_icd_filenames) {
            return;
        }
        g_opencl_icd_filenames = resolved;
        g_opencl_icd_source = "environment";
        return;
    }

    static constexpr const char * kAndroidOpenClVendorCandidates[] = {
        "/vendor/lib64/libOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so",
        "/vendor/lib/libOpenCL.so",
        "/system/vendor/lib/libOpenCL.so",
    };

    for (const char * candidate : kAndroidOpenClVendorCandidates) {
        if (!file_exists(candidate)) {
            continue;
        }
        setenv("OCL_ICD_FILENAMES", candidate, 1);
        g_opencl_icd_filenames = candidate;
        g_opencl_icd_source = "android_vendor_lib";
        return;
    }

    g_opencl_icd_filenames.clear();
    g_opencl_icd_source = "not_found";
}

const char * compiled_gpu_backends() {
#if defined(GGML_USE_HEXAGON) && defined(GGML_USE_OPENCL)
    return "hexagon,opencl";
#elif defined(GGML_USE_HEXAGON)
    return "hexagon";
#elif defined(GGML_USE_OPENCL)
    return "opencl";
#else
    return "cpu-only";
#endif
}

std::string json_escape(const std::string & value) {
    std::string escaped;
    escaped.reserve(value.size());
    for (const char ch : value) {
        switch (ch) {
            case '"':
                escaped += "\\\"";
                break;
            case '\\':
                escaped += "\\\\";
                break;
            case '\n':
                escaped += "\\n";
                break;
            case '\r':
                escaped += "\\r";
                break;
            case '\t':
                escaped += "\\t";
                break;
            default:
                escaped.push_back(ch);
                break;
        }
    }
    return escaped;
}

std::string summarize_cache_key(const std::string & cache_key) {
    if (cache_key.empty()) {
        return "empty";
    }
    const size_t preview_len = std::min<size_t>(12, cache_key.size());
    return cache_key.substr(0, preview_len);
}

struct BackendDiagnosticsSnapshot {
    int registered_backend_count = 0;
    int opencl_device_count = 0;
    int hexagon_device_count = 0;
    int gpu_device_count = 0;
    int accel_device_count = 0;
    std::string registered_backends;
    std::string opencl_icd_filenames;
    std::string opencl_icd_source;
    ggml_backend_dev_t opencl_device = nullptr;
    ggml_backend_dev_t hexagon_device = nullptr;
    uint64_t opencl_memory_bytes = 0;
    uint64_t hexagon_memory_bytes = 0;
    std::string opencl_device_name;
    std::string opencl_device_description;
    std::string opencl_device_version;
    int opencl_adreno_generation = 0;
};

struct BackendSelection {
    std::string requested_profile = "auto";
    std::string selected_backend = "cpu";
    ggml_backend_dev_t selected_device = nullptr;
    uint64_t selected_device_memory_bytes = 0;
    bool runtime_supported = false;
    bool auto_fallback_to_cpu = false;
};

void clear_backend_error_locked() {
    g_last_backend_error_code.clear();
    g_last_backend_error_detail.clear();
}

void set_backend_error_locked(const std::string & code, const std::string & detail) {
    g_last_backend_error_code = code;
    g_last_backend_error_detail = detail;
}

std::string backend_label_for_device(ggml_backend_dev_t dev) {
    if (dev == nullptr) {
        return "cpu";
    }
    ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
    const std::string reg_name = lowercase_copy(reg != nullptr && ggml_backend_reg_name(reg) != nullptr
        ? ggml_backend_reg_name(reg)
        : "");
    const std::string dev_name = lowercase_copy(ggml_backend_dev_name(dev) != nullptr
        ? ggml_backend_dev_name(dev)
        : "");
    const std::string dev_desc = lowercase_copy(ggml_backend_dev_description(dev) != nullptr
        ? ggml_backend_dev_description(dev)
        : "");
    if (reg_name.find("hexagon") != std::string::npos ||
        dev_name.find("hexagon") != std::string::npos ||
        dev_desc.find("hexagon") != std::string::npos ||
        ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_ACCEL) {
        return "hexagon";
    }
    if (reg_name.find("opencl") != std::string::npos ||
        dev_name.find("opencl") != std::string::npos ||
        dev_desc.find("opencl") != std::string::npos) {
        return "opencl";
    }
    if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_GPU ||
        ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_IGPU) {
        return "opencl";
    }
    return "unknown";
}

BackendDiagnosticsSnapshot collect_registered_backend_diagnostics() {
    BackendDiagnosticsSnapshot info;
    info.opencl_icd_filenames = g_opencl_icd_filenames;
    info.opencl_icd_source = g_opencl_icd_source;
    std::vector<std::string> backend_names;
    backend_names.reserve(ggml_backend_reg_count());
    for (size_t index = 0; index < ggml_backend_reg_count(); ++index) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(index);
        if (reg == nullptr) {
            continue;
        }
        info.registered_backend_count += 1;
        const std::string name = lowercase_copy(safe_string(ggml_backend_reg_name(reg)));
        backend_names.push_back(name.empty() ? "unknown" : name);
    }
    std::ostringstream out;
    for (size_t index = 0; index < backend_names.size(); ++index) {
        if (index > 0) {
            out << ",";
        }
        out << backend_names[index];
    }
    info.registered_backends = out.str();
    return info;
}

BackendDiagnosticsSnapshot collect_backend_diagnostics() {
    BackendDiagnosticsSnapshot info = collect_registered_backend_diagnostics();
    for (size_t index = 0; index < ggml_backend_dev_count(); ++index) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(index);
        if (dev == nullptr) {
            continue;
        }
        const auto type = ggml_backend_dev_type(dev);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            info.gpu_device_count += 1;
        } else if (type == GGML_BACKEND_DEVICE_TYPE_ACCEL) {
            info.accel_device_count += 1;
        }
        size_t free_bytes = 0;
        size_t total_bytes = 0;
        ggml_backend_dev_memory(dev, &free_bytes, &total_bytes);
        (void) free_bytes;
        const uint64_t memory_bytes = static_cast<uint64_t>(total_bytes);
        const std::string label = backend_label_for_device(dev);
        if (label == "hexagon") {
            info.hexagon_device_count += 1;
            if (info.hexagon_device == nullptr || memory_bytes > info.hexagon_memory_bytes) {
                info.hexagon_device = dev;
                info.hexagon_memory_bytes = memory_bytes;
            }
        } else if (label == "opencl") {
            info.opencl_device_count += 1;
            if (info.opencl_device == nullptr || memory_bytes > info.opencl_memory_bytes) {
                info.opencl_device = dev;
                info.opencl_memory_bytes = memory_bytes;
                info.opencl_device_name = safe_string(ggml_backend_dev_name(dev));
                info.opencl_device_description = safe_string(ggml_backend_dev_description(dev));
                info.opencl_device_version = extract_opencl_version(info.opencl_device_description);
                info.opencl_adreno_generation = parse_adreno_generation(
                    info.opencl_device_name + " " + info.opencl_device_description);
            }
        }
    }
    return info;
}

BackendSelection resolve_backend_selection_locked(bool gpu_requested) {
    apply_android_backend_profile_env();
    const BackendDiagnosticsSnapshot diag = collect_backend_diagnostics();
    BackendSelection selection;
    selection.requested_profile = g_backend_profile;
    selection.runtime_supported = diag.hexagon_device_count > 0 || diag.opencl_device_count > 0;

    if (!gpu_requested || selection.requested_profile == "cpu") {
        return selection;
    }

    if (selection.requested_profile == "hexagon") {
        if (diag.hexagon_device != nullptr) {
            selection.selected_backend = "hexagon";
            selection.selected_device = diag.hexagon_device;
            selection.selected_device_memory_bytes = diag.hexagon_memory_bytes;
        }
        return selection;
    }
    if (selection.requested_profile == "opencl") {
        if (diag.opencl_device != nullptr) {
            selection.selected_backend = "opencl";
            selection.selected_device = diag.opencl_device;
            selection.selected_device_memory_bytes = diag.opencl_memory_bytes;
        }
        return selection;
    }

    if (diag.hexagon_device != nullptr) {
        selection.selected_backend = "hexagon";
        selection.selected_device = diag.hexagon_device;
        selection.selected_device_memory_bytes = diag.hexagon_memory_bytes;
        return selection;
    }
    if (diag.opencl_device != nullptr) {
        selection.selected_backend = "opencl";
        selection.selected_device = diag.opencl_device;
        selection.selected_device_memory_bytes = diag.opencl_memory_bytes;
        return selection;
    }
    selection.auto_fallback_to_cpu = true;
    return selection;
}

bool gpu_offload_supported() {
    const BackendDiagnosticsSnapshot diag = collect_backend_diagnostics();
    return diag.hexagon_device_count > 0 || diag.opencl_device_count > 0;
}

std::string backend_diagnostics_json() {
    apply_android_backend_profile_env();
    const bool runtime_supported = gpu_offload_supported();
    const BackendDiagnosticsSnapshot diag = collect_backend_diagnostics();
    std::ostringstream out;
    const bool strict_accelerator_fail_fast = g_backend_profile == "hexagon" || g_backend_profile == "opencl";
    const bool auto_backend_cpu_fallback = g_backend_profile == "auto";
    const std::string flash_attn_guard_reason = g_last_opencl_flash_guard_applied ? "opencl_backend" : "";
    const std::string quantized_kv_guard_reason = g_last_opencl_quant_kv_guard_applied ? "opencl_backend" : "";
    out << "{"
        << "\"compiled_backend\":\"" << json_escape(compiled_gpu_backends()) << "\","
        << "\"backend_profile\":\"" << json_escape(g_backend_profile) << "\","
        << "\"runtime_supported\":" << (runtime_supported ? "true" : "false") << ","
        << "\"strict_accelerator_fail_fast\":" << (strict_accelerator_fail_fast ? "true" : "false") << ","
        << "\"auto_backend_cpu_fallback\":" << (auto_backend_cpu_fallback ? "true" : "false") << ","
        << "\"active_backend\":\"" << json_escape(g_active_backend) << "\","
        << "\"backend_init_mode\":\"" << json_escape(backend_init_mode_label(g_backend_init_mode)) << "\","
        << "\"registered_backend_count\":" << diag.registered_backend_count << ","
        << "\"registered_backends\":\"" << json_escape(diag.registered_backends) << "\","
        << "\"opencl_icd_filenames\":\"" << json_escape(diag.opencl_icd_filenames) << "\","
        << "\"opencl_icd_source\":\"" << json_escape(diag.opencl_icd_source) << "\","
        << "\"opencl_device_count\":" << diag.opencl_device_count << ","
        << "\"hexagon_device_count\":" << diag.hexagon_device_count << ","
        << "\"gpu_device_count\":" << diag.gpu_device_count << ","
        << "\"accel_device_count\":" << diag.accel_device_count << ","
        << "\"opencl_memory_bytes\":" << static_cast<unsigned long long>(diag.opencl_memory_bytes) << ","
        << "\"hexagon_memory_bytes\":" << static_cast<unsigned long long>(diag.hexagon_memory_bytes) << ","
        << "\"opencl_device_name\":\"" << json_escape(diag.opencl_device_name) << "\","
        << "\"opencl_device_description\":\"" << json_escape(diag.opencl_device_description) << "\","
        << "\"opencl_device_version\":\"" << json_escape(diag.opencl_device_version) << "\","
        << "\"opencl_adreno_generation\":" << diag.opencl_adreno_generation << ","
        << "\"requested_model_quantization\":\"" << json_escape(g_last_model_quantization) << "\","
        << "\"active_model_quantization\":\"" << json_escape(g_active_model_quantization) << "\","
        << "\"model_memory_mode\":\"" << json_escape(model_memory_mode_label()) << "\","
        << "\"prefix_cache_mode\":\"" << json_escape(prefix_cache_mode_label()) << "\","
        << "\"flashAttnActive\":" << (g_last_flash_attn_active ? "true" : "false") << ","
        << "\"kv_cache_mode\":\"" << json_escape(g_last_kv_cache_mode) << "\","
        << "\"kv_cache_fallback_reason\":\"" << json_escape(g_last_kv_cache_fallback_reason) << "\","
        << "\"flash_attn_guard_reason\":\"" << json_escape(flash_attn_guard_reason) << "\","
        << "\"quantized_kv_guard_reason\":\"" << json_escape(quantized_kv_guard_reason) << "\","
        << "\"last_mmap_readahead_label\":\"" << json_escape(g_mmap_telemetry.last_label) << "\","
        << "\"last_mmap_readahead_bytes\":" << g_mmap_telemetry.last_bytes << ","
        << "\"last_mmap_readahead_result\":" << g_mmap_telemetry.last_result << ","
        << "\"last_mmap_readahead_ms\":" << g_mmap_telemetry.last_duration_ms << ","
        << "\"mmap_readahead_count\":" << static_cast<unsigned long long>(g_mmap_telemetry.readahead_count) << ","
        << "\"device_local_heap_bytes\":" << static_cast<unsigned long long>(g_active_backend_memory_bytes) << ","
        << "\"last_error_code\":\"" << json_escape(g_last_backend_error_code) << "\","
        << "\"last_error_detail\":\"" << json_escape(g_last_backend_error_detail) << "\""
        << "}";
    return out.str();
}

int32_t estimate_max_gpu_layers_locked(int32_t requested_n_ctx) {
    if (g_model == nullptr || !gpu_offload_supported()) {
        return -1;
    }
    const int32_t layer_count = llama_model_n_layer(g_model);
    if (layer_count <= 0) {
        return -1;
    }
    const uint64_t model_size = g_model_size_bytes > 0
        ? g_model_size_bytes
        : static_cast<uint64_t>(llama_model_size(g_model));
    if (model_size == 0) {
        return -1;
    }
    uint64_t backend_memory_bytes = g_active_backend_memory_bytes;
    if (backend_memory_bytes == 0) {
        const BackendDiagnosticsSnapshot diag = collect_backend_diagnostics();
        if (g_active_backend == "hexagon") {
            backend_memory_bytes = diag.hexagon_memory_bytes;
        } else if (g_active_backend == "opencl") {
            backend_memory_bytes = diag.opencl_memory_bytes;
        } else {
            backend_memory_bytes = std::max(diag.opencl_memory_bytes, diag.hexagon_memory_bytes);
        }
    }
    if (backend_memory_bytes == 0) {
        const int32_t heuristic = clamp_i32(layer_count / 2, 1, layer_count);
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "GPU_OFFLOAD|estimate_layers=%d|strategy=heuristic_backend_unknown|layer_count=%d",
            static_cast<int>(heuristic),
            static_cast<int>(layer_count));
        return heuristic;
    }
    const int32_t n_ctx = std::max<int32_t>(1, requested_n_ctx > 0 ? requested_n_ctx : g_runtime_context_size);
    const int32_t n_embd = std::max(1, llama_model_n_embd(g_model));
    const int32_t n_head = std::max(1, llama_model_n_head(g_model));
    const int32_t n_head_kv = std::max(1, llama_model_n_head_kv(g_model));
    const double kv_type_bytes = g_last_quantized_kv_cache ? 1.0 : 2.0;
    const double per_layer_model_bytes = static_cast<double>(model_size) / static_cast<double>(layer_count);
    const double kv_ratio = static_cast<double>(n_head_kv) / static_cast<double>(n_head);
    const double per_layer_kv_bytes =
        2.0 * static_cast<double>(n_ctx) * static_cast<double>(n_embd) * kv_ratio * kv_type_bytes;
    const double per_layer_total_bytes = per_layer_model_bytes + per_layer_kv_bytes;
    if (per_layer_total_bytes <= 0.0) {
        return -1;
    }
    const double available_bytes = static_cast<double>(backend_memory_bytes) * 0.75;
    const int32_t estimate = clamp_i32(
        static_cast<int32_t>(std::floor(available_bytes / per_layer_total_bytes)),
        0,
        layer_count);
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "GPU_OFFLOAD|estimate_layers=%d|layer_count=%d|n_ctx=%d|heap_bytes=%llu|model_bytes=%llu|per_layer_bytes=%.0f|kv_per_layer_bytes=%.0f",
        static_cast<int>(estimate),
        static_cast<int>(layer_count),
        static_cast<int>(n_ctx),
        static_cast<unsigned long long>(backend_memory_bytes),
        static_cast<unsigned long long>(model_size),
        per_layer_model_bytes,
        per_layer_kv_bytes);
    return estimate;
}

const char * backend_init_mode_label(BackendInitMode mode) {
    switch (mode) {
        case BackendInitMode::GPU_ENABLED:
            return "gpu-enabled";
        case BackendInitMode::CPU_ONLY:
            return "cpu-only";
        case BackendInitMode::NONE:
        default:
            return "none";
    }
}

const char * model_memory_mode_label() {
    if (g_model_uses_hybrid_memory && g_model_uses_recurrent_memory) {
        return "hybrid_recurrent";
    }
    if (g_model_uses_hybrid_memory) {
        return "hybrid";
    }
    if (g_model_uses_recurrent_memory) {
        return "recurrent";
    }
    return "standard";
}

const char * prefix_cache_mode_label() {
    return g_prefix_cache_prefill_only_mode ? "prefill_only" : "full_sequence";
}

void log_gpu_support_once() {
    static bool logged = false;
    if (logged) {
        return;
    }
    logged = true;
    const BackendDiagnosticsSnapshot diag = collect_backend_diagnostics();
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "GPU_OFFLOAD|compiled_backend=%s|supported=%s|active_backend=%s|registered_backend_count=%d|registered_backends=%s|opencl_icd_source=%s|opencl_icd_filenames=%s|opencl_device_count=%d|hexagon_device_count=%d|"
        "opencl_device_name=%s|opencl_device_version=%s|backend_init_mode=%s|last_error_code=%s|last_error_detail=%s",
        compiled_gpu_backends(),
        (diag.hexagon_device_count > 0 || diag.opencl_device_count > 0) ? "true" : "false",
        g_active_backend.c_str(),
        diag.registered_backend_count,
        diag.registered_backends.empty() ? "none" : diag.registered_backends.c_str(),
        diag.opencl_icd_source.empty() ? "none" : diag.opencl_icd_source.c_str(),
        diag.opencl_icd_filenames.empty() ? "none" : diag.opencl_icd_filenames.c_str(),
        diag.opencl_device_count,
        diag.hexagon_device_count,
        diag.opencl_device_name.empty() ? "none" : diag.opencl_device_name.c_str(),
        diag.opencl_device_version.empty() ? "unknown" : diag.opencl_device_version.c_str(),
        backend_init_mode_label(g_backend_init_mode),
        g_last_backend_error_code.empty() ? "none" : g_last_backend_error_code.c_str(),
        g_last_backend_error_detail.empty() ? "none" : g_last_backend_error_detail.c_str());
}

void log_error(const std::string & message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message.c_str());
}

void llama_android_log_callback(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) {
        return;
    }
    int priority = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR:
            priority = ANDROID_LOG_ERROR;
            break;
        case GGML_LOG_LEVEL_WARN:
            priority = ANDROID_LOG_WARN;
            break;
        case GGML_LOG_LEVEL_INFO:
            priority = ANDROID_LOG_INFO;
            break;
        case GGML_LOG_LEVEL_DEBUG:
        case GGML_LOG_LEVEL_CONT:
        case GGML_LOG_LEVEL_NONE:
        default:
            priority = ANDROID_LOG_DEBUG;
            break;
    }
    __android_log_print(priority, "PocketLlamaCore", "%s", text);
}

struct Utf8PrefixResult {
    size_t valid_prefix_len = 0;
    bool has_invalid = false;
    bool has_incomplete = false;
};

Utf8PrefixResult utf8_valid_prefix(const std::string & text) {
    Utf8PrefixResult result{};
    size_t index = 0;
    while (index < text.size()) {
        const uint8_t lead = static_cast<uint8_t>(text[index]);
        size_t length = 0;
        uint32_t codepoint = 0;
        if ((lead & 0x80u) == 0u) {
            length = 1;
            codepoint = lead;
        } else if ((lead & 0xE0u) == 0xC0u) {
            length = 2;
            codepoint = static_cast<uint32_t>(lead & 0x1Fu);
        } else if ((lead & 0xF0u) == 0xE0u) {
            length = 3;
            codepoint = static_cast<uint32_t>(lead & 0x0Fu);
        } else if ((lead & 0xF8u) == 0xF0u) {
            length = 4;
            codepoint = static_cast<uint32_t>(lead & 0x07u);
        } else {
            result.valid_prefix_len = index;
            result.has_invalid = true;
            return result;
        }

        if (index + length > text.size()) {
            result.valid_prefix_len = index;
            result.has_incomplete = true;
            return result;
        }

        for (size_t offset = 1; offset < length; ++offset) {
            const uint8_t next = static_cast<uint8_t>(text[index + offset]);
            if ((next & 0xC0u) != 0x80u) {
                result.valid_prefix_len = index;
                result.has_invalid = true;
                return result;
            }
            codepoint = (codepoint << 6u) | static_cast<uint32_t>(next & 0x3Fu);
        }

        if ((length == 2 && codepoint < 0x80u) ||
            (length == 3 && (codepoint < 0x800u || (codepoint >= 0xD800u && codepoint <= 0xDFFFu))) ||
            (length == 4 && (codepoint < 0x10000u || codepoint > 0x10FFFFu))) {
            result.valid_prefix_len = index;
            result.has_invalid = true;
            return result;
        }

        index += length;
    }
    result.valid_prefix_len = text.size();
    return result;
}

bool token_to_piece_dynamic(const llama_vocab * vocab, llama_token token, std::string * out_piece) {
    std::vector<char> buffer(256);
    for (int attempt = 0; attempt < 4; ++attempt) {
        const int piece_size = llama_token_to_piece(
            vocab,
            token,
            buffer.data(),
            static_cast<int32_t>(buffer.size()),
            0,
            true);
        if (piece_size >= 0) {
            out_piece->assign(buffer.data(), static_cast<size_t>(piece_size));
            return true;
        }
        const int required = -piece_size;
        if (required <= 0) {
            return false;
        }
        buffer.resize(static_cast<size_t>(required) + 8);
    }
    return false;
}

enum class EmitUtf8Status {
    OK,
    CALLBACK_REJECTED,
};

EmitUtf8Status emit_utf8_buffered(
    const std::string & piece,
    std::string * pending,
    const std::function<bool(const char *, size_t)> & emit_piece) {
    constexpr char UTF8_REPLACEMENT_CHAR[] = "\xEF\xBF\xBD";
    constexpr size_t UTF8_REPLACEMENT_CHAR_LEN = 3;
    pending->append(piece);
    while (!pending->empty()) {
        const Utf8PrefixResult prefix = utf8_valid_prefix(*pending);
        if (prefix.has_invalid) {
            if (prefix.valid_prefix_len > 0) {
                if (!emit_piece(pending->data(), prefix.valid_prefix_len)) {
                    return EmitUtf8Status::CALLBACK_REJECTED;
                }
                pending->erase(0, prefix.valid_prefix_len);
                continue;
            }
            // Some token pieces can contain raw bytes that are not standalone UTF-8.
            // Keep streaming by replacing the invalid lead byte with U+FFFD.
            pending->erase(0, 1);
            if (!emit_piece(UTF8_REPLACEMENT_CHAR, UTF8_REPLACEMENT_CHAR_LEN)) {
                return EmitUtf8Status::CALLBACK_REJECTED;
            }
            __android_log_print(ANDROID_LOG_WARN, TAG, "UTF8_STREAM|invalid_byte_replaced=true");
            continue;
        }
        if (prefix.valid_prefix_len == 0) {
            return EmitUtf8Status::OK;
        }
        if (!emit_piece(pending->data(), prefix.valid_prefix_len)) {
            return EmitUtf8Status::CALLBACK_REJECTED;
        }
        pending->erase(0, prefix.valid_prefix_len);
        if (prefix.has_incomplete) {
            return EmitUtf8Status::OK;
        }
    }
    return EmitUtf8Status::OK;
}

std::string to_std_string(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return "";
    }
    const char * raw = env->GetStringUTFChars(value, nullptr);
    if (raw == nullptr) {
        return "";
    }
    std::string result(raw);
    env->ReleaseStringUTFChars(value, raw);
    return result;
}

void release_runtime_locked() {
    if (g_mtmd_ctx != nullptr) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
    }
    if (g_sampler != nullptr) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    if (g_draft_sampler != nullptr) {
        llama_sampler_free(g_draft_sampler);
        g_draft_sampler = nullptr;
    }
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_draft_context != nullptr) {
        llama_free(g_draft_context);
        g_draft_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    if (g_draft_model != nullptr) {
        llama_model_free(g_draft_model);
        g_draft_model = nullptr;
    }
    clear_prefix_cache_slots_locked();
    g_prompt_decode_batch_size = DEFAULT_PROMPT_DECODE_BATCH_SIZE;
    g_runtime_context_size = DEFAULT_CONTEXT_SIZE;
    g_sampling_temperature = 0.7f;
    g_sampling_top_k = 40;
    g_sampling_top_p = 0.95f;
    g_sampling_min_p = 0.0f;
    g_sampling_typical_p = 1.0f;
    g_sampling_repeat_last_n = 64;
    g_sampling_repeat_penalty = 1.0f;
    g_sampling_frequency_penalty = 0.0f;
    g_sampling_presence_penalty = 0.0f;
    g_sampling_mirostat = 0;
    g_sampling_mirostat_tau = 5.0f;
    g_sampling_mirostat_eta = 0.1f;
    g_sampling_xtc_threshold = 0.1f;
    g_sampling_xtc_probability = 0.0f;
    g_sampling_seed = -1;
    g_runtime_n_keep = 128;
    g_model_use_mmap = true;
    g_model_use_mlock = false;
    g_model_layer_count = 0;
    g_model_size_bytes = 0;
    g_last_flash_attn_requested = false;
    g_last_flash_attn_gpu_ops = false;
    g_last_flash_attn_active = false;
    g_last_quantized_kv_cache = false;
    g_last_kv_cache_mode = "f16";
    g_last_kv_cache_fallback_reason.clear();
    g_last_opencl_flash_guard_applied = false;
    g_last_opencl_quant_kv_guard_applied = false;
    g_last_model_quantization = "unknown";
    g_active_model_quantization = "unknown";
    g_active_backend = "cpu";
    g_active_backend_memory_bytes = 0;
    g_model_uses_recurrent_memory = false;
    g_model_uses_hybrid_memory = false;
    g_prefix_cache_prefill_only_mode = false;
    g_speculative_acceptance_rate = 0.65f;
    g_speculative_enabled = false;
    g_speculative_max_draft_tokens = 6;
    g_speculative_min_draft_tokens = 2;
    g_context_shift_count = 0;
    g_context_rebuild_count = 0;
    g_cancel_requested.store(false, std::memory_order_release);
}

bool ensure_backend_initialized_locked(bool require_gpu_backend) {
    apply_android_backend_profile_env();
    apply_android_opencl_icd_hint_env();
    const BackendInitMode previous_mode = g_backend_init_mode;
    const bool needs_backend_discovery =
        previous_mode == BackendInitMode::NONE ||
        (require_gpu_backend && previous_mode != BackendInitMode::GPU_ENABLED);
    if (g_backend_init_mode == BackendInitMode::NONE) {
        llama_backend_init();
    }
    if (!g_llama_logging_installed) {
        llama_log_set(llama_android_log_callback, nullptr);
        g_llama_logging_installed = true;
    }
    // Backend discovery is expensive and runs under the global runtime lock.
    // Avoid reloading every backend on repeated support/diagnostics checks once
    // GPU-capable discovery has already completed for this process.
    if (needs_backend_discovery) {
        ggml_backend_load_all();
    }
    const BackendInitMode next_mode =
        (require_gpu_backend || previous_mode == BackendInitMode::GPU_ENABLED)
            ? BackendInitMode::GPU_ENABLED
            : BackendInitMode::CPU_ONLY;
    if (!needs_backend_discovery && next_mode == previous_mode) {
        return true;
    }
    g_backend_init_mode = next_mode;
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "GPU_OFFLOAD|backend_init_mode=%s|profile=%s",
        next_mode == BackendInitMode::GPU_ENABLED ? "gpu-enabled" : "cpu-only",
        g_backend_profile.c_str());
    return true;
}

std::vector<llama_token> tokenize_prompt(const llama_vocab * vocab, const std::string & prompt) {
    const int token_count = -llama_tokenize(
        vocab,
        prompt.c_str(),
        static_cast<int32_t>(prompt.size()),
        nullptr,
        0,
        true,
        true);
    if (token_count <= 0) {
        return {};
    }
    std::vector<llama_token> tokens(static_cast<size_t>(token_count));
    const int actual = llama_tokenize(
        vocab,
        prompt.c_str(),
        static_cast<int32_t>(prompt.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        true,
        true);
    if (actual < 0) {
        return {};
    }
    tokens.resize(static_cast<size_t>(actual));
    return tokens;
}

size_t longest_common_prefix(
    const std::vector<llama_token> & lhs,
    const std::vector<llama_token> & rhs) {
    const size_t limit = std::min(lhs.size(), rhs.size());
    size_t count = 0;
    while (count < limit && lhs[count] == rhs[count]) {
        ++count;
    }
    return count;
}

void clear_prefix_cache_slots_locked() {
    for (auto & slot : g_prefix_cache_slots) {
        slot.target_prompt_tokens.clear();
        slot.draft_prompt_tokens.clear();
        slot.target_seq_state.clear();
        slot.draft_seq_state.clear();
        slot.cache_key.clear();
        slot.last_used_epoch = 0;
        slot.valid = false;
    }
    g_active_prefix_cache_slot = -1;
    g_prefix_cache_epoch = 0;
    g_prefix_cache_telemetry = PrefixCacheTelemetry{};
}

int select_prefix_cache_slot_for_store() {
    for (size_t idx = 0; idx < g_prefix_cache_slots.size(); ++idx) {
        if (!g_prefix_cache_slots[idx].valid) {
            return static_cast<int>(idx);
        }
    }
    size_t evict_index = 0;
    for (size_t idx = 1; idx < g_prefix_cache_slots.size(); ++idx) {
        if (g_prefix_cache_slots[idx].last_used_epoch < g_prefix_cache_slots[evict_index].last_used_epoch) {
            evict_index = idx;
        }
    }
    return static_cast<int>(evict_index);
}

int store_prefix_cache_slot(
    const std::vector<llama_token> & target_prompt_tokens,
    const std::vector<llama_token> * draft_prompt_tokens,
    const std::string & cache_key) {
    int slot_index = g_active_prefix_cache_slot;
    const bool active_slot_valid =
        slot_index >= 0 &&
        slot_index < static_cast<int>(g_prefix_cache_slots.size()) &&
        g_prefix_cache_slots[static_cast<size_t>(slot_index)].valid;
    if (!active_slot_valid) {
        slot_index = select_prefix_cache_slot_for_store();
    } else {
        const auto & active_slot = g_prefix_cache_slots[static_cast<size_t>(slot_index)];
        const bool preserve_previous_slot = !cache_key.empty() && active_slot.cache_key != cache_key;
        if (preserve_previous_slot) {
            slot_index = select_prefix_cache_slot_for_store();
            if (slot_index == g_active_prefix_cache_slot && g_prefix_cache_slots.size() > 1) {
                slot_index = (slot_index + 1) % static_cast<int>(g_prefix_cache_slots.size());
            }
        }
    }
    auto & slot = g_prefix_cache_slots[static_cast<size_t>(slot_index)];
    slot.target_prompt_tokens = target_prompt_tokens;
    if (draft_prompt_tokens != nullptr) {
        slot.draft_prompt_tokens = *draft_prompt_tokens;
    } else {
        slot.draft_prompt_tokens.clear();
        slot.draft_seq_state.clear();
    }
    slot.cache_key = cache_key;
    slot.valid = true;
    slot.last_used_epoch = ++g_prefix_cache_epoch;
    g_active_prefix_cache_slot = slot_index;
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "PREFIX_CACHE|stage=store_slot|slot=%d|cache_key_len=%zu|cache_key_prefix=%s|target_tokens=%zu",
        slot_index,
        cache_key.size(),
        summarize_cache_key(cache_key).c_str(),
        target_prompt_tokens.size());
    return slot_index;
}

int reuse_prefix_cache_slot(
    int cache_policy,
    const std::string & cache_key,
    const std::vector<llama_token> & prompt_tokens,
    bool * strict_key_match,
    size_t * reused_tokens) {
    *strict_key_match = false;
    *reused_tokens = 0;
    if (cache_policy != CACHE_POLICY_PREFIX_REUSE && cache_policy != CACHE_POLICY_PREFIX_REUSE_STRICT) {
        return -1;
    }

    const bool strict_mode = cache_policy == CACHE_POLICY_PREFIX_REUSE_STRICT;
    int best_slot = -1;
    size_t best_reused_tokens = 0;
    bool best_slot_strict_match = false;

    for (size_t idx = 0; idx < g_prefix_cache_slots.size(); ++idx) {
        auto & slot = g_prefix_cache_slots[idx];
        if (!slot.valid || slot.target_prompt_tokens.empty()) {
            continue;
        }
        const bool key_matches = !cache_key.empty() && cache_key == slot.cache_key;
        if (strict_mode && !key_matches) {
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "PREFIX_CACHE|stage=reuse_candidate|slot=%zu|strict_mode=true|key_match=false|candidate_reuse=0|stored_tokens=%zu|prompt_tokens=%zu|requested_key_len=%zu|requested_key_prefix=%s|stored_key_len=%zu|stored_key_prefix=%s",
                idx,
                slot.target_prompt_tokens.size(),
                prompt_tokens.size(),
                cache_key.size(),
                summarize_cache_key(cache_key).c_str(),
                slot.cache_key.size(),
                summarize_cache_key(slot.cache_key).c_str());
            continue;
        }
        const size_t candidate_reuse = longest_common_prefix(slot.target_prompt_tokens, prompt_tokens);
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "PREFIX_CACHE|stage=reuse_candidate|slot=%zu|strict_mode=%s|key_match=%s|candidate_reuse=%zu|stored_tokens=%zu|prompt_tokens=%zu|requested_key_len=%zu|requested_key_prefix=%s|stored_key_len=%zu|stored_key_prefix=%s",
            idx,
            strict_mode ? "true" : "false",
            key_matches ? "true" : "false",
            candidate_reuse,
            slot.target_prompt_tokens.size(),
            prompt_tokens.size(),
            cache_key.size(),
            summarize_cache_key(cache_key).c_str(),
            slot.cache_key.size(),
            summarize_cache_key(slot.cache_key).c_str());
        if (candidate_reuse == 0) {
            continue;
        }
        const bool prefer_candidate =
            best_slot < 0 ||
            (key_matches && !best_slot_strict_match) ||
            (key_matches == best_slot_strict_match && candidate_reuse > best_reused_tokens) ||
            (key_matches == best_slot_strict_match && candidate_reuse == best_reused_tokens &&
                slot.last_used_epoch > g_prefix_cache_slots[static_cast<size_t>(best_slot)].last_used_epoch);
        if (!prefer_candidate) {
            continue;
        }
        best_slot = static_cast<int>(idx);
        best_reused_tokens = candidate_reuse;
        best_slot_strict_match = key_matches;
    }

    if (best_slot < 0) {
        return -1;
    }

    auto & slot = g_prefix_cache_slots[static_cast<size_t>(best_slot)];
    slot.last_used_epoch = ++g_prefix_cache_epoch;
    *strict_key_match = best_slot_strict_match;
    *reused_tokens = best_reused_tokens;
    return best_slot;
}

void log_prefix_cache_state_event(
    const char * stage,
    const char * label,
    int slot_index,
    size_t bytes,
    bool success,
    const char * reason) {
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "PREFIX_CACHE|stage=%s|label=%s|slot=%d|bytes=%zu|success=%s|reason=%s",
        stage,
        label,
        slot_index,
        bytes,
        success ? "true" : "false",
        reason == nullptr ? "none" : reason);
    const std::string stage_name = stage == nullptr ? "" : stage;
    const std::string reason_name = reason == nullptr ? "none" : reason;
    g_prefix_cache_telemetry.last_slot = slot_index;
    if (stage_name == "store_state") {
        g_prefix_cache_telemetry.last_store_reason = reason_name;
        if (success) {
            g_prefix_cache_telemetry.store_state_success += 1;
        } else {
            g_prefix_cache_telemetry.store_state_failure += 1;
            if (reason_name == "over_budget") {
                g_prefix_cache_telemetry.store_state_over_budget += 1;
            }
        }
    } else if (stage_name == "restore_state") {
        g_prefix_cache_telemetry.last_restore_reason = reason_name;
        if (success) {
            g_prefix_cache_telemetry.restore_state_success += 1;
        } else {
            g_prefix_cache_telemetry.restore_state_failure += 1;
            if (reason_name == "over_budget") {
                g_prefix_cache_telemetry.restore_state_over_budget += 1;
            }
        }
    }
}

std::string prefix_cache_diagnostics_line_locked() {
    size_t resident_slots = 0;
    for (const auto & slot : g_prefix_cache_slots) {
        if (slot.valid) {
            resident_slots += 1;
        }
    }
    const uint64_t total_prefix_decisions = g_prefix_cache_telemetry.target_hits + g_prefix_cache_telemetry.target_misses;
    const double prefix_cache_hit_rate = total_prefix_decisions > 0
        ? static_cast<double>(g_prefix_cache_telemetry.target_hits) / static_cast<double>(total_prefix_decisions)
        : 0.0;
    std::ostringstream out;
    out << "PREFIX_CACHE_DIAG"
        << "|target_hits=" << g_prefix_cache_telemetry.target_hits
        << "|target_misses=" << g_prefix_cache_telemetry.target_misses
        << "|draft_hits=" << g_prefix_cache_telemetry.draft_hits
        << "|draft_misses=" << g_prefix_cache_telemetry.draft_misses
        << "|store_state_success=" << g_prefix_cache_telemetry.store_state_success
        << "|store_state_failure=" << g_prefix_cache_telemetry.store_state_failure
        << "|store_state_over_budget=" << g_prefix_cache_telemetry.store_state_over_budget
        << "|restore_state_success=" << g_prefix_cache_telemetry.restore_state_success
        << "|restore_state_failure=" << g_prefix_cache_telemetry.restore_state_failure
        << "|restore_state_over_budget=" << g_prefix_cache_telemetry.restore_state_over_budget
        << "|last_stage=" << (g_prefix_cache_telemetry.last_stage.empty() ? "none" : g_prefix_cache_telemetry.last_stage)
        << "|last_store_reason=" << (g_prefix_cache_telemetry.last_store_reason.empty() ? "none" : g_prefix_cache_telemetry.last_store_reason)
        << "|last_restore_reason=" << (g_prefix_cache_telemetry.last_restore_reason.empty() ? "none" : g_prefix_cache_telemetry.last_restore_reason)
        << "|last_slot=" << g_prefix_cache_telemetry.last_slot
        << "|last_reused_tokens=" << g_prefix_cache_telemetry.last_reused_tokens
        << "|last_cache_hit=" << (g_prefix_cache_telemetry.last_cache_hit ? "true" : "false")
        << "|prefix_cache_hit_rate=" << prefix_cache_hit_rate
        << "|context_shift_count=" << g_context_shift_count
        << "|context_rebuild_count=" << g_context_rebuild_count
        << "|active_slot=" << g_active_prefix_cache_slot
        << "|resident_slots=" << resident_slots;
    return out.str();
}

bool capture_seq_state(
    llama_context * ctx,
    std::vector<uint8_t> * state,
    size_t max_bytes,
    const char * label,
    int slot_index) {
    if (state == nullptr) {
        return false;
    }
    state->clear();
    if (ctx == nullptr) {
        log_prefix_cache_state_event("store_state", label, slot_index, 0, false, "context_null");
        return false;
    }
    const size_t state_size = g_prefix_cache_prefill_only_mode
        ? llama_state_seq_get_size_ext(ctx, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY)
        : llama_state_seq_get_size(ctx, 0);
    if (state_size == 0) {
        log_prefix_cache_state_event("store_state", label, slot_index, 0, false, "empty");
        return false;
    }
    if (state_size > max_bytes) {
        log_prefix_cache_state_event("store_state", label, slot_index, state_size, false, "over_budget");
        return false;
    }
    state->resize(state_size);
    const size_t written = g_prefix_cache_prefill_only_mode
        ? llama_state_seq_get_data_ext(ctx, state->data(), state->size(), 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY)
        : llama_state_seq_get_data(ctx, state->data(), state->size(), 0);
    if (written != state_size) {
        state->clear();
        log_prefix_cache_state_event("store_state", label, slot_index, written, false, "copy_failed");
        return false;
    }
    log_prefix_cache_state_event("store_state", label, slot_index, written, true, "stored");
    return true;
}

bool restore_seq_state(
    llama_context * ctx,
    const std::vector<uint8_t> & state,
    const char * label,
    int slot_index) {
    if (ctx == nullptr) {
        log_prefix_cache_state_event("restore_state", label, slot_index, 0, false, "context_null");
        return false;
    }
    if (state.empty()) {
        log_prefix_cache_state_event("restore_state", label, slot_index, 0, false, "empty");
        return false;
    }
    llama_memory_clear(llama_get_memory(ctx), false);
    const size_t restored = g_prefix_cache_prefill_only_mode
        ? llama_state_seq_set_data_ext(ctx, state.data(), state.size(), 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY)
        : llama_state_seq_set_data(ctx, state.data(), state.size(), 0);
    if (restored == 0) {
        log_prefix_cache_state_event("restore_state", label, slot_index, state.size(), false, "copy_failed");
        return false;
    }
    log_prefix_cache_state_event("restore_state", label, slot_index, restored, true, "restored");
    return true;
}

// -- Disk-backed session cache --
// File format (little-endian):
//   u32  magic  (0x50475343 = "PGSC")
//   u32  version (1)
//   u32  token_count
//   token_count * i32  tokens
//   u32  cache_key_len
//   cache_key_len bytes  cache_key (UTF-8, no NUL)
//   u64  state_size
//   state_size bytes  state data

constexpr uint32_t SESSION_CACHE_MAGIC   = 0x50475343u;
constexpr uint32_t SESSION_CACHE_VERSION = 1u;

template <typename T>
bool write_val(std::ofstream & out, T val) {
    out.write(reinterpret_cast<const char *>(&val), sizeof(val));
    return out.good();
}

template <typename T>
bool read_val(std::ifstream & in, T & val) {
    in.read(reinterpret_cast<char *>(&val), sizeof(val));
    return in.good();
}

bool save_prefix_cache_slot_to_disk(
    const PrefixCacheSlot & slot,
    const std::string & file_path) {
    if (!slot.valid || slot.target_seq_state.empty()) {
        return false;
    }
    std::ofstream out(file_path, std::ios::binary | std::ios::trunc);
    if (!out) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "SESSION_CACHE|save_open_failed|path=%s", file_path.c_str());
        return false;
    }
    const auto token_count = static_cast<uint32_t>(slot.target_prompt_tokens.size());
    const auto key_len = static_cast<uint32_t>(slot.cache_key.size());
    const auto state_size = static_cast<uint64_t>(slot.target_seq_state.size());
    if (!write_val(out, SESSION_CACHE_MAGIC)) return false;
    if (!write_val(out, SESSION_CACHE_VERSION)) return false;
    if (!write_val(out, token_count)) return false;
    if (token_count > 0) {
        out.write(reinterpret_cast<const char *>(slot.target_prompt_tokens.data()),
                  static_cast<std::streamsize>(token_count * sizeof(llama_token)));
        if (!out.good()) return false;
    }
    if (!write_val(out, key_len)) return false;
    if (key_len > 0) {
        out.write(slot.cache_key.data(), key_len);
        if (!out.good()) return false;
    }
    if (!write_val(out, state_size)) return false;
    out.write(reinterpret_cast<const char *>(slot.target_seq_state.data()),
              static_cast<std::streamsize>(state_size));
    const bool ok = out.good();
    out.close();
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "SESSION_CACHE|saved|tokens=%u|state_bytes=%llu|key=%s|ok=%s",
        token_count, (unsigned long long)state_size, slot.cache_key.c_str(), ok ? "true" : "false");
    return ok;
}

bool load_prefix_cache_slot_from_disk(
    const std::string & file_path,
    PrefixCacheSlot & slot) {
    slot.valid = false;
    slot.target_prompt_tokens.clear();
    slot.target_seq_state.clear();
    slot.cache_key.clear();
    std::ifstream in(file_path, std::ios::binary);
    if (!in) {
        return false;
    }
    uint32_t magic = 0, version = 0, token_count = 0, key_len = 0;
    uint64_t state_size = 0;
    if (!read_val(in, magic) || magic != SESSION_CACHE_MAGIC) return false;
    if (!read_val(in, version) || version != SESSION_CACHE_VERSION) return false;
    if (!read_val(in, token_count)) return false;
    if (token_count > 0) {
        slot.target_prompt_tokens.resize(token_count);
        in.read(reinterpret_cast<char *>(slot.target_prompt_tokens.data()),
                static_cast<std::streamsize>(token_count * sizeof(llama_token)));
        if (!in.good()) return false;
    }
    if (!read_val(in, key_len)) return false;
    if (key_len > 0) {
        std::string key(key_len, '\0');
        in.read(&key[0], key_len);
        if (!in.good()) return false;
        slot.cache_key = std::move(key);
    }
    if (!read_val(in, state_size)) return false;
    if (state_size > MAX_TARGET_PREFIX_STATE_BYTES) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "SESSION_CACHE|load_over_budget|state_bytes=%llu|max=%zu",
            (unsigned long long)state_size, MAX_TARGET_PREFIX_STATE_BYTES);
        return false;
    }
    slot.target_seq_state.resize(static_cast<size_t>(state_size));
    in.read(reinterpret_cast<char *>(slot.target_seq_state.data()),
            static_cast<std::streamsize>(state_size));
    if (!in.good()) return false;
    slot.valid = true;
    slot.last_used_epoch = 0;
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "SESSION_CACHE|loaded|tokens=%u|state_bytes=%llu|key=%s",
        token_count, (unsigned long long)state_size, slot.cache_key.c_str());
    return true;
}

bool decode_tokens_from_offset(
    llama_context * ctx,
    const std::vector<llama_token> & tokens,
    size_t offset) {
    if (offset >= tokens.size()) {
        return true;
    }
    const int32_t prompt_decode_batch_size = std::max(1, g_prompt_decode_batch_size);
    const size_t total_tokens = tokens.size();
    for (size_t idx = offset; idx < tokens.size(); idx += static_cast<size_t>(prompt_decode_batch_size)) {
        if (g_cancel_requested.load(std::memory_order_acquire)) {
            log_error("nativeGenerate cancelled during prompt decode");
            return false;
        }
        const size_t remaining = tokens.size() - idx;
        const int32_t chunk_size = static_cast<int32_t>(
            std::min(remaining, static_cast<size_t>(prompt_decode_batch_size)));
        const size_t completed = std::min(total_tokens, idx + static_cast<size_t>(chunk_size));
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "PROMPT_DECODE|progress=%zu/%zu|offset=%zu|chunk=%d",
            completed,
            total_tokens,
            idx,
            static_cast<int>(chunk_size));
        llama_batch prompt_batch = llama_batch_get_one(const_cast<llama_token *>(tokens.data() + idx), chunk_size);
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "GPU_PROBE_DIAG|stage=prompt_decode_before|idx=%zu|chunk=%d",
            idx,
            static_cast<int>(chunk_size));
        if (llama_decode(ctx, prompt_batch) != 0) {
            return false;
        }
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "GPU_PROBE_DIAG|stage=prompt_decode_after|idx=%zu|chunk=%d",
            idx,
            static_cast<int>(chunk_size));
    }
    return true;
}

uint32_t resolve_sampling_seed(int32_t seed) {
    return seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed);
}

llama_sampler * build_sampler_chain(
    float temperature,
    int32_t top_k,
    float top_p,
    float min_p,
    float typical_p,
    int32_t repeat_last_n,
    float repeat_penalty,
    float frequency_penalty,
    float presence_penalty,
    int32_t mirostat,
    float mirostat_tau,
    float mirostat_eta,
    float xtc_threshold,
    float xtc_probability,
    int32_t seed) {
    const auto sampler_params = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
    if (sampler == nullptr) {
        return nullptr;
    }
    const uint32_t resolved_seed = resolve_sampling_seed(seed);
    const bool penalties_active =
        repeat_last_n != 0 ||
        repeat_penalty != 1.0f ||
        frequency_penalty != 0.0f ||
        presence_penalty != 0.0f;
    if (penalties_active) {
        llama_sampler_chain_add(
            sampler,
            llama_sampler_init_penalties(
                repeat_last_n,
                repeat_penalty,
                frequency_penalty,
                presence_penalty));
    }
    const llama_vocab * vocab = g_model != nullptr ? llama_model_get_vocab(g_model) : nullptr;
    const int32_t vocab_size = vocab != nullptr ? llama_vocab_n_tokens(vocab) : 0;
    if (mirostat == 1 && vocab_size > 0) {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(std::max(0.001f, temperature)));
        llama_sampler_chain_add(
            sampler,
            llama_sampler_init_mirostat(
                vocab_size,
                resolved_seed,
                mirostat_tau,
                mirostat_eta,
                100));
        return sampler;
    }
    if (mirostat == 2) {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(std::max(0.001f, temperature)));
        llama_sampler_chain_add(
            sampler,
            llama_sampler_init_mirostat_v2(
                resolved_seed,
                mirostat_tau,
                mirostat_eta));
        return sampler;
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
        return sampler;
    }
    if (top_k > 0) {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
    }
    if (typical_p > 0.0f && typical_p < 1.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_typical(typical_p, 1));
    }
    if (top_p > 0.0f && top_p < 1.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
    }
    if (min_p > 0.0f && min_p < 1.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_min_p(min_p, 1));
    }
    if (xtc_probability > 0.0f) {
        llama_sampler_chain_add(
            sampler,
            llama_sampler_init_xtc(
                xtc_probability,
                xtc_threshold,
                1,
                resolved_seed));
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(resolved_seed));
    return sampler;
}

bool apply_sampling_config_locked(
    float temperature,
    int32_t top_k,
    float top_p,
    float min_p,
    float typical_p,
    int32_t repeat_last_n,
    float repeat_penalty,
    float frequency_penalty,
    float presence_penalty,
    int32_t mirostat,
    float mirostat_tau,
    float mirostat_eta,
    float xtc_threshold,
    float xtc_probability,
    int32_t seed) {
    g_sampling_temperature = std::max(0.0f, temperature);
    g_sampling_top_k = clamp_i32(top_k, 0, 1024);
    g_sampling_top_p = std::max(0.0f, std::min(1.0f, top_p));
    g_sampling_min_p = std::max(0.0f, std::min(1.0f, min_p));
    g_sampling_typical_p = std::max(0.0f, std::min(1.0f, typical_p));
    g_sampling_repeat_last_n = repeat_last_n < -1 ? -1 : repeat_last_n;
    g_sampling_repeat_penalty = std::max(0.0f, repeat_penalty);
    g_sampling_frequency_penalty = frequency_penalty;
    g_sampling_presence_penalty = presence_penalty;
    g_sampling_mirostat = clamp_i32(mirostat, 0, 2);
    g_sampling_mirostat_tau = std::max(0.0f, mirostat_tau);
    g_sampling_mirostat_eta = std::max(0.0f, mirostat_eta);
    g_sampling_xtc_threshold = std::max(0.0f, std::min(1.0f, xtc_threshold));
    g_sampling_xtc_probability = std::max(0.0f, std::min(1.0f, xtc_probability));
    g_sampling_seed = seed;
    if (g_context == nullptr) {
        return true;
    }

    llama_sampler * next_sampler = build_sampler_chain(
        g_sampling_temperature,
        g_sampling_top_k,
        g_sampling_top_p,
        g_sampling_min_p,
        g_sampling_typical_p,
        g_sampling_repeat_last_n,
        g_sampling_repeat_penalty,
        g_sampling_frequency_penalty,
        g_sampling_presence_penalty,
        g_sampling_mirostat,
        g_sampling_mirostat_tau,
        g_sampling_mirostat_eta,
        g_sampling_xtc_threshold,
        g_sampling_xtc_probability,
        g_sampling_seed);
    if (next_sampler == nullptr) {
        return false;
    }

    llama_sampler * next_draft_sampler = nullptr;
    if (g_draft_context != nullptr) {
        next_draft_sampler = build_sampler_chain(
            g_sampling_temperature,
            std::max(1, g_sampling_top_k / 2),
            std::min(0.98f, std::max(0.5f, g_sampling_top_p)),
            g_sampling_min_p,
            g_sampling_typical_p,
            g_sampling_repeat_last_n,
            g_sampling_repeat_penalty,
            g_sampling_frequency_penalty,
            g_sampling_presence_penalty,
            g_sampling_mirostat,
            g_sampling_mirostat_tau,
            g_sampling_mirostat_eta,
            g_sampling_xtc_threshold,
            g_sampling_xtc_probability,
            g_sampling_seed);
        if (next_draft_sampler == nullptr) {
            llama_sampler_free(next_sampler);
            return false;
        }
    }

    if (g_sampler != nullptr) {
        llama_sampler_free(g_sampler);
    }
    g_sampler = next_sampler;
    llama_sampler_reset(g_sampler);

    if (g_draft_sampler != nullptr) {
        llama_sampler_free(g_draft_sampler);
    }
    g_draft_sampler = next_draft_sampler;
    if (g_draft_sampler != nullptr) {
        llama_sampler_reset(g_draft_sampler);
    }
    return true;
}

bool decode_single_token(llama_context * ctx, llama_token token) {
    llama_token next_token = token;
    llama_batch batch = llama_batch_get_one(&next_token, 1);
    return llama_decode(ctx, batch) == 0;
}

bool speculative_vocabs_compatible(const llama_model * target_model, const llama_model * draft_model) {
    const llama_vocab * target_vocab = llama_model_get_vocab(target_model);
    const llama_vocab * draft_vocab = llama_model_get_vocab(draft_model);
    if (target_vocab == nullptr || draft_vocab == nullptr) {
        return false;
    }
    if (llama_vocab_type(target_vocab) != llama_vocab_type(draft_vocab)) {
        return false;
    }
    if (llama_vocab_get_add_bos(target_vocab) != llama_vocab_get_add_bos(draft_vocab) ||
        llama_vocab_get_add_eos(target_vocab) != llama_vocab_get_add_eos(draft_vocab) ||
        llama_vocab_bos(target_vocab) != llama_vocab_bos(draft_vocab) ||
        llama_vocab_eos(target_vocab) != llama_vocab_eos(draft_vocab)) {
        return false;
    }
    const int target_n_vocab = llama_vocab_n_tokens(target_vocab);
    const int draft_n_vocab = llama_vocab_n_tokens(draft_vocab);
    if (std::abs(target_n_vocab - draft_n_vocab) > 128) {
        return false;
    }
    const int sample_vocab = std::min(target_n_vocab, draft_n_vocab);
    const int max_checks = std::min(sample_vocab, 2048);
    for (int id = 5; id < max_checks; ++id) {
        const char * target_piece = llama_vocab_get_text(target_vocab, id);
        const char * draft_piece = llama_vocab_get_text(draft_vocab, id);
        if (target_piece == nullptr || draft_piece == nullptr) {
            return false;
        }
        if (std::string(target_piece) != std::string(draft_piece)) {
            return false;
        }
    }
    return true;
}

struct SpeculativeStepResult {
    jint status = STREAM_STATUS_COMPLETED;
    bool reached_eog = false;
    std::vector<llama_token> emitted_tokens;
};

SpeculativeStepResult run_speculative_step(
    const llama_vocab * vocab,
    int remaining_tokens,
    llama_pos * target_context_tokens,
    llama_pos * draft_context_tokens) {
    SpeculativeStepResult result{};
    result.status = STREAM_STATUS_COMPLETED;
    if (remaining_tokens <= 0) {
        return result;
    }
    if (g_context == nullptr || g_sampler == nullptr || g_draft_context == nullptr || g_draft_sampler == nullptr) {
        result.status = STREAM_STATUS_RUNTIME_ERROR;
        return result;
    }

    const int max_draft = resolve_speculative_max_draft(remaining_tokens);
    if (max_draft < g_speculative_min_draft_tokens) {
        const llama_token token = llama_sampler_sample(g_sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            result.reached_eog = true;
            return result;
        }
        llama_sampler_accept(g_sampler, token);
        if (!decode_single_token(g_context, token)) {
            result.status = STREAM_STATUS_RUNTIME_ERROR;
            return result;
        }
        *target_context_tokens += 1;
        if (!decode_single_token(g_draft_context, token)) {
            result.status = STREAM_STATUS_RUNTIME_ERROR;
            return result;
        }
        *draft_context_tokens += 1;
        result.emitted_tokens.push_back(token);
        log_speculative_metrics(0, 0, max_draft, remaining_tokens);
        return result;
    }

    const llama_pos base_target_tokens = *target_context_tokens;
    const llama_pos base_draft_tokens = *draft_context_tokens;

    std::vector<llama_token> draft_tokens;
    draft_tokens.reserve(static_cast<size_t>(max_draft));
    for (int i = 0; i < max_draft; ++i) {
        if (g_cancel_requested.load(std::memory_order_acquire)) {
            result.status = STREAM_STATUS_CANCELLED;
            return result;
        }
        const llama_token draft_token = llama_sampler_sample(g_draft_sampler, g_draft_context, -1);
        if (llama_vocab_is_eog(vocab, draft_token)) {
            break;
        }
        llama_sampler_accept(g_draft_sampler, draft_token);
        draft_tokens.push_back(draft_token);
        if (!decode_single_token(g_draft_context, draft_token)) {
            result.status = STREAM_STATUS_RUNTIME_ERROR;
            return result;
        }
        *draft_context_tokens += 1;
    }

    if (static_cast<int>(draft_tokens.size()) < g_speculative_min_draft_tokens) {
        llama_memory_seq_rm(llama_get_memory(g_draft_context), 0, base_draft_tokens, -1);
        *draft_context_tokens = base_draft_tokens;
        llama_sampler_reset(g_draft_sampler);
        const llama_token token = llama_sampler_sample(g_sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            result.reached_eog = true;
            return result;
        }
        llama_sampler_accept(g_sampler, token);
        if (!decode_single_token(g_context, token)) {
            result.status = STREAM_STATUS_RUNTIME_ERROR;
            return result;
        }
        *target_context_tokens += 1;
        if (!decode_single_token(g_draft_context, token)) {
            result.status = STREAM_STATUS_RUNTIME_ERROR;
            return result;
        }
        *draft_context_tokens += 1;
        result.emitted_tokens.push_back(token);
        log_speculative_metrics(0, draft_tokens.size(), max_draft, remaining_tokens);
        return result;
    }

    const llama_token first_token = llama_sampler_sample(g_sampler, g_context, -1);
    if (llama_vocab_is_eog(vocab, first_token)) {
        result.reached_eog = true;
        return result;
    }
    llama_sampler_accept(g_sampler, first_token);

    if (first_token != draft_tokens[0]) {
        llama_memory_seq_rm(llama_get_memory(g_draft_context), 0, base_draft_tokens, -1);
        *draft_context_tokens = base_draft_tokens;
        llama_sampler_reset(g_draft_sampler);
        if (!decode_single_token(g_context, first_token)) {
            result.status = STREAM_STATUS_RUNTIME_ERROR;
            return result;
        }
        *target_context_tokens += 1;
        if (!decode_single_token(g_draft_context, first_token)) {
            result.status = STREAM_STATUS_RUNTIME_ERROR;
            return result;
        }
        *draft_context_tokens += 1;
        result.emitted_tokens.push_back(first_token);
        log_speculative_metrics(0, draft_tokens.size(), max_draft, remaining_tokens);
        return result;
    }

    llama_batch target_batch = llama_batch_get_one(draft_tokens.data(), static_cast<int32_t>(draft_tokens.size()));
    if (llama_decode(g_context, target_batch) != 0) {
        result.status = STREAM_STATUS_RUNTIME_ERROR;
        return result;
    }
    *target_context_tokens += static_cast<llama_pos>(draft_tokens.size());

    size_t accepted_drafts = 1;
    llama_token next_token = 0;
    bool mismatch = false;
    for (size_t i = 1; i < draft_tokens.size(); ++i) {
        const llama_token sampled = llama_sampler_sample(g_sampler, g_context, static_cast<int32_t>(i - 1));
        if (llama_vocab_is_eog(vocab, sampled)) {
            result.reached_eog = true;
            next_token = sampled;
            mismatch = true;
            break;
        }
        llama_sampler_accept(g_sampler, sampled);
        if (sampled != draft_tokens[i]) {
            next_token = sampled;
            mismatch = true;
            break;
        }
        accepted_drafts += 1;
    }

    if (!mismatch) {
        next_token = llama_sampler_sample(
            g_sampler,
            g_context,
            static_cast<int32_t>(draft_tokens.size() - 1));
        if (llama_vocab_is_eog(vocab, next_token)) {
            result.reached_eog = true;
        } else {
            llama_sampler_accept(g_sampler, next_token);
        }
    }

    const llama_pos keep_target_tokens = base_target_tokens + static_cast<llama_pos>(accepted_drafts);
    llama_memory_seq_rm(llama_get_memory(g_context), 0, keep_target_tokens, -1);
    *target_context_tokens = keep_target_tokens;

    result.emitted_tokens.insert(
        result.emitted_tokens.end(),
        draft_tokens.begin(),
        draft_tokens.begin() + static_cast<std::ptrdiff_t>(accepted_drafts));

    if (!result.reached_eog) {
        result.emitted_tokens.push_back(next_token);
        if (!decode_single_token(g_context, next_token)) {
            result.status = STREAM_STATUS_RUNTIME_ERROR;
            return result;
        }
        *target_context_tokens += 1;
    }

    const llama_pos keep_draft_tokens = base_draft_tokens + static_cast<llama_pos>(accepted_drafts);
    llama_memory_seq_rm(llama_get_memory(g_draft_context), 0, keep_draft_tokens, -1);
    *draft_context_tokens = keep_draft_tokens;
    llama_sampler_reset(g_draft_sampler);
    if (!result.reached_eog) {
        if (!decode_single_token(g_draft_context, next_token)) {
            result.status = STREAM_STATUS_RUNTIME_ERROR;
            return result;
        }
        *draft_context_tokens += 1;
    }

    log_speculative_metrics(accepted_drafts, draft_tokens.size(), max_draft, remaining_tokens);
    return result;
}

void log_prefix_cache_event(
    const char * stage,
    int policy,
    bool hit,
    size_t reused_tokens,
    size_t prompt_tokens,
    bool strict_key_match,
    int slot_index) {
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "PREFIX_CACHE|stage=%s|policy=%d|hit=%s|reused_tokens=%zu|prompt_tokens=%zu|strict_key_match=%s|slot=%d",
        stage,
        policy,
        hit ? "true" : "false",
        reused_tokens,
        prompt_tokens,
        strict_key_match ? "true" : "false",
        slot_index);
    g_prefix_cache_telemetry.last_stage = stage == nullptr ? "" : stage;
    g_prefix_cache_telemetry.last_slot = slot_index;
    g_prefix_cache_telemetry.last_reused_tokens = reused_tokens;
    g_prefix_cache_telemetry.last_cache_hit = hit;
    const std::string stage_name = g_prefix_cache_telemetry.last_stage;
    if (stage_name == "target") {
        if (hit) {
            g_prefix_cache_telemetry.target_hits += 1;
        } else {
            g_prefix_cache_telemetry.target_misses += 1;
        }
    } else if (stage_name == "draft") {
        if (hit) {
            g_prefix_cache_telemetry.draft_hits += 1;
        } else {
            g_prefix_cache_telemetry.draft_misses += 1;
        }
    }
}

jint generate_locked(
    JNIEnv * env,
    const std::string & prompt_text,
    int max_tokens,
    const std::string & cache_key,
    int cache_policy,
    const std::function<bool(const char *, size_t)> & emit_piece) {
    if (g_model == nullptr || g_context == nullptr || g_sampler == nullptr) {
        log_error("nativeGenerate failed: runtime not initialized");
        return STREAM_STATUS_RUNTIME_ERROR;
    }
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "GPU_PROBE_DIAG|stage=generate_enter|max_tokens=%d|prompt_bytes=%zu",
        max_tokens,
        prompt_text.size());
    if (g_cancel_requested.exchange(false, std::memory_order_acq_rel)) {
        log_error("nativeGenerate cancelled before start");
        return STREAM_STATUS_CANCELLED;
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) {
        log_error("nativeGenerate failed: model vocabulary is null");
        return STREAM_STATUS_RUNTIME_ERROR;
    }

    llama_sampler_reset(g_sampler);
    if (g_speculative_enabled && g_draft_sampler != nullptr) {
        llama_sampler_reset(g_draft_sampler);
    }

    const int runtime_context_size = static_cast<int>(
        llama_n_ctx(g_context) > 0 ? llama_n_ctx(g_context) : static_cast<uint32_t>(g_runtime_context_size));
    const int safe_max_tokens = std::clamp(max_tokens, 1, std::max(1, runtime_context_size - 1));
    if (safe_max_tokens != max_tokens) {
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "GENERATION_CLAMP|max_tokens_requested=%d|max_tokens_applied=%d|n_ctx=%d",
            max_tokens,
            safe_max_tokens,
            runtime_context_size);
    }
    std::vector<llama_token> prompt_tokens = tokenize_prompt(vocab, prompt_text);
    if (prompt_tokens.empty()) {
        log_error("nativeGenerate failed: prompt tokenization returned zero tokens");
        return STREAM_STATUS_RUNTIME_ERROR;
    }
    const int max_prompt_tokens = std::max(1, runtime_context_size - safe_max_tokens - 1);

    auto shift_prefill_window = [&](llama_context * context,
                                    llama_pos * context_tokens,
                                    std::vector<llama_token> * cached_tokens,
                                    const char * label) {
        if (context == nullptr || context_tokens == nullptr || cached_tokens == nullptr) {
            return false;
        }
        while (static_cast<int>(prompt_tokens.size()) > max_prompt_tokens) {
            const llama_pos n_keep = resolve_n_keep_tokens(cached_tokens->size());
            llama_pos discard = resolve_context_shift_discard(
                static_cast<llama_pos>(prompt_tokens.size()),
                safe_max_tokens + 1,
                n_keep);
            const llama_pos max_discard = std::max<llama_pos>(0, *context_tokens - n_keep);
            discard = std::min(discard, max_discard);
            if (discard <= 0) {
                return false;
            }
            llama_sampler * sampler = context == g_context ? g_sampler : g_draft_sampler;
            if (!shift_or_rebuild_context_window_locked(
                    context,
                    sampler,
                    context_tokens,
                    cached_tokens,
                    n_keep,
                    discard,
                    label)) {
                return false;
            }
            erase_token_window(&prompt_tokens, n_keep, discard);
        }
        return true;
    };

    bool strict_key_match = true;
    size_t reused_tokens = 0;
    size_t decode_offset = 0;
    bool cache_hit = false;
    int cache_slot = -1;
    const bool use_prefix_cache =
        cache_policy == CACHE_POLICY_PREFIX_REUSE || cache_policy == CACHE_POLICY_PREFIX_REUSE_STRICT;
    const int previously_active_cache_slot = g_active_prefix_cache_slot;
    if (use_prefix_cache) {
        cache_slot = reuse_prefix_cache_slot(cache_policy, cache_key, prompt_tokens, &strict_key_match, &reused_tokens);
        if (cache_slot >= 0 && reused_tokens > 0) {
            auto & selected_slot = g_prefix_cache_slots[static_cast<size_t>(cache_slot)];
            const size_t stored_target_tokens = selected_slot.target_prompt_tokens.size();
            if (g_prefix_cache_prefill_only_mode && reused_tokens < stored_target_tokens) {
                __android_log_print(
                    ANDROID_LOG_INFO,
                    TAG,
                    "PREFIX_CACHE|stage=target_reuse_skip|slot=%d|reason=partial_reuse_unsupported|requested_reuse=%zu|stored_tokens=%zu",
                    cache_slot,
                    reused_tokens,
                    stored_target_tokens);
                reused_tokens = 0;
                cache_slot = -1;
            }
        }
        if (cache_slot >= 0 && reused_tokens > 0) {
            auto & selected_slot = g_prefix_cache_slots[static_cast<size_t>(cache_slot)];
            const size_t stored_target_tokens = selected_slot.target_prompt_tokens.size();
            bool target_state_ready = restore_seq_state(
                g_context,
                selected_slot.target_seq_state,
                "target",
                cache_slot);
            if (!target_state_ready &&
                cache_slot == previously_active_cache_slot &&
                selected_slot.target_seq_state.empty()) {
                target_state_ready = true;
            }
            if (target_state_ready &&
                (reused_tokens >= stored_target_tokens ||
                    llama_memory_seq_rm(llama_get_memory(g_context), 0, static_cast<llama_pos>(reused_tokens), -1))) {
                decode_offset = std::min(reused_tokens, stored_target_tokens);
                cache_hit = true;
                llama_pos reused_context_tokens = static_cast<llama_pos>(decode_offset);
                shift_prefill_window(g_context, &reused_context_tokens, &selected_slot.target_prompt_tokens, "target_prefill");
                reused_tokens = std::min(decode_offset, static_cast<size_t>(reused_context_tokens));
                decode_offset = std::min(decode_offset, static_cast<size_t>(reused_context_tokens));
            } else {
                __android_log_print(
                    ANDROID_LOG_INFO,
                    TAG,
                    "PREFIX_CACHE|stage=target_reuse_abort|slot=%d|target_state_ready=%s|requested_reuse=%zu|previously_active_slot=%d",
                    cache_slot,
                    target_state_ready ? "true" : "false",
                    reused_tokens,
                    previously_active_cache_slot);
                reused_tokens = 0;
                cache_slot = -1;
            }
        }
    }

    if (static_cast<int>(prompt_tokens.size()) > max_prompt_tokens) {
        trim_prompt_tokens_for_context(&prompt_tokens, static_cast<size_t>(max_prompt_tokens));
        if (cache_hit) {
            llama_memory_clear(llama_get_memory(g_context), false);
            decode_offset = 0;
            reused_tokens = 0;
            cache_hit = false;
            cache_slot = -1;
        }
    }

    if (!cache_hit) {
        llama_memory_clear(llama_get_memory(g_context), false);
        decode_offset = 0;
    }

    if (!decode_tokens_from_offset(g_context, prompt_tokens, decode_offset)) {
        const bool cancelled = g_cancel_requested.load(std::memory_order_acquire);
        if (!g_cancel_requested.load(std::memory_order_acquire)) {
            log_error("nativeGenerate failed: llama_decode failed on prompt batch");
        }
        g_cancel_requested.store(false, std::memory_order_release);
        return cancelled ? STREAM_STATUS_CANCELLED : STREAM_STATUS_RUNTIME_ERROR;
    }

    std::vector<uint8_t> target_prefill_seq_state;
    std::vector<uint8_t> draft_prefill_seq_state;
    log_prefix_cache_event("target", cache_policy, cache_hit, reused_tokens, prompt_tokens.size(), strict_key_match, cache_slot);
    if (use_prefix_cache && g_prefix_cache_prefill_only_mode) {
        capture_seq_state(g_context, &target_prefill_seq_state, MAX_TARGET_PREFIX_STATE_BYTES, "target_prefill", cache_slot);
    }

    const bool speculative_ready = g_speculative_enabled &&
        g_draft_model != nullptr &&
        g_draft_context != nullptr &&
        g_draft_sampler != nullptr;
    bool draft_strict_key_match = true;
    size_t draft_reused_tokens = 0;
    bool draft_cache_hit = false;
    llama_pos target_context_tokens = static_cast<llama_pos>(prompt_tokens.size());
    llama_pos draft_context_tokens = static_cast<llama_pos>(prompt_tokens.size());
    std::vector<llama_token> tracked_target_tokens = prompt_tokens;
    std::vector<llama_token> tracked_draft_tokens = prompt_tokens;
    if (speculative_ready) {
        size_t draft_decode_offset = 0;
        if (use_prefix_cache && cache_slot >= 0) {
            auto & selected_slot = g_prefix_cache_slots[static_cast<size_t>(cache_slot)];
            const bool strict_mode = cache_policy == CACHE_POLICY_PREFIX_REUSE_STRICT;
            draft_strict_key_match = !strict_mode || (!cache_key.empty() && cache_key == selected_slot.cache_key);
            if (draft_strict_key_match && !selected_slot.draft_prompt_tokens.empty()) {
                draft_reused_tokens = longest_common_prefix(selected_slot.draft_prompt_tokens, prompt_tokens);
                if (draft_reused_tokens > 0) {
                    const size_t stored_draft_tokens = selected_slot.draft_prompt_tokens.size();
                    if (g_prefix_cache_prefill_only_mode && draft_reused_tokens < stored_draft_tokens) {
                        draft_reused_tokens = 0;
                    }
                }
                if (draft_reused_tokens > 0) {
                    const size_t stored_draft_tokens = selected_slot.draft_prompt_tokens.size();
                    bool draft_state_ready = restore_seq_state(
                        g_draft_context,
                        selected_slot.draft_seq_state,
                        "draft",
                        cache_slot);
                    if (!draft_state_ready &&
                        cache_slot == previously_active_cache_slot &&
                        selected_slot.draft_seq_state.empty()) {
                        draft_state_ready = true;
                    }
                    if (draft_state_ready &&
                        (draft_reused_tokens >= stored_draft_tokens ||
                            llama_memory_seq_rm(llama_get_memory(g_draft_context), 0, static_cast<llama_pos>(draft_reused_tokens), -1))) {
                        draft_decode_offset = std::min(draft_reused_tokens, stored_draft_tokens);
                        draft_cache_hit = true;
                        llama_pos draft_reused_context_tokens = static_cast<llama_pos>(draft_decode_offset);
                        shift_prefill_window(
                            g_draft_context,
                            &draft_reused_context_tokens,
                            &selected_slot.draft_prompt_tokens,
                            "draft_prefill");
                        draft_reused_tokens = std::min(
                            draft_decode_offset,
                            static_cast<size_t>(draft_reused_context_tokens));
                        draft_decode_offset = std::min(
                            draft_decode_offset,
                            static_cast<size_t>(draft_reused_context_tokens));
                    } else {
                        draft_reused_tokens = 0;
                    }
                }
            }
        }
        if (!draft_cache_hit) {
            llama_memory_clear(llama_get_memory(g_draft_context), false);
            draft_decode_offset = 0;
        }
        if (!decode_tokens_from_offset(g_draft_context, prompt_tokens, draft_decode_offset)) {
            log_error("nativeGenerate failed: draft prompt decode failed");
            g_cancel_requested.store(false, std::memory_order_release);
            return STREAM_STATUS_RUNTIME_ERROR;
        }
        tracked_draft_tokens = prompt_tokens;
        if (use_prefix_cache && g_prefix_cache_prefill_only_mode) {
            capture_seq_state(g_draft_context, &draft_prefill_seq_state, MAX_DRAFT_PREFIX_STATE_BYTES, "draft_prefill", cache_slot);
        }
        if (cache_hit) {
            g_active_prefix_cache_slot = cache_slot;
        }
        log_prefix_cache_event(
            "draft",
            cache_policy,
            draft_cache_hit,
            draft_reused_tokens,
            prompt_tokens.size(),
            draft_strict_key_match,
            cache_slot);
    }

    llama_batch batch{};
    std::string utf8_pending;
    bool reached_max_tokens = true;
    int emitted_token_count = 0;
    while (emitted_token_count < safe_max_tokens) {
        if (g_cancel_requested.load(std::memory_order_acquire)) {
            log_error("nativeGenerate cancelled while sampling");
            g_cancel_requested.store(false, std::memory_order_release);
            return STREAM_STATUS_CANCELLED;
        }
        std::vector<llama_token> emitted_tokens;
        const int remaining_tokens = safe_max_tokens - emitted_token_count;
        const int reserved_tokens = remaining_tokens + 1;
        if (target_context_tokens + reserved_tokens >= static_cast<llama_pos>(runtime_context_size)) {
            const llama_pos target_n_keep = resolve_n_keep_tokens(tracked_target_tokens.size());
            const llama_pos target_discard = resolve_context_shift_discard(
                target_context_tokens,
                reserved_tokens,
                target_n_keep);
            if (target_discard <= 0 ||
                !shift_or_rebuild_context_window_locked(
                    g_context,
                    g_sampler,
                    &target_context_tokens,
                    &tracked_target_tokens,
                    target_n_keep,
                    target_discard,
                    "target_decode")) {
                log_error("nativeGenerate failed: target context window exhausted");
                g_cancel_requested.store(false, std::memory_order_release);
                return STREAM_STATUS_RUNTIME_ERROR;
            }
            if (speculative_ready) {
                const llama_pos draft_n_keep = resolve_n_keep_tokens(tracked_draft_tokens.size());
                const llama_pos draft_discard = resolve_context_shift_discard(
                    draft_context_tokens,
                    reserved_tokens,
                    draft_n_keep);
                if (draft_discard <= 0 ||
                    !shift_or_rebuild_context_window_locked(
                        g_draft_context,
                        g_draft_sampler,
                        &draft_context_tokens,
                        &tracked_draft_tokens,
                        draft_n_keep,
                        draft_discard,
                        "draft_decode")) {
                    log_error("nativeGenerate failed: draft context window exhausted");
                    g_cancel_requested.store(false, std::memory_order_release);
                    return STREAM_STATUS_RUNTIME_ERROR;
                }
            }
        }
        if (speculative_ready && remaining_tokens > 1) {
            SpeculativeStepResult step = run_speculative_step(
                vocab,
                remaining_tokens,
                &target_context_tokens,
                &draft_context_tokens);
            if (step.status != STREAM_STATUS_COMPLETED) {
                g_cancel_requested.store(false, std::memory_order_release);
                return step.status;
            }
            if (step.reached_eog) {
                reached_max_tokens = false;
                break;
            }
            emitted_tokens = std::move(step.emitted_tokens);
        } else {
            const llama_token token = llama_sampler_sample(g_sampler, g_context, -1);
            if (llama_vocab_is_eog(vocab, token)) {
                reached_max_tokens = false;
                break;
            }
            emitted_tokens.push_back(token);
            llama_sampler_accept(g_sampler, token);

            llama_token next_token = token;
            batch = llama_batch_get_one(&next_token, 1);
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "GPU_PROBE_DIAG|stage=sample_decode_before|index=%d",
                emitted_token_count);
            if (llama_decode(g_context, batch) != 0) {
                log_error("nativeGenerate failed: llama_decode failed on sampled token");
                g_cancel_requested.store(false, std::memory_order_release);
                return STREAM_STATUS_RUNTIME_ERROR;
            }
            target_context_tokens += 1;
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "GPU_PROBE_DIAG|stage=sample_decode_after|index=%d",
                emitted_token_count);
            if (speculative_ready) {
                if (!decode_single_token(g_draft_context, token)) {
                    g_cancel_requested.store(false, std::memory_order_release);
                    return STREAM_STATUS_RUNTIME_ERROR;
                }
                draft_context_tokens += 1;
            }
            if (g_cancel_requested.load(std::memory_order_acquire)) {
                log_error("nativeGenerate cancelled after decode");
                g_cancel_requested.store(false, std::memory_order_release);
                return STREAM_STATUS_CANCELLED;
            }
        }

        for (const llama_token token : emitted_tokens) {
            std::string piece;
            if (!token_to_piece_dynamic(vocab, token, &piece)) {
                log_error("nativeGenerate failed: llama_token_to_piece dynamic sizing failed");
                g_cancel_requested.store(false, std::memory_order_release);
                return STREAM_STATUS_RUNTIME_ERROR;
            }
            const EmitUtf8Status emit_status = emit_utf8_buffered(piece, &utf8_pending, emit_piece);
            if (emit_status == EmitUtf8Status::CALLBACK_REJECTED) {
                log_error("nativeGenerate failed: token callback rejected piece");
                g_cancel_requested.store(false, std::memory_order_release);
                return STREAM_STATUS_CALLBACK_ERROR;
            }
        }
        tracked_target_tokens.insert(tracked_target_tokens.end(), emitted_tokens.begin(), emitted_tokens.end());
        if (speculative_ready) {
            tracked_draft_tokens.insert(tracked_draft_tokens.end(), emitted_tokens.begin(), emitted_tokens.end());
        }
        emitted_token_count += static_cast<int>(emitted_tokens.size());
    }

    if (!utf8_pending.empty()) {
        constexpr char UTF8_REPLACEMENT_CHAR[] = "\xEF\xBF\xBD";
        constexpr size_t UTF8_REPLACEMENT_CHAR_LEN = 3;
        while (!utf8_pending.empty()) {
            const Utf8PrefixResult final_prefix = utf8_valid_prefix(utf8_pending);
            if (final_prefix.valid_prefix_len > 0) {
                if (!emit_piece(utf8_pending.data(), final_prefix.valid_prefix_len)) {
                    log_error("nativeGenerate failed: callback rejected utf8 tail");
                    g_cancel_requested.store(false, std::memory_order_release);
                    return STREAM_STATUS_CALLBACK_ERROR;
                }
                utf8_pending.erase(0, final_prefix.valid_prefix_len);
                continue;
            }
            if (final_prefix.has_invalid) {
                utf8_pending.erase(0, 1);
                if (!emit_piece(UTF8_REPLACEMENT_CHAR, UTF8_REPLACEMENT_CHAR_LEN)) {
                    log_error("nativeGenerate failed: callback rejected utf8 replacement tail");
                    g_cancel_requested.store(false, std::memory_order_release);
                    return STREAM_STATUS_CALLBACK_ERROR;
                }
                __android_log_print(ANDROID_LOG_WARN, TAG, "UTF8_STREAM|trailing_invalid_byte_replaced=true");
                continue;
            }
            if (final_prefix.has_incomplete) {
                utf8_pending.clear();
                if (!emit_piece(UTF8_REPLACEMENT_CHAR, UTF8_REPLACEMENT_CHAR_LEN)) {
                    log_error("nativeGenerate failed: callback rejected utf8 replacement tail");
                    g_cancel_requested.store(false, std::memory_order_release);
                    return STREAM_STATUS_CALLBACK_ERROR;
                }
                __android_log_print(ANDROID_LOG_WARN, TAG, "UTF8_STREAM|trailing_incomplete_replaced=true");
                break;
            }
            break;
        }
    }
    if (cache_hit && !speculative_ready) {
        g_active_prefix_cache_slot = cache_slot;
    }
    const auto & target_tokens_to_store = g_prefix_cache_prefill_only_mode ? prompt_tokens : tracked_target_tokens;
    const auto & draft_tokens_to_store = g_prefix_cache_prefill_only_mode ? prompt_tokens : tracked_draft_tokens;
    cache_slot = store_prefix_cache_slot(
        target_tokens_to_store,
        speculative_ready ? &draft_tokens_to_store : nullptr,
        cache_key);
    auto & stored_slot = g_prefix_cache_slots[static_cast<size_t>(cache_slot)];
    if (g_prefix_cache_prefill_only_mode && !target_prefill_seq_state.empty()) {
        stored_slot.target_seq_state = std::move(target_prefill_seq_state);
    } else {
        capture_seq_state(g_context, &stored_slot.target_seq_state, MAX_TARGET_PREFIX_STATE_BYTES, "target", cache_slot);
    }
    if (speculative_ready) {
        if (g_prefix_cache_prefill_only_mode && !draft_prefill_seq_state.empty()) {
            stored_slot.draft_seq_state = std::move(draft_prefill_seq_state);
        } else {
            capture_seq_state(g_draft_context, &stored_slot.draft_seq_state, MAX_DRAFT_PREFIX_STATE_BYTES, "draft", cache_slot);
        }
    }
    log_prefix_cache_event(
        "target_store",
        cache_policy,
        cache_hit,
        reused_tokens,
        target_tokens_to_store.size(),
        strict_key_match,
        cache_slot);
    if (speculative_ready) {
        log_prefix_cache_event(
            "draft_store",
            cache_policy,
            draft_cache_hit,
            draft_reused_tokens,
            draft_tokens_to_store.size(),
            draft_strict_key_match,
            cache_slot);
    }
    g_cancel_requested.store(false, std::memory_order_release);
    return reached_max_tokens ? STREAM_STATUS_MAX_TOKENS : STREAM_STATUS_COMPLETED;
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeInitialize(
    JNIEnv * /*env*/,
    jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return ensure_backend_initialized_locked(false) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeLoadModel(
    JNIEnv * env,
    jobject /*thiz*/,
    jstring /*modelId*/,
    jstring modelPath,
    jint nThreads,
    jint nThreadsBatch,
    jint nBatch,
    jint nUbatch,
    jint nCtx,
    jint nGpuLayers,
    jint flashAttnCode,
    jint kvCachePresetCode,
    jfloat temperature,
    jint topK,
    jfloat topP,
    jfloat minP,
    jfloat typicalP,
    jint repeatLastN,
    jfloat repeatPenalty,
    jfloat frequencyPenalty,
    jfloat presencePenalty,
    jint mirostat,
    jfloat mirostatTau,
    jfloat mirostatEta,
    jfloat xtcThreshold,
    jfloat xtcProbability,
    jint seed,
    jboolean speculativeEnabled,
    jstring speculativeDraftModelPath,
    jint speculativeMaxDraftTokens,
    jint speculativeMinDraftTokens,
    jint speculativeDraftGpuLayers,
    jboolean useMmap,
    jboolean useMlock,
    jint nKeep,
    jobject progressCallback) {
    const std::string model_path = to_std_string(env, modelPath);
    const std::string draft_model_path = to_std_string(env, speculativeDraftModelPath);
    if (model_path.empty()) {
        log_error("nativeLoadModel failed: empty model path");
        return JNI_FALSE;
    }
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        release_runtime_locked();
        clear_backend_error_locked();
        g_last_model_quantization = extract_quantization_tag_from_path(model_path);
        const int32_t requested_target_gpu_layers =
            nGpuLayers < 0 ? -1 : clamp_i32(static_cast<int32_t>(nGpuLayers), 0, 128);
        const int32_t requested_draft_gpu_layers =
            speculativeDraftGpuLayers < 0 ? -1 : clamp_i32(static_cast<int32_t>(speculativeDraftGpuLayers), 0, 128);
        const bool request_gpu_layers = requested_target_gpu_layers != 0 || requested_draft_gpu_layers != 0;
        if (!ensure_backend_initialized_locked(request_gpu_layers)) {
            set_backend_error_locked("BACKEND_INIT_FAILED", "backend initialization failed");
            log_error("nativeLoadModel failed: backend initialization failed");
            return JNI_FALSE;
        }
        log_gpu_support_once();
        const BackendSelection backend_selection = resolve_backend_selection_locked(request_gpu_layers);
        const bool supports_gpu_offload = backend_selection.runtime_supported;
        const bool explicit_accelerator_profile = g_backend_profile == "hexagon" || g_backend_profile == "opencl";
        if (request_gpu_layers && explicit_accelerator_profile && backend_selection.selected_device == nullptr) {
            std::ostringstream detail;
            detail << "profile=" << g_backend_profile
                   << "|runtime_supported=" << (supports_gpu_offload ? "true" : "false")
                   << "|requested_layers=" << requested_target_gpu_layers
                   << "|requested_draft_layers=" << requested_draft_gpu_layers;
            set_backend_error_locked("GPU_BACKEND_UNAVAILABLE", detail.str());
            log_error("nativeLoadModel failed: requested accelerator backend unavailable");
            return JNI_FALSE;
        }
        const bool accelerator_selected = backend_selection.selected_device != nullptr;
        std::vector<ggml_backend_dev_t> selected_devices;
        if (accelerator_selected) {
            selected_devices.push_back(backend_selection.selected_device);
            selected_devices.push_back(nullptr);
        }

        const bool should_scale_draft_progress =
            speculativeEnabled == JNI_TRUE && !draft_model_path.empty() && draft_model_path != model_path;
        LoadProgressContext target_progress {};
        LoadProgressContext draft_progress {};

        llama_model_params model_params = llama_model_default_params();
        model_params.use_mmap = useMmap == JNI_TRUE;
        model_params.use_mlock = useMlock == JNI_TRUE;
        model_params.n_gpu_layers = accelerator_selected ? requested_target_gpu_layers : 0;
        model_params.devices = accelerator_selected ? selected_devices.data() : nullptr;
        configure_progress_callback(
            env,
            progressCallback,
            0.0f,
            should_scale_draft_progress ? 0.9f : 1.0f,
            &target_progress,
            &model_params);
        g_model_use_mmap = model_params.use_mmap;
        g_model_use_mlock = model_params.use_mlock;
        const bool use_gpu_ops = model_params.n_gpu_layers != 0;
        if (accelerator_selected) {
            model_params.split_mode = LLAMA_SPLIT_MODE_NONE;
            model_params.main_gpu = 0;
            g_active_backend = backend_selection.selected_backend;
            g_active_backend_memory_bytes = backend_selection.selected_device_memory_bytes;
        } else {
            model_params.split_mode = LLAMA_SPLIT_MODE_NONE;
            model_params.main_gpu = -1;
            g_active_backend = "cpu";
            g_active_backend_memory_bytes = 0;
        }
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "GPU_OFFLOAD|requested_layers=%d|effective_layers=%d|requested_draft_layers=%d|split_mode=%d|main_gpu=%d|requested_profile=%s|selected_backend=%s",
            static_cast<int>(requested_target_gpu_layers),
            static_cast<int>(model_params.n_gpu_layers),
            static_cast<int>(requested_draft_gpu_layers),
            static_cast<int>(model_params.split_mode),
            static_cast<int>(model_params.main_gpu),
            g_backend_profile.c_str(),
            g_active_backend.c_str());
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "MMAP|use_mmap=%s|use_mlock=%s|use_direct_io=%s",
            model_params.use_mmap ? "true" : "false",
            model_params.use_mlock ? "true" : "false",
            model_params.use_direct_io ? "true" : "false");
        madvise_model_readahead(model_path, "target");
        errno = 0;
        g_model = load_model_with_mmap_retry(model_path, &model_params, "target");
        const int target_load_errno = errno;
        g_model_use_mmap = model_params.use_mmap;
        g_model_use_mlock = model_params.use_mlock;
        if (g_model == nullptr) {
            if (target_progress.cancelled) {
                set_backend_error_locked("LOAD_CANCELLED_NEWER_REQUEST", "load cancelled by progress callback");
            } else if (is_out_of_memory_errno(target_load_errno)) {
                set_backend_error_locked(
                    "OUT_OF_MEMORY",
                    std::string("stage=model_load|errno=") + std::to_string(target_load_errno) + "|detail=" + std::strerror(target_load_errno));
            } else {
                set_backend_error_locked(
                    "GPU_BACKEND_LOAD_FAILED",
                    std::string("profile=") + g_backend_profile + "|selected_backend=" + g_active_backend + "|target_layers=" +
                        std::to_string(static_cast<int>(model_params.n_gpu_layers)) + "|use_mmap=" +
                        (model_params.use_mmap ? "true" : "false"));
            }
            log_error("nativeLoadModel failed: llama_model_load_from_file returned null");
            return JNI_FALSE;
        }
        g_model_layer_count = llama_model_n_layer(g_model);
        g_model_size_bytes = static_cast<uint64_t>(llama_model_size(g_model));
        g_model_uses_recurrent_memory = llama_model_is_recurrent(g_model);
        g_model_uses_hybrid_memory = llama_model_is_hybrid(g_model);
        g_prefix_cache_prefill_only_mode = g_model_uses_recurrent_memory || g_model_uses_hybrid_memory;
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "PREFIX_CACHE|stage=model_capability|prefill_only_mode=%s|model_recurrent=%s|model_hybrid=%s",
            g_prefix_cache_prefill_only_mode ? "true" : "false",
            g_model_uses_recurrent_memory ? "true" : "false",
            g_model_uses_hybrid_memory ? "true" : "false");

        llama_context_params context_params = llama_context_default_params();
        const int32_t requested_n_batch = resolve_batch(nBatch);
        const int32_t requested_n_ubatch = resolve_batch(nUbatch);
        const bool recurrent_cpu_batch_cap_applied =
            !use_gpu_ops && model_has_no_kv_cache();
        context_params.n_ctx = static_cast<uint32_t>(resolve_context_size(nCtx));
        context_params.n_batch = recurrent_cpu_batch_cap_applied
            ? std::min<int32_t>(requested_n_batch, CPU_PREFILL_ONLY_BATCH_CAP)
            : requested_n_batch;
        context_params.n_ubatch = recurrent_cpu_batch_cap_applied
            ? std::min<int32_t>(requested_n_ubatch, CPU_PREFILL_ONLY_BATCH_CAP)
            : requested_n_ubatch;
        context_params.n_threads = resolve_threads(nThreads);
        context_params.n_threads_batch = resolve_threads(nThreadsBatch);
        context_params.offload_kqv = use_gpu_ops;
        context_params.op_offload = use_gpu_ops;
        const bool is_opencl_backend = (g_active_backend == "opencl");
        const bool opencl_flash_guard_applied = use_gpu_ops && is_opencl_backend;
        const bool quantized_kv_applicable = !model_has_no_kv_cache();
        context_params.kv_unified = quantized_kv_applicable;
        context_params.flash_attn_type = resolve_flash_attn_type(flashAttnCode, opencl_flash_guard_applied);
        const auto kv_types = resolve_quantized_kv_types(kvCachePresetCode);
        const ggml_type requested_kv_type_k = kv_types.type_k;
        const ggml_type requested_kv_type_v = kv_types.type_v;
        const bool quantized_kv_requested =
            quantized_kv_applicable &&
                (requested_kv_type_k != GGML_TYPE_F16 || requested_kv_type_v != GGML_TYPE_F16);
        const bool quantized_kv_enabled =
            quantized_kv_requested &&
                !is_opencl_backend &&
                context_params.flash_attn_type != LLAMA_FLASH_ATTN_TYPE_DISABLED;
        if (quantized_kv_enabled) {
            context_params.type_k = requested_kv_type_k;
            context_params.type_v = requested_kv_type_v;
        } else {
            context_params.type_k = GGML_TYPE_F16;
            context_params.type_v = GGML_TYPE_F16;
        }
        g_last_flash_attn_requested = flashAttnCode != 2;
        g_last_flash_attn_gpu_ops = use_gpu_ops;
        g_last_opencl_flash_guard_applied = opencl_flash_guard_applied;
        g_last_opencl_quant_kv_guard_applied = quantized_kv_requested && is_opencl_backend;
        g_last_quantized_kv_cache = quantized_kv_enabled;
        g_last_kv_cache_fallback_reason.clear();
        if (!quantized_kv_applicable) {
            g_last_kv_cache_mode = "not_applicable";
            g_last_kv_cache_fallback_reason = "recurrent_no_kv_cache";
        } else if (quantized_kv_enabled) {
            g_last_kv_cache_mode = "quantized_upstream_rotation";
        } else if (quantized_kv_requested) {
            g_last_kv_cache_mode = "f16_guarded";
            g_last_kv_cache_fallback_reason =
                is_opencl_backend ? "opencl_backend" : "flash_attention_disabled";
        } else {
            g_last_kv_cache_mode = "f16";
        }
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "GPU_OFFLOAD|offload_kqv=%s|op_offload=%s|flash_attn_type=%d|quantized_kv=%s",
            context_params.offload_kqv ? "true" : "false",
            context_params.op_offload ? "true" : "false",
            static_cast<int>(context_params.flash_attn_type),
            quantized_kv_enabled ? "true" : "false");
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "BATCH_TUNING|requested_n_batch=%d|requested_n_ubatch=%d|effective_n_batch=%u|effective_n_ubatch=%u|prefill_only_mode=%s|cpu_cap_applied=%s",
            requested_n_batch,
            requested_n_ubatch,
            context_params.n_batch,
            context_params.n_ubatch,
            g_prefix_cache_prefill_only_mode ? "true" : "false",
            recurrent_cpu_batch_cap_applied ? "true" : "false");
        auto init_target_context = [&](const char * stage, int * init_errno) -> bool {
            errno = 0;
            g_context = llama_init_from_model(g_model, context_params);
            *init_errno = errno;
            if (g_context != nullptr) {
                return true;
            }
            if (is_out_of_memory_errno(*init_errno)) {
                set_backend_error_locked(
                    "OUT_OF_MEMORY",
                    std::string("stage=") + stage + "|errno=" + std::to_string(*init_errno) +
                        "|detail=" + std::strerror(*init_errno));
            } else {
                set_backend_error_locked(
                    "GPU_CONTEXT_INIT_FAILED",
                    std::string("stage=") + stage + "|profile=" + g_backend_profile +
                        "|selected_backend=" + g_active_backend);
            }
            return false;
        };
        int context_init_errno = 0;
        if (!init_target_context("context_init", &context_init_errno)) {
            log_error("nativeLoadModel failed: llama_init_from_model returned null");
            release_runtime_locked();
            return JNI_FALSE;
        }
        g_last_flash_attn_active = context_params.flash_attn_type != LLAMA_FLASH_ATTN_TYPE_DISABLED;
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "FLASH_ATTN|requested=%s|type=%s|gpu_ops=%s|type_k=%s|type_v=%s|n_ctx=%u|n_batch=%u|n_ubatch=%u|kv_cache_mode=%s|fallback_reason=%s",
            g_last_flash_attn_requested ? "true" : "false",
            llama_flash_attn_type_name(context_params.flash_attn_type),
            g_last_flash_attn_gpu_ops ? "true" : "false",
            ggml_type_name(context_params.type_k),
            ggml_type_name(context_params.type_v),
            context_params.n_ctx,
            context_params.n_batch,
            context_params.n_ubatch,
            g_last_kv_cache_mode.c_str(),
            g_last_kv_cache_fallback_reason.c_str());

        if (should_run_native_warmup(use_gpu_ops)) {
            const int warmup_rc = warmup_context(g_context, g_model);
            __android_log_print(ANDROID_LOG_INFO, TAG, "WARMUP|target|rc=%d", warmup_rc);
        } else {
            __android_log_print(
                ANDROID_LOG_INFO,
                TAG,
                "WARMUP|target|skipped=true|reason=cpu_prefill_only_low_roi");
        }

        const float resolved_temperature = std::max(0.0f, static_cast<float>(temperature));
        const float resolved_top_p = std::max(0.0f, std::min(1.0f, static_cast<float>(topP)));
        const int32_t resolved_top_k = clamp_i32(static_cast<int32_t>(topK), 0, 1024);
        const float resolved_min_p = std::max(0.0f, std::min(1.0f, static_cast<float>(minP)));
        const float resolved_typical_p = std::max(0.0f, std::min(1.0f, static_cast<float>(typicalP)));
        const int32_t resolved_repeat_last_n =
            static_cast<int32_t>(repeatLastN) < -1 ? -1 : static_cast<int32_t>(repeatLastN);
        const float resolved_repeat_penalty = std::max(0.0f, static_cast<float>(repeatPenalty));
        const float resolved_frequency_penalty = static_cast<float>(frequencyPenalty);
        const float resolved_presence_penalty = static_cast<float>(presencePenalty);
        const int32_t resolved_mirostat = clamp_i32(static_cast<int32_t>(mirostat), 0, 2);
        const float resolved_mirostat_tau = std::max(0.0f, static_cast<float>(mirostatTau));
        const float resolved_mirostat_eta = std::max(0.0f, static_cast<float>(mirostatEta));
        const float resolved_xtc_threshold = std::max(0.0f, std::min(1.0f, static_cast<float>(xtcThreshold)));
        const float resolved_xtc_probability = std::max(0.0f, std::min(1.0f, static_cast<float>(xtcProbability)));
        const int32_t resolved_seed = static_cast<int32_t>(seed);
        const int32_t resolved_prompt_decode_batch =
            std::min<int32_t>(context_params.n_batch, context_params.n_ubatch);
        const int32_t cpu_prompt_decode_batch_cap = model_has_no_kv_cache()
            ? CPU_PREFILL_ONLY_PROMPT_DECODE_BATCH_CAP
            : CPU_PROMPT_DECODE_BATCH_CAP;
        g_prompt_decode_batch_size = use_gpu_ops
            ? resolved_prompt_decode_batch
            : std::min<int32_t>(resolved_prompt_decode_batch, cpu_prompt_decode_batch_cap);
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "PROMPT_DECODE|batch_size=%d|cpu_cap_applied=%s|prefill_only_mode=%s|cpu_cap=%d",
            static_cast<int>(g_prompt_decode_batch_size),
            (!use_gpu_ops && g_prompt_decode_batch_size < resolved_prompt_decode_batch) ? "true" : "false",
            g_prefix_cache_prefill_only_mode ? "true" : "false",
            cpu_prompt_decode_batch_cap);
        g_runtime_context_size = static_cast<int32_t>(context_params.n_ctx);
        g_runtime_n_keep = clamp_i32(
            static_cast<int32_t>(nKeep),
            0,
            std::max<int32_t>(0, g_runtime_context_size - 1));
        g_speculative_enabled = false;
        g_speculative_max_draft_tokens = clamp_i32(static_cast<int32_t>(speculativeMaxDraftTokens), 1, 16);
        g_speculative_min_draft_tokens = clamp_i32(static_cast<int32_t>(speculativeMinDraftTokens), 1, 16);
        if (g_speculative_min_draft_tokens > g_speculative_max_draft_tokens) {
            g_speculative_min_draft_tokens = g_speculative_max_draft_tokens;
        }

        if (speculativeEnabled == JNI_TRUE &&
            !draft_model_path.empty() &&
            draft_model_path != model_path) {
            llama_model_params draft_model_params = llama_model_default_params();
            draft_model_params.use_mmap = model_params.use_mmap;
            draft_model_params.use_mlock = model_params.use_mlock;
            draft_model_params.n_gpu_layers = accelerator_selected ? requested_draft_gpu_layers : 0;
            draft_model_params.devices = accelerator_selected ? selected_devices.data() : nullptr;
            configure_progress_callback(env, progressCallback, 0.9f, 0.1f, &draft_progress, &draft_model_params);
            if (draft_model_params.n_gpu_layers <= 0) {
                draft_model_params.split_mode = LLAMA_SPLIT_MODE_NONE;
                draft_model_params.main_gpu = -1;
            } else {
                draft_model_params.split_mode = LLAMA_SPLIT_MODE_NONE;
                draft_model_params.main_gpu = 0;
            }
            madvise_model_readahead(draft_model_path, "draft");
            errno = 0;
            g_draft_model = load_model_with_mmap_retry(draft_model_path, &draft_model_params, "draft");
            const int draft_load_errno = errno;
            if (g_draft_model == nullptr && draft_progress.cancelled) {
                set_backend_error_locked("LOAD_CANCELLED_NEWER_REQUEST", "draft load cancelled by progress callback");
                release_runtime_locked();
                return JNI_FALSE;
            }
            if (g_draft_model == nullptr && is_out_of_memory_errno(draft_load_errno)) {
                set_backend_error_locked(
                    "OUT_OF_MEMORY",
                    std::string("stage=draft_model_load|errno=") + std::to_string(draft_load_errno) + "|detail=" + std::strerror(draft_load_errno));
                release_runtime_locked();
                return JNI_FALSE;
            }
            if (g_draft_model != nullptr && speculative_vocabs_compatible(g_model, g_draft_model)) {
                llama_context_params draft_context_params = llama_context_default_params();
                draft_context_params.n_ctx = std::min<uint32_t>(context_params.n_ctx, 1024u);
                draft_context_params.n_batch = context_params.n_batch;
                draft_context_params.n_ubatch = context_params.n_ubatch;
                draft_context_params.n_threads = resolve_threads(nThreadsBatch);
                draft_context_params.n_threads_batch = resolve_threads(nThreadsBatch);
                draft_context_params.offload_kqv = draft_model_params.n_gpu_layers != 0;
                draft_context_params.op_offload = draft_model_params.n_gpu_layers != 0;
                draft_context_params.kv_unified = context_params.kv_unified;
                draft_context_params.flash_attn_type = context_params.flash_attn_type;
                draft_context_params.type_k = context_params.type_k;
                draft_context_params.type_v = context_params.type_v;
                errno = 0;
                g_draft_context = llama_init_from_model(g_draft_model, draft_context_params);
                const int draft_context_errno = errno;
                if (g_draft_context == nullptr && is_out_of_memory_errno(draft_context_errno)) {
                    set_backend_error_locked(
                        "OUT_OF_MEMORY",
                        std::string("stage=draft_context_init|errno=") + std::to_string(draft_context_errno) + "|detail=" + std::strerror(draft_context_errno));
                    release_runtime_locked();
                    return JNI_FALSE;
                }
                if (g_draft_context != nullptr) {
                    g_draft_sampler = build_sampler_chain(
                        std::max(0.0f, resolved_temperature),
                        std::max(1, resolved_top_k / 2),
                        std::min(0.98f, std::max(0.5f, resolved_top_p)),
                        resolved_min_p,
                        resolved_typical_p,
                        resolved_repeat_last_n,
                        resolved_repeat_penalty,
                        resolved_frequency_penalty,
                        resolved_presence_penalty,
                        resolved_mirostat,
                        resolved_mirostat_tau,
                        resolved_mirostat_eta,
                        resolved_xtc_threshold,
                        resolved_xtc_probability,
                        resolved_seed);
                    if (g_draft_sampler != nullptr) {
                        g_speculative_enabled = true;
                        if (should_run_native_warmup(draft_context_params.offload_kqv)) {
                            const int draft_warmup_rc = warmup_context(g_draft_context, g_draft_model);
                            __android_log_print(ANDROID_LOG_INFO, TAG,
                                "WARMUP|draft|rc=%d", draft_warmup_rc);
                        } else {
                            __android_log_print(
                                ANDROID_LOG_INFO,
                                TAG,
                                "WARMUP|draft|skipped=true|reason=cpu_prefill_only_low_roi");
                        }
                    }
                }
            }
            if (!g_speculative_enabled) {
                if (g_draft_sampler != nullptr) {
                    llama_sampler_free(g_draft_sampler);
                    g_draft_sampler = nullptr;
                }
                if (g_draft_context != nullptr) {
                    llama_free(g_draft_context);
                    g_draft_context = nullptr;
                }
                if (g_draft_model != nullptr) {
                    llama_model_free(g_draft_model);
                    g_draft_model = nullptr;
                }
                __android_log_print(ANDROID_LOG_WARN, TAG, "SPECULATIVE|disabled|reason=draft_init_failed");
            } else {
                __android_log_print(
                    ANDROID_LOG_INFO,
                    TAG,
                    "SPECULATIVE|enabled=true|max=%d|min=%d|draft_n_ctx=%u|draft_gpu_layers=%d|acceptance_rate=%.3f",
                    static_cast<int>(g_speculative_max_draft_tokens),
                    static_cast<int>(g_speculative_min_draft_tokens),
                    g_draft_context != nullptr ? llama_n_ctx(g_draft_context) : 0u,
                    static_cast<int>(draft_model_params.n_gpu_layers),
                    static_cast<double>(g_speculative_acceptance_rate));
            }
        }
        if (!apply_sampling_config_locked(
                resolved_temperature,
                resolved_top_k,
                resolved_top_p,
                resolved_min_p,
                resolved_typical_p,
                resolved_repeat_last_n,
                resolved_repeat_penalty,
                resolved_frequency_penalty,
                resolved_presence_penalty,
                resolved_mirostat,
                resolved_mirostat_tau,
                resolved_mirostat_eta,
                resolved_xtc_threshold,
                resolved_xtc_probability,
                resolved_seed)) {
            set_backend_error_locked("SAMPLER_CONFIG_FAILED", "failed to apply runtime sampling config");
            log_error("nativeLoadModel failed: sampler config apply failed");
            release_runtime_locked();
            return JNI_FALSE;
        }
        clear_backend_error_locked();
        g_active_model_quantization = g_last_model_quantization;
        g_cancel_requested.store(false, std::memory_order_release);
        return JNI_TRUE;
    } catch (const std::bad_alloc & error) {
        std::lock_guard<std::mutex> lock(g_mutex);
        set_backend_error_locked("OUT_OF_MEMORY", error.what());
        release_runtime_locked();
        log_error("nativeLoadModel failed: std::bad_alloc");
        return JNI_FALSE;
    } catch (const std::exception & error) {
        std::lock_guard<std::mutex> lock(g_mutex);
        set_backend_error_locked("JNI_LOAD_EXCEPTION", error.what());
        release_runtime_locked();
        log_error("nativeLoadModel failed: std::exception");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeGenerate(
    JNIEnv * env,
    jobject /*thiz*/,
    jstring prompt,
    jint maxTokens,
    jstring cacheKey,
    jint cachePolicy) {
    const std::string prompt_text = to_std_string(env, prompt);
    if (prompt_text.empty()) {
        return env->NewStringUTF("");
    }
    std::string output;
    output.reserve(static_cast<size_t>(std::max(1, static_cast<int>(maxTokens))) * 4);
    const std::string cache_key = to_std_string(env, cacheKey);
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "PREFIX_CACHE|stage=generate_enter|request=sync|cache_key_len=%zu|cache_key_prefix=%s|cache_policy=%d",
        cache_key.size(),
        summarize_cache_key(cache_key).c_str(),
        static_cast<int>(cachePolicy));

    std::lock_guard<std::mutex> lock(g_mutex);
    const jint status = generate_locked(
        env,
        prompt_text,
        static_cast<int>(maxTokens),
        cache_key,
        static_cast<int>(cachePolicy),
        [&output](const char * piece, size_t piece_size) {
            output.append(piece, piece_size);
            return true;
        });
    if (status != STREAM_STATUS_COMPLETED && status != STREAM_STATUS_MAX_TOKENS) {
        return env->NewStringUTF("");
    }

    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeSetSamplingConfig(
    JNIEnv * /*env*/,
    jobject /*thiz*/,
    jfloat temperature,
    jint topK,
    jfloat topP,
    jfloat minP,
    jfloat typicalP,
    jint repeatLastN,
    jfloat repeatPenalty,
    jfloat frequencyPenalty,
    jfloat presencePenalty,
    jint mirostat,
    jfloat mirostatTau,
    jfloat mirostatEta,
    jfloat xtcThreshold,
    jfloat xtcProbability,
    jint seed,
    jint nKeep) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_runtime_n_keep = clamp_i32(
        static_cast<int32_t>(nKeep),
        0,
        std::max<int32_t>(0, g_runtime_context_size - 1));
    apply_sampling_config_locked(
        static_cast<float>(temperature),
        static_cast<int32_t>(topK),
        static_cast<float>(topP),
        static_cast<float>(minP),
        static_cast<float>(typicalP),
        static_cast<int32_t>(repeatLastN),
        static_cast<float>(repeatPenalty),
        static_cast<float>(frequencyPenalty),
        static_cast<float>(presencePenalty),
        static_cast<int32_t>(mirostat),
        static_cast<float>(mirostatTau),
        static_cast<float>(mirostatEta),
        static_cast<float>(xtcThreshold),
        static_cast<float>(xtcProbability),
        static_cast<int32_t>(seed));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeGenerateStream(
    JNIEnv * env,
    jobject /*thiz*/,
    jstring requestId,
    jstring prompt,
    jint maxTokens,
    jstring cacheKey,
    jint cachePolicy,
    jobject callback) {
    const std::string request_id = to_std_string(env, requestId);
    (void)request_id;
    const std::string prompt_text = to_std_string(env, prompt);
    if (prompt_text.empty() || callback == nullptr) {
        return STREAM_STATUS_RUNTIME_ERROR;
    }
    const std::string cache_key = to_std_string(env, cacheKey);
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "PREFIX_CACHE|stage=generate_enter|request=%s|cache_key_len=%zu|cache_key_prefix=%s|cache_policy=%d",
        request_id.empty() ? "none" : request_id.c_str(),
        cache_key.size(),
        summarize_cache_key(cache_key).c_str(),
        static_cast<int>(cachePolicy));
    jclass callback_class = env->GetObjectClass(callback);
    if (callback_class == nullptr) {
        log_error("nativeGenerateStream failed: callback class missing");
        return STREAM_STATUS_RUNTIME_ERROR;
    }
    const jmethodID on_token = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");
    if (on_token == nullptr) {
        env->DeleteLocalRef(callback_class);
        log_error("nativeGenerateStream failed: callback.onToken(String) missing");
        return STREAM_STATUS_CALLBACK_ERROR;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    const jint status = generate_locked(
        env,
        prompt_text,
        static_cast<int>(maxTokens),
        cache_key,
        static_cast<int>(cachePolicy),
        [&](const char * piece, size_t piece_size) {
            std::string token(piece, piece_size);
            jstring token_j = env->NewStringUTF(token.c_str());
            if (token_j == nullptr) {
                return false;
            }
            env->CallVoidMethod(callback, on_token, token_j);
            env->DeleteLocalRef(token_j);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                return false;
            }
            return true;
        });
    env->DeleteLocalRef(callback_class);
    return status;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeUnloadModel(
    JNIEnv * /*env*/,
    jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_runtime_locked();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeCancelGeneration(
    JNIEnv * /*env*/,
    jobject /*thiz*/) {
    g_cancel_requested.store(true, std::memory_order_release);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeInitialize(
    JNIEnv * env,
    jobject thiz) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeInitialize(env, thiz);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeLoadModel(
    JNIEnv * env,
    jobject thiz,
    jstring modelId,
    jstring modelPath,
    jint nThreads,
    jint nThreadsBatch,
    jint nBatch,
    jint nUbatch,
    jint nCtx,
    jint nGpuLayers,
    jint flashAttnCode,
    jint kvCachePresetCode,
    jfloat temperature,
    jint topK,
    jfloat topP,
    jfloat minP,
    jfloat typicalP,
    jint repeatLastN,
    jfloat repeatPenalty,
    jfloat frequencyPenalty,
    jfloat presencePenalty,
    jint mirostat,
    jfloat mirostatTau,
    jfloat mirostatEta,
    jfloat xtcThreshold,
    jfloat xtcProbability,
    jint seed,
    jboolean speculativeEnabled,
    jstring speculativeDraftModelPath,
    jint speculativeMaxDraftTokens,
    jint speculativeMinDraftTokens,
    jint speculativeDraftGpuLayers,
    jboolean useMmap,
    jboolean useMlock,
    jint nKeep,
    jobject progressCallback) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeLoadModel(
        env,
        thiz,
        modelId,
        modelPath,
        nThreads,
        nThreadsBatch,
        nBatch,
        nUbatch,
        nCtx,
        nGpuLayers,
        flashAttnCode,
        kvCachePresetCode,
        temperature,
        topK,
        topP,
        minP,
        typicalP,
        repeatLastN,
        repeatPenalty,
        frequencyPenalty,
        presencePenalty,
        mirostat,
        mirostatTau,
        mirostatEta,
        xtcThreshold,
        xtcProbability,
        seed,
        speculativeEnabled,
        speculativeDraftModelPath,
        speculativeMaxDraftTokens,
        speculativeMinDraftTokens,
        speculativeDraftGpuLayers,
        useMmap,
        useMlock,
        nKeep,
        progressCallback);
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeSetSamplingConfig(
    JNIEnv * env,
    jobject thiz,
    jfloat temperature,
    jint topK,
    jfloat topP,
    jfloat minP,
    jfloat typicalP,
    jint repeatLastN,
    jfloat repeatPenalty,
    jfloat frequencyPenalty,
    jfloat presencePenalty,
    jint mirostat,
    jfloat mirostatTau,
    jfloat mirostatEta,
    jfloat xtcThreshold,
    jfloat xtcProbability,
    jint seed,
    jint nKeep) {
    Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeSetSamplingConfig(
        env,
        thiz,
        temperature,
        topK,
        topP,
        minP,
        typicalP,
        repeatLastN,
        repeatPenalty,
        frequencyPenalty,
        presencePenalty,
        mirostat,
        mirostatTau,
        mirostatEta,
        xtcThreshold,
        xtcProbability,
        seed,
        nKeep);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeGenerate(
    JNIEnv * env,
    jobject thiz,
    jstring prompt,
    jint maxTokens,
    jstring cacheKey,
    jint cachePolicy) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeGenerate(
        env,
        thiz,
        prompt,
        maxTokens,
        cacheKey,
        cachePolicy);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeGenerateStream(
    JNIEnv * env,
    jobject thiz,
    jstring requestId,
    jstring prompt,
    jint maxTokens,
    jstring cacheKey,
    jint cachePolicy,
    jobject callback) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeGenerateStream(
        env,
        thiz,
        requestId,
        prompt,
        maxTokens,
        cacheKey,
        cachePolicy,
        callback);
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeUnloadModel(
    JNIEnv * env,
    jobject thiz) {
    Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeUnloadModel(env, thiz);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeCancelGeneration(
    JNIEnv * env,
    jobject thiz) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeCancelGeneration(
        env,
        thiz);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeSupportsGpuOffload(
    JNIEnv * /*env*/,
    jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    ensure_backend_initialized_locked(true);
    log_gpu_support_once();
    return gpu_offload_supported() ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeModelLayerCount(
    JNIEnv * /*env*/,
    jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<jint>(g_model_layer_count);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeModelSizeBytes(
    JNIEnv * /*env*/,
    jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<jlong>(g_model_size_bytes);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeEstimateMaxGpuLayers(
    JNIEnv * /*env*/,
    jobject /*thiz*/,
    jint nCtx) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<jint>(estimate_max_gpu_layers_locked(static_cast<int32_t>(nCtx)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeBackendDiagnosticsJson(
    JNIEnv * env,
    jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    ensure_backend_initialized_locked(true);
    const std::string payload = backend_diagnostics_json();
    return env->NewStringUTF(payload.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeSupportsGpuOffload(
    JNIEnv * env,
    jobject thiz) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeSupportsGpuOffload(
        env,
        thiz);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeModelLayerCount(
    JNIEnv * env,
    jobject thiz) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeModelLayerCount(
        env,
        thiz);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeModelSizeBytes(
    JNIEnv * env,
    jobject thiz) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeModelSizeBytes(
        env,
        thiz);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeEstimateMaxGpuLayers(
    JNIEnv * env,
    jobject thiz,
    jint nCtx) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeEstimateMaxGpuLayers(
        env,
        thiz,
        nCtx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeBackendDiagnosticsJson(
    JNIEnv * env,
    jobject thiz) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeBackendDiagnosticsJson(
        env,
        thiz);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativePeakRssMb(
    JNIEnv * /*env*/,
    jobject /*thiz*/) {
    return static_cast<jdouble>(read_peak_rss_mb());
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeCurrentRssMb(
    JNIEnv * /*env*/,
    jobject /*thiz*/) {
    return static_cast<jdouble>(read_current_rss_mb());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeIsRuntimeReleased(
    JNIEnv * /*env*/,
    jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return runtime_is_released_locked() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativePrefixCacheDiagnosticsLine(
    JNIEnv * env,
    jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const std::string payload = prefix_cache_diagnostics_line_locked();
    return env->NewStringUTF(payload.c_str());
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativePeakRssMb(
    JNIEnv * env,
    jobject thiz) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativePeakRssMb(
        env,
        thiz);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeCurrentRssMb(
    JNIEnv * env,
    jobject thiz) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeCurrentRssMb(
        env,
        thiz);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeIsRuntimeReleased(
    JNIEnv * env,
    jobject thiz) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeIsRuntimeReleased(
        env,
        thiz);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativePrefixCacheDiagnosticsLine(
    JNIEnv * env,
    jobject thiz) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativePrefixCacheDiagnosticsLine(
        env,
        thiz);
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeSetBackendProfile(
    JNIEnv * env,
    jobject /*thiz*/,
    jstring profile) {
    if (profile == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    const char * profileChars = env->GetStringUTFChars(profile, nullptr);
    if (profileChars) {
        const std::string normalized = lowercase_copy(profileChars);
        const std::string profile_value = normalized == "hexagon" || normalized == "opencl" || normalized == "cpu"
            ? normalized
            : "auto";
        setenv("POCKETGPT_BACKEND_PROFILE", profile_value.c_str(), 1);
        g_backend_profile = profile_value;
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "GPU_OFFLOAD|set_backend_profile=%s",
            profile_value.c_str());
        env->ReleaseStringUTFChars(profile, profileChars);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeSaveSessionCache(
    JNIEnv * env,
    jobject /*thiz*/,
    jstring filePath) {
    const std::string path = to_std_string(env, filePath);
    if (path.empty()) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_active_prefix_cache_slot < 0 ||
        g_active_prefix_cache_slot >= static_cast<int>(g_prefix_cache_slots.size())) {
        return JNI_FALSE;
    }
    const auto & slot = g_prefix_cache_slots[static_cast<size_t>(g_active_prefix_cache_slot)];
    return save_prefix_cache_slot_to_disk(slot, path) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeLoadSessionCache(
    JNIEnv * env,
    jobject /*thiz*/,
    jstring filePath) {
    const std::string path = to_std_string(env, filePath);
    if (path.empty()) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    const int slot_index = select_prefix_cache_slot_for_store();
    if (slot_index < 0) {
        return JNI_FALSE;
    }
    auto & slot = g_prefix_cache_slots[static_cast<size_t>(slot_index)];
    if (!load_prefix_cache_slot_from_disk(path, slot)) {
        return JNI_FALSE;
    }
    slot.last_used_epoch = ++g_prefix_cache_epoch;
    g_active_prefix_cache_slot = slot_index;
    return JNI_TRUE;
}

// ---------------------------------------------------------------------------
// Multimodal (vision) support
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeInitMultimodal(
    JNIEnv * env,
    jobject /*thiz*/,
    jstring mmProjPath,
    jboolean useGpu,
    jint imageMaxTokens) {
    const std::string path = to_std_string(env, mmProjPath);
    if (path.empty()) {
        log_error("nativeInitMultimodal: empty mmproj path");
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr) {
        log_error("nativeInitMultimodal: model not loaded");
        return JNI_FALSE;
    }
    if (g_mtmd_ctx != nullptr) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
    }
    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu = useGpu == JNI_TRUE;
    params.print_timings = false;
    params.n_threads = 4;
    if (imageMaxTokens > 0) {
        params.image_max_tokens = static_cast<int>(imageMaxTokens);
    }
    g_mtmd_ctx = mtmd_init_from_file(path.c_str(), g_model, params);
    if (g_mtmd_ctx == nullptr) {
        log_error("nativeInitMultimodal: mtmd_init_from_file failed");
        return JNI_FALSE;
    }
    const bool has_vision = mtmd_support_vision(g_mtmd_ctx);
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "MULTIMODAL|init=ok|vision=%s|gpu=%s|max_tokens=%d",
        has_vision ? "true" : "false",
        params.use_gpu ? "true" : "false",
        params.image_max_tokens);
    return has_vision ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeFreeMultimodal(
    JNIEnv * /*env*/,
    jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_mtmd_ctx != nullptr) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
        __android_log_print(ANDROID_LOG_INFO, TAG, "MULTIMODAL|freed");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeIsMultimodalEnabled(
    JNIEnv * /*env*/,
    jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_mtmd_ctx != nullptr && mtmd_support_vision(g_mtmd_ctx)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeGenerateStreamWithImages(
    JNIEnv * env,
    jobject /*thiz*/,
    jstring requestId,
    jstring prompt,
    jobjectArray imagePaths,
    jint maxTokens,
    jobject callback) {
    const std::string request_id = to_std_string(env, requestId);
    (void)request_id;
    const std::string prompt_text = to_std_string(env, prompt);
    if (prompt_text.empty() || callback == nullptr) {
        return STREAM_STATUS_RUNTIME_ERROR;
    }
    const int image_count = (imagePaths != nullptr) ? env->GetArrayLength(imagePaths) : 0;

    jclass callback_class = env->GetObjectClass(callback);
    if (callback_class == nullptr) {
        log_error("nativeGenerateStreamWithImages: callback class missing");
        return STREAM_STATUS_RUNTIME_ERROR;
    }
    const jmethodID on_token = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");
    if (on_token == nullptr) {
        env->DeleteLocalRef(callback_class);
        log_error("nativeGenerateStreamWithImages: callback.onToken(String) missing");
        return STREAM_STATUS_CALLBACK_ERROR;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr || g_context == nullptr || g_sampler == nullptr) {
        env->DeleteLocalRef(callback_class);
        log_error("nativeGenerateStreamWithImages: runtime not initialized");
        return STREAM_STATUS_RUNTIME_ERROR;
    }
    if (g_mtmd_ctx == nullptr) {
        env->DeleteLocalRef(callback_class);
        log_error("nativeGenerateStreamWithImages: multimodal context not initialized");
        return STREAM_STATUS_RUNTIME_ERROR;
    }
    if (g_cancel_requested.exchange(false, std::memory_order_acq_rel)) {
        env->DeleteLocalRef(callback_class);
        log_error("nativeGenerateStreamWithImages: cancelled before start");
        return STREAM_STATUS_CANCELLED;
    }

    // Load image bitmaps via stb_image through mtmd helper
    mtmd::bitmaps bitmaps;
    for (int i = 0; i < image_count; ++i) {
        auto jpath = static_cast<jstring>(env->GetObjectArrayElement(imagePaths, i));
        const std::string img_path = to_std_string(env, jpath);
        env->DeleteLocalRef(jpath);
        auto media = mtmd_helper_bitmap_init_from_file(g_mtmd_ctx, img_path.c_str(), false);
        if (media.bitmap == nullptr) {
            env->DeleteLocalRef(callback_class);
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                "MULTIMODAL|bitmap_load_failed|path=%s", img_path.c_str());
            return STREAM_STATUS_RUNTIME_ERROR;
        }
        if (media.video_ctx != nullptr) {
            mtmd_bitmap_free(media.bitmap);
            mtmd_helper_video_free(media.video_ctx);
            env->DeleteLocalRef(callback_class);
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                "MULTIMODAL|video_unsupported|path=%s", img_path.c_str());
            return STREAM_STATUS_RUNTIME_ERROR;
        }
        bitmaps.entries.emplace_back(media.bitmap);
    }

    // Tokenize prompt with interleaved media markers.
    // The prompt must contain the marker returned by mtmd_default_marker() (currently
    // "<__media__>") for each image — the Kotlin layer injects these via injectImageMarkers().
    const char * marker = mtmd_default_marker();
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "MULTIMODAL|tokenize_start|images=%d|marker=%s|prompt_len=%zu|marker_present=%s",
        image_count, marker, prompt_text.size(),
        (prompt_text.find(marker) != std::string::npos) ? "true" : "false");

    mtmd_input_text input_text{};
    input_text.text = prompt_text.c_str();
    input_text.add_special = true;
    input_text.parse_special = true;

    mtmd::input_chunks chunks(mtmd_input_chunks_init());
    auto bitmaps_c = bitmaps.c_ptr();
    int32_t tok_result = mtmd_tokenize(
        g_mtmd_ctx, chunks.ptr.get(), &input_text,
        bitmaps_c.data(), bitmaps_c.size());
    if (tok_result != 0) {
        env->DeleteLocalRef(callback_class);
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "MULTIMODAL|tokenize_failed|result=%d|n_images=%d", tok_result, image_count);
        return STREAM_STATUS_RUNTIME_ERROR;
    }

    const size_t total_tokens = mtmd_helper_get_n_tokens(chunks.ptr.get());
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "MULTIMODAL|tokenized|chunks=%zu|total_tokens=%zu|images=%d",
        chunks.size(), total_tokens, image_count);

    // Clear KV cache and evaluate all chunks (text + vision interleaved)
    llama_memory_clear(llama_get_memory(g_context), false);
    llama_pos n_past = 0;
    int32_t eval_result = mtmd_helper_eval_chunks(
        g_mtmd_ctx, g_context, chunks.ptr.get(),
        n_past, 0, g_prompt_decode_batch_size, true, &n_past);
    if (eval_result != 0) {
        env->DeleteLocalRef(callback_class);
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "MULTIMODAL|eval_chunks_failed|result=%d", eval_result);
        return STREAM_STATUS_RUNTIME_ERROR;
    }

    // Sampling loop — identical to text-only path but starting after multimodal eval
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) {
        env->DeleteLocalRef(callback_class);
        log_error("nativeGenerateStreamWithImages: vocab is null");
        return STREAM_STATUS_RUNTIME_ERROR;
    }
    llama_sampler_reset(g_sampler);

    const int runtime_context_size = static_cast<int>(
        llama_n_ctx(g_context) > 0 ? llama_n_ctx(g_context) : static_cast<uint32_t>(g_runtime_context_size));
    const int safe_max_tokens = std::clamp(maxTokens, 1, std::max(1, runtime_context_size - static_cast<int>(n_past) - 1));

    std::string utf8_pending;
    int emitted_count = 0;
    jint final_status = STREAM_STATUS_MAX_TOKENS;

    while (emitted_count < safe_max_tokens) {
        if (g_cancel_requested.load(std::memory_order_acquire)) {
            g_cancel_requested.store(false, std::memory_order_release);
            final_status = STREAM_STATUS_CANCELLED;
            break;
        }
        const llama_token token = llama_sampler_sample(g_sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            final_status = STREAM_STATUS_COMPLETED;
            break;
        }
        llama_sampler_accept(g_sampler, token);

        llama_token next_token = token;
        llama_batch batch = llama_batch_get_one(&next_token, 1);
        if (llama_decode(g_context, batch) != 0) {
            log_error("nativeGenerateStreamWithImages: decode failed on sampled token");
            final_status = STREAM_STATUS_RUNTIME_ERROR;
            break;
        }
        n_past += 1;

        std::string piece;
        if (!token_to_piece_dynamic(vocab, token, &piece)) {
            final_status = STREAM_STATUS_RUNTIME_ERROR;
            break;
        }

        utf8_pending += piece;
        while (!utf8_pending.empty()) {
            size_t valid_bytes = 0;
            for (size_t j = 0; j < utf8_pending.size(); ) {
                unsigned char c = static_cast<unsigned char>(utf8_pending[j]);
                size_t char_len = 0;
                if (c < 0x80) char_len = 1;
                else if ((c & 0xE0) == 0xC0) char_len = 2;
                else if ((c & 0xF0) == 0xE0) char_len = 3;
                else if ((c & 0xF8) == 0xF0) char_len = 4;
                else { valid_bytes = j + 1; break; }
                if (j + char_len > utf8_pending.size()) break;
                valid_bytes = j + char_len;
                j += char_len;
            }
            if (valid_bytes == 0) break;
            std::string valid_str = utf8_pending.substr(0, valid_bytes);
            utf8_pending.erase(0, valid_bytes);
            jstring token_j = env->NewStringUTF(valid_str.c_str());
            if (token_j == nullptr) {
                final_status = STREAM_STATUS_RUNTIME_ERROR;
                goto done;
            }
            env->CallVoidMethod(callback, on_token, token_j);
            env->DeleteLocalRef(token_j);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                final_status = STREAM_STATUS_CALLBACK_ERROR;
                goto done;
            }
        }
        emitted_count += 1;
    }

done:
    env->DeleteLocalRef(callback_class);
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "MULTIMODAL|generate_done|tokens=%d|status=%d", emitted_count, static_cast<int>(final_status));
    return final_status;
}

// NativeJniLlamaCppBridge delegates (multimodal)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeInitMultimodal(
    JNIEnv * env,
    jobject thiz,
    jstring mmProjPath,
    jboolean useGpu,
    jint imageMaxTokens) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeInitMultimodal(
        env, thiz, mmProjPath, useGpu, imageMaxTokens);
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeFreeMultimodal(
    JNIEnv * env,
    jobject thiz) {
    Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeFreeMultimodal(env, thiz);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeIsMultimodalEnabled(
    JNIEnv * env,
    jobject thiz) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeIsMultimodalEnabled(env, thiz);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketagent_nativebridge_NativeJniLlamaCppBridge_00024JniNativeApi_nativeGenerateStreamWithImages(
    JNIEnv * env,
    jobject thiz,
    jstring requestId,
    jstring prompt,
    jobjectArray imagePaths,
    jint maxTokens,
    jobject callback) {
    return Java_com_pocketagent_android_AndroidLlamaCppRuntimeBridge_00024JniNativeApi_nativeGenerateStreamWithImages(
        env, thiz, requestId, prompt, imagePaths, maxTokens, callback);
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM * /*vm*/, void * /*reserved*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_runtime_locked();
    if (g_backend_init_mode != BackendInitMode::NONE) {
        llama_backend_free();
        g_backend_init_mode = BackendInitMode::NONE;
    }
}
