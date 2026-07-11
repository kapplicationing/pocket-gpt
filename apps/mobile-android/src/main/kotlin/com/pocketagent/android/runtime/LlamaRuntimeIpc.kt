package com.pocketagent.android.runtime

internal object LlamaRuntimeIpc {
    /** This IPC version has no image payload or image-generation command contract. */
    const val SUPPORTS_IMAGE_GENERATION = false

    const val MSG_PING = 1
    const val MSG_SET_CONFIG = 2
    const val MSG_GET_CONFIG = 3
    const val MSG_LIST_MODELS = 4
    const val MSG_LOAD_MODEL = 5
    const val MSG_UNLOAD_MODEL = 6
    const val MSG_GENERATE_START = 7
    const val MSG_GENERATE_CANCEL = 8
    const val MSG_SUPPORTS_GPU = 9
    const val MSG_GET_BACKEND_DIAGNOSTICS = 10
    const val MSG_RUN_GPU_PROBE = 11
    const val MSG_LAST_ERROR = 12

    const val MSG_REPLY = 100
    const val MSG_STREAM_TOKEN = 101
    const val MSG_STREAM_RESULT = 102

    const val EXTRA_OK = "ok"
    const val EXTRA_ERROR_CODE = "error_code"
    const val EXTRA_ERROR_DETAIL = "error_detail"
    const val EXTRA_SERVICE_EPOCH = "service_epoch"

    const val EXTRA_MODEL_ID = "model_id"
    const val EXTRA_MODEL_PATH = "model_path"
    const val EXTRA_MODEL_VERSION = "model_version"
    const val EXTRA_LAYER_LADDER = "layer_ladder"

    const val EXTRA_THREADS = "n_threads"
    const val EXTRA_THREADS_BATCH = "n_threads_batch"
    const val EXTRA_BATCH = "n_batch"
    const val EXTRA_UBATCH = "n_ubatch"
    const val EXTRA_CTX = "n_ctx"
    const val EXTRA_GPU_ENABLED = "gpu_enabled"
    const val EXTRA_GPU_LAYERS = "gpu_layers"
    const val EXTRA_GPU_BACKEND = "gpu_backend"
    const val EXTRA_STRICT_GPU_OFFLOAD = "strict_gpu_offload"
    const val EXTRA_KV_PRESET = "kv_preset"
    const val EXTRA_SAMPLING_TEMPERATURE = "sampling_temperature"
    const val EXTRA_SAMPLING_TOP_K = "sampling_top_k"
    const val EXTRA_SAMPLING_TOP_P = "sampling_top_p"
    const val EXTRA_SPECULATIVE_ENABLED = "speculative_enabled"
    const val EXTRA_SPECULATIVE_DRAFT_MODEL_ID = "speculative_draft_model_id"
    const val EXTRA_SPECULATIVE_DRAFT_MODEL_PATH = "speculative_draft_model_path"
    const val EXTRA_SPECULATIVE_DRAFT_MAX = "speculative_draft_max"
    const val EXTRA_SPECULATIVE_DRAFT_MIN = "speculative_draft_min"
    const val EXTRA_SPECULATIVE_DRAFT_GPU_LAYERS = "speculative_draft_gpu_layers"
    const val EXTRA_USE_MMAP = "use_mmap"
    const val EXTRA_USE_MLOCK = "use_mlock"
    const val EXTRA_N_KEEP = "n_keep"

    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_PROMPT = "prompt"
    const val EXTRA_MAX_TOKENS = "max_tokens"
    const val EXTRA_CACHE_KEY = "cache_key"
    const val EXTRA_CACHE_POLICY = "cache_policy"
    const val EXTRA_TOKEN = "token"

    const val EXTRA_RESULT_FINISH_REASON = "result_finish_reason"
    const val EXTRA_RESULT_TOKEN_COUNT = "result_token_count"
    const val EXTRA_RESULT_FIRST_TOKEN_MS = "result_first_token_ms"
    const val EXTRA_RESULT_TOTAL_MS = "result_total_ms"
    const val EXTRA_RESULT_CANCELLED = "result_cancelled"
    const val EXTRA_RESULT_PREFILL_MS = "result_prefill_ms"
    const val EXTRA_RESULT_DECODE_MS = "result_decode_ms"
    const val EXTRA_RESULT_TOKENS_PER_SEC = "result_tokens_per_sec"
    const val EXTRA_RESULT_PEAK_RSS_MB = "result_peak_rss_mb"
    const val EXTRA_RESULT_KV_PRESET = "result_kv_preset"

    const val EXTRA_RUNTIME_SUPPORTED = "runtime_supported"
    const val EXTRA_DRIVER_NAME = "driver_name"
    const val EXTRA_DRIVER_VERSION = "driver_version"
    const val EXTRA_BACKEND_DIAGNOSTICS_JSON = "backend_diagnostics_json"
    const val EXTRA_AVAILABLE_MODELS = "available_models"
    const val EXTRA_LAST_ERROR_CODE = "last_error_code"
    const val EXTRA_LAST_ERROR_DETAIL = "last_error_detail"

    const val EXTRA_PROBE_STATUS = "probe_status"
    const val EXTRA_PROBE_MAX_LAYERS = "probe_max_layers"
    const val EXTRA_PROBE_REASON = "probe_reason"
    const val EXTRA_PROBE_DETAIL = "probe_detail"
    const val EXTRA_BACKEND_PROFILE = "backend_profile"
}
