# TurboQuant KV Cache Quantization Implementation Plan

Status note:

1. This is a runtime/R&D implementation plan, not part of the controlled Play Store MVP critical path.
2. Treat TurboQuant launch relevance as conditional: only bring it onto the prod path if a required-lane blocker is traced to it.

## Completion Status

| Phase | Description | Status |
|-------|-------------|--------|
| Phase 1 | WHT rotation, Q8_0/Q4_0 quantization, session management | COMPLETE |
| Phase 1.5 | Asymmetric K/V (KIVI principle), layer-adaptive protection | COMPLETE |
| Phase 2 | ULTRA/EXTREME presets (Q3_K/Q2_K), 2/3-bit codebooks, KIVI assessment | COMPLETE |
| Phase 3 | Correctness fixes, fallback safety, quality tests, estimator alignment | COMPLETE |
| Phase 4A | Deterministic F16 recovery, scratch wiring, diagnostics hardening | COMPLETE |
| Phase 4B | Experimental custom low-bit Lloyd-Max + QJL helper path | IN PROGRESS |

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement rotation-based KV cache quantization (TurboQuant algorithm) using ggml custom ops, replacing the current preset-to-standard-type mapping with actual data-oblivious rotation + Lloyd-Max codebook quantization.

**Architecture:** We implement TurboQuant as a custom operation layer in `pocket_llama.cpp` that intercepts KV cache writes. Before values are stored via `ggml_set_rows`, they pass through a rotation transform (precomputed random orthogonal matrix per layer), then are quantized using Lloyd-Max codebook centroids for the Beta distribution. Dequantization applies the inverse rotation. We store in existing Q8_0 blocks (BALANCED preset) or Q4_0 blocks (AGGRESSIVE preset), but the rotation step redistributes coordinate energy to make these standard quantizers near-optimal. This avoids modifying the ggml type system while achieving TurboQuant's theoretical gains.

**Tech Stack:** C/C++ (ggml custom ops, ARM NEON intrinsics), Kotlin (Android runtime layer), llama.cpp (vendored in `third_party/llama.cpp/`)

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `apps/mobile-android/src/main/cpp/turboquant.h` | Create | TurboQuant rotation matrix, codebook, and quantization API declarations |
| `apps/mobile-android/src/main/cpp/turboquant.c` | Create | Rotation matrix generation (QR), Lloyd-Max codebook, quantize/dequantize with rotation |
| `apps/mobile-android/src/main/cpp/turboquant_codebooks.h` | Create | Precomputed Lloyd-Max centroids for Beta distribution at 4-bit and 8-bit |
| `apps/mobile-android/src/main/cpp/pocket_llama.cpp` | Modify | Wire TurboQuant rotation into KV cache write path, manage per-layer rotation matrices |
| `apps/mobile-android/src/main/cpp/CMakeLists.txt` | Modify | Add turboquant.c to build |
| `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/RuntimeBridgeContracts.kt` | Modify | Add `TURBOQUANT_ROTATION` to `KvCacheMethod` enum |
| `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimeModelMemoryEstimator.kt` | Modify | Add rotation matrix memory overhead to estimate |
| `apps/mobile-android/src/main/cpp/test_turboquant.cpp` | Create | Native unit tests for rotation + quantization correctness |

---

### Task 1: Lloyd-Max Codebook Header

**Files:**
- Create: `apps/mobile-android/src/main/cpp/turboquant_codebooks.h`

The TurboQuant paper uses optimal Lloyd-Max quantizer centroids for the Beta(d/2, d/2) distribution. In high dimensions, this concentrates toward N(0, 1/d). We precompute centroids for 4-bit (16 levels) and 8-bit (256 levels) quantization of a standard normal distribution (the high-dimensional limit).

- [ ] **Step 1: Create the codebook header with precomputed centroids**

```c
// apps/mobile-android/src/main/cpp/turboquant_codebooks.h
#pragma once

// Lloyd-Max optimal quantizer centroids for N(0,1) distribution.
// High-dimensional limit of Beta(d/2, d/2) after rotation.
// 4-bit: 16 levels (for AGGRESSIVE preset mapping to Q4_0-like compression)
// 8-bit: 256 levels (for BALANCED preset mapping to Q8_0-like compression)
//
// Boundary[i] = midpoint between centroid[i] and centroid[i+1].
// Quantize: find i such that boundary[i-1] <= x < boundary[i].
// Dequantize: return centroid[i].

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// 4-bit Lloyd-Max centroids for N(0,1), 16 levels, symmetric
// Generated from standard Lloyd-Max algorithm on Gaussian distribution.
// Levels are symmetric: centroid[-i] = -centroid[i].
// We store only the positive half (8 values); negative half is mirrored.
static const float TQ_CENTROID_4BIT_POS[8] = {
    0.1284f, 0.3884f, 0.6568f, 0.9423f,
    1.2562f, 1.6180f, 2.0690f, 2.7326f,
};

// Decision boundaries for 4-bit (positive half, 7 boundaries + implicit 0 and +inf)
static const float TQ_BOUNDARY_4BIT_POS[7] = {
    0.2582f, 0.5224f, 0.7996f, 1.0993f,
    1.4371f, 1.8435f, 2.4008f,
};

// 8-bit Lloyd-Max: for Q8_0 blocks we use the existing Q8_0 uniform quantizer
// after rotation, which is near-optimal because rotated coordinates are
// approximately i.i.d. Gaussian. No custom codebook needed for 8-bit --
// the standard absmax uniform quantizer in Q8_0 achieves within 1.1x of
// Lloyd-Max for Gaussian inputs at 256 levels.
//
// For 4-bit we need the non-uniform codebook because Q4_0's uniform
// quantizer loses ~30% vs Lloyd-Max at only 16 levels.

// Number of positive levels for each bit width
#define TQ_LEVELS_4BIT 8

// Map a float value to a 4-bit quantized index (0-15).
// Input should be pre-normalized (zero mean, known scale).
static inline uint8_t tq_quantize_4bit(float x, float inv_scale) {
    float v = x * inv_scale;
    // Symmetric: index 0-7 = negative, 8-15 = positive
    if (v < 0.0f) {
        float av = -v;
        uint8_t idx = 0;
        for (int i = 0; i < 7; i++) {
            if (av >= TQ_BOUNDARY_4BIT_POS[i]) idx = (uint8_t)(i + 1);
        }
        return 7 - idx; // map: most negative = 0, zero-crossing = 7
    } else {
        uint8_t idx = 0;
        for (int i = 0; i < 7; i++) {
            if (v >= TQ_BOUNDARY_4BIT_POS[i]) idx = (uint8_t)(i + 1);
        }
        return 8 + idx; // map: zero-crossing = 8, most positive = 15
    }
}

// Dequantize a 4-bit index back to float (before inverse rotation).
static inline float tq_dequantize_4bit(uint8_t idx, float scale) {
    if (idx < 8) {
        return -TQ_CENTROID_4BIT_POS[7 - idx] * scale;
    } else {
        return TQ_CENTROID_4BIT_POS[idx - 8] * scale;
    }
}

#ifdef __cplusplus
}
#endif
```

- [ ] **Step 2: Commit**

```bash
git add apps/mobile-android/src/main/cpp/turboquant_codebooks.h
git commit -m "feat(turboquant): add Lloyd-Max codebook centroids for Gaussian distribution"
```

---

### Task 2: TurboQuant Core API Header

**Files:**
- Create: `apps/mobile-android/src/main/cpp/turboquant.h`

- [ ] **Step 1: Create the TurboQuant API header**

```c
// apps/mobile-android/src/main/cpp/turboquant.h
#pragma once

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// --- Rotation Matrix Management ---

// Opaque handle to a per-layer rotation context.
// Each layer gets its own random orthogonal matrix for decorrelating KV vectors.
typedef struct tq_layer_ctx tq_layer_ctx;

// Create a rotation context for a single layer.
// n_embd: embedding dimension (head_dim * n_head_kv for the full GQA dimension).
// seed: deterministic seed for reproducibility across load/unload cycles.
// Returns NULL on allocation failure.
tq_layer_ctx * tq_layer_ctx_create(int n_embd, uint64_t seed);

// Free a rotation context.
void tq_layer_ctx_free(tq_layer_ctx * ctx);

// --- Rotation Operations ---

// Apply forward rotation (before quantization): dst = R @ src.
// src and dst are float arrays of length n_embd.
// src and dst may alias.
void tq_rotate_forward(const tq_layer_ctx * ctx, const float * src, float * dst, int n_embd);

// Apply inverse rotation (after dequantization): dst = R^T @ src.
void tq_rotate_inverse(const tq_layer_ctx * ctx, const float * src, float * dst, int n_embd);

// --- Batch Operations ---

// Rotate + quantize a batch of vectors into Q8_0 format (BALANCED preset).
// src: [n_tokens, n_embd] float array (row-major).
// dst: [n_tokens * n_embd / QK8_0] block_q8_0 array.
// Rotation is applied per-vector, then standard Q8_0 quantization.
void tq_rotate_quantize_q8_0(
    const tq_layer_ctx * ctx,
    const float * src,
    void * dst,
    int n_tokens,
    int n_embd);

// Rotate + quantize into 4-bit using Lloyd-Max codebook (AGGRESSIVE preset).
// Uses the non-uniform codebook for near-optimal Gaussian quantization.
// dst: [n_tokens * n_embd / QK4_0] block_q4_0 array.
void tq_rotate_quantize_q4_0(
    const tq_layer_ctx * ctx,
    const float * src,
    void * dst,
    int n_tokens,
    int n_embd);

// Dequantize Q8_0 + inverse rotate a batch of vectors.
// src: block_q8_0 array, dst: [n_tokens, n_embd] float array.
void tq_dequantize_rotate_q8_0(
    const tq_layer_ctx * ctx,
    const void * src,
    float * dst,
    int n_tokens,
    int n_embd);

// Dequantize 4-bit Lloyd-Max + inverse rotate.
void tq_dequantize_rotate_q4_0(
    const tq_layer_ctx * ctx,
    const void * src,
    float * dst,
    int n_tokens,
    int n_embd);

// --- Session Context ---

// Manages rotation contexts for all layers in a model.
typedef struct tq_session tq_session;

// Create a session for n_layers, each with n_embd dimension.
// base_seed is mixed with layer index for per-layer seeds.
tq_session * tq_session_create(int n_layers, int n_embd, uint64_t base_seed);

// Free the session and all layer contexts.
void tq_session_free(tq_session * session);

// Get the layer context for a given layer index.
const tq_layer_ctx * tq_session_get_layer(const tq_session * session, int layer_idx);

// Get the memory footprint of all rotation matrices in bytes.
size_t tq_session_memory_bytes(const tq_session * session);

#ifdef __cplusplus
}
#endif
```

- [ ] **Step 2: Commit**

```bash
git add apps/mobile-android/src/main/cpp/turboquant.h
git commit -m "feat(turboquant): add TurboQuant rotation and quantization API header"
```

---

### Task 3: TurboQuant Core Implementation

**Files:**
- Create: `apps/mobile-android/src/main/cpp/turboquant.c`

This is the core algorithm. The rotation matrix is generated via QR decomposition of a random Gaussian matrix (the paper's prescribed approach). For mobile efficiency, we use a fixed-seed PRNG so the matrix is deterministic and doesn't need to be stored -- it's regenerated identically on each model load.

- [ ] **Step 1: Create the implementation file**

```c
// apps/mobile-android/src/main/cpp/turboquant.c
#include "turboquant.h"
#include "turboquant_codebooks.h"

#include <stdlib.h>
#include <string.h>
#include <math.h>

// --- PRNG (xoshiro256** for reproducible Gaussian matrix generation) ---

typedef struct {
    uint64_t s[4];
} tq_rng;

static inline uint64_t tq_rng_rotl(uint64_t x, int k) {
    return (x << k) | (x >> (64 - k));
}

static uint64_t tq_rng_next(tq_rng * rng) {
    const uint64_t result = tq_rng_rotl(rng->s[1] * 5, 7) * 9;
    const uint64_t t = rng->s[1] << 17;
    rng->s[2] ^= rng->s[0];
    rng->s[3] ^= rng->s[1];
    rng->s[1] ^= rng->s[2];
    rng->s[0] ^= rng->s[3];
    rng->s[2] ^= t;
    rng->s[3] = tq_rng_rotl(rng->s[3], 45);
    return result;
}

static void tq_rng_seed(tq_rng * rng, uint64_t seed) {
    // SplitMix64 seeding
    for (int i = 0; i < 4; i++) {
        seed += 0x9e3779b97f4a7c15ULL;
        uint64_t z = seed;
        z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL;
        z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL;
        rng->s[i] = z ^ (z >> 31);
    }
}

// Box-Muller transform for Gaussian samples
static float tq_rng_gaussian(tq_rng * rng) {
    uint64_t u1_raw = tq_rng_next(rng);
    uint64_t u2_raw = tq_rng_next(rng);
    double u1 = ((double)(u1_raw >> 11) + 0.5) / 9007199254740992.0; // (0,1)
    double u2 = ((double)(u2_raw >> 11)) / 9007199254740992.0;       // [0,1)
    return (float)(sqrt(-2.0 * log(u1)) * cos(6.283185307179586 * u2));
}

// --- Rotation Matrix via QR Decomposition (Modified Gram-Schmidt) ---

struct tq_layer_ctx {
    int n_embd;
    float * rotation_matrix; // [n_embd * n_embd], row-major, orthogonal
};

// Generate random orthogonal matrix via QR decomposition of Gaussian matrix.
// Uses modified Gram-Schmidt for numerical stability.
static void generate_orthogonal_matrix(float * R, int n, uint64_t seed) {
    tq_rng rng;
    tq_rng_seed(&rng, seed);

    // Fill with i.i.d. N(0,1)
    for (int i = 0; i < n * n; i++) {
        R[i] = tq_rng_gaussian(&rng);
    }

    // Modified Gram-Schmidt QR decomposition (in-place on columns)
    // R is row-major, so column j = R[j], R[n+j], R[2n+j], ...
    // For efficiency, we work on a transposed copy.
    float * col = (float *)malloc(n * sizeof(float));
    if (!col) return;

    for (int j = 0; j < n; j++) {
        // Extract column j
        for (int i = 0; i < n; i++) {
            col[i] = R[i * n + j];
        }

        // Orthogonalize against previous columns
        for (int k = 0; k < j; k++) {
            float dot = 0.0f;
            for (int i = 0; i < n; i++) {
                dot += col[i] * R[i * n + k];
            }
            for (int i = 0; i < n; i++) {
                col[i] -= dot * R[i * n + k];
            }
        }

        // Normalize
        float norm = 0.0f;
        for (int i = 0; i < n; i++) {
            norm += col[i] * col[i];
        }
        norm = sqrtf(norm);
        if (norm > 1e-10f) {
            float inv_norm = 1.0f / norm;
            for (int i = 0; i < n; i++) {
                col[i] *= inv_norm;
            }
        }

        // Write back column j
        for (int i = 0; i < n; i++) {
            R[i * n + j] = col[i];
        }
    }

    free(col);
}

// --- Public API ---

tq_layer_ctx * tq_layer_ctx_create(int n_embd, uint64_t seed) {
    tq_layer_ctx * ctx = (tq_layer_ctx *)calloc(1, sizeof(tq_layer_ctx));
    if (!ctx) return NULL;

    ctx->n_embd = n_embd;
    ctx->rotation_matrix = (float *)malloc((size_t)n_embd * n_embd * sizeof(float));
    if (!ctx->rotation_matrix) {
        free(ctx);
        return NULL;
    }

    generate_orthogonal_matrix(ctx->rotation_matrix, n_embd, seed);
    return ctx;
}

void tq_layer_ctx_free(tq_layer_ctx * ctx) {
    if (ctx) {
        free(ctx->rotation_matrix);
        free(ctx);
    }
}

void tq_rotate_forward(const tq_layer_ctx * ctx, const float * src, float * dst, int n_embd) {
    // dst = R @ src (matrix-vector multiply, R is row-major)
    const float * R = ctx->rotation_matrix;
    // Allow in-place: copy src if aliased
    float local_buf[512]; // stack buffer for small dimensions
    float * tmp = (n_embd <= 512) ? local_buf : (float *)malloc(n_embd * sizeof(float));
    if (!tmp) return;
    memcpy(tmp, src, n_embd * sizeof(float));

    for (int i = 0; i < n_embd; i++) {
        float sum = 0.0f;
        const float * row = R + i * n_embd;
        for (int j = 0; j < n_embd; j++) {
            sum += row[j] * tmp[j];
        }
        dst[i] = sum;
    }

    if (tmp != local_buf) free(tmp);
}

void tq_rotate_inverse(const tq_layer_ctx * ctx, const float * src, float * dst, int n_embd) {
    // dst = R^T @ src (transpose multiply)
    const float * R = ctx->rotation_matrix;
    float local_buf[512];
    float * tmp = (n_embd <= 512) ? local_buf : (float *)malloc(n_embd * sizeof(float));
    if (!tmp) return;
    memcpy(tmp, src, n_embd * sizeof(float));

    for (int j = 0; j < n_embd; j++) {
        float sum = 0.0f;
        for (int i = 0; i < n_embd; i++) {
            sum += R[i * n_embd + j] * tmp[i];
        }
        dst[j] = sum;
    }

    if (tmp != local_buf) free(tmp);
}

// --- Batch rotate + quantize (Q8_0) ---

// Q8_0 block format: ggml_half d (scale), int8_t qs[32]
// After rotation, coordinates are ~N(0, 1/d). Standard absmax Q8_0 is
// near-optimal for Gaussian inputs.

void tq_rotate_quantize_q8_0(
    const tq_layer_ctx * ctx,
    const float * src,
    void * dst,
    int n_tokens,
    int n_embd)
{
    float * rotated = (float *)malloc(n_embd * sizeof(float));
    if (!rotated) return;

    // Q8_0 block size = 32
    const int qk = 32;
    const int n_blocks_per_row = n_embd / qk;

    // dst is array of block_q8_0: { ggml_half d; int8_t qs[32]; }
    // sizeof(block_q8_0) = 2 + 32 = 34 bytes
    uint8_t * out = (uint8_t *)dst;

    for (int t = 0; t < n_tokens; t++) {
        const float * vec = src + t * n_embd;
        tq_rotate_forward(ctx, vec, rotated, n_embd);

        for (int b = 0; b < n_blocks_per_row; b++) {
            const float * block_src = rotated + b * qk;

            // Find absmax for this block
            float amax = 0.0f;
            for (int i = 0; i < qk; i++) {
                float av = fabsf(block_src[i]);
                if (av > amax) amax = av;
            }

            float d = amax / 127.0f;
            float id = (d != 0.0f) ? 127.0f / amax : 0.0f;

            // Write scale as fp16 (ggml_half = uint16_t)
            // Using a simple fp32->fp16 conversion
            union { float f; uint32_t u; } fu = { .f = d };
            uint16_t fp16_d;
            {
                uint32_t f32 = fu.u;
                uint32_t sign = (f32 >> 16) & 0x8000;
                int32_t exp = ((f32 >> 23) & 0xFF) - 127 + 15;
                uint32_t mant = (f32 >> 13) & 0x3FF;
                if (exp <= 0) { fp16_d = (uint16_t)sign; }
                else if (exp >= 31) { fp16_d = (uint16_t)(sign | 0x7C00); }
                else { fp16_d = (uint16_t)(sign | (exp << 10) | mant); }
            }

            // Write block: 2 bytes scale + 32 bytes quants
            memcpy(out, &fp16_d, 2);
            out += 2;
            for (int i = 0; i < qk; i++) {
                int v = (int)roundf(block_src[i] * id);
                if (v > 127) v = 127;
                if (v < -128) v = -128;
                *out++ = (uint8_t)(int8_t)v;
            }
        }
    }

    free(rotated);
}

// --- Batch dequantize Q8_0 + inverse rotate ---

void tq_dequantize_rotate_q8_0(
    const tq_layer_ctx * ctx,
    const void * src,
    float * dst,
    int n_tokens,
    int n_embd)
{
    float * dequantized = (float *)malloc(n_embd * sizeof(float));
    if (!dequantized) return;

    const int qk = 32;
    const int n_blocks_per_row = n_embd / qk;
    const uint8_t * in = (const uint8_t *)src;

    for (int t = 0; t < n_tokens; t++) {
        // Dequantize all blocks for this token
        for (int b = 0; b < n_blocks_per_row; b++) {
            // Read scale (fp16)
            uint16_t fp16_d;
            memcpy(&fp16_d, in, 2);
            in += 2;

            // fp16 -> fp32
            float d;
            {
                uint32_t sign = ((uint32_t)fp16_d & 0x8000) << 16;
                uint32_t exp = (fp16_d >> 10) & 0x1F;
                uint32_t mant = fp16_d & 0x3FF;
                uint32_t f32;
                if (exp == 0) { f32 = sign; }
                else if (exp == 31) { f32 = sign | 0x7F800000; }
                else { f32 = sign | ((exp - 15 + 127) << 23) | (mant << 13); }
                union { uint32_t u; float f; } fu = { .u = f32 };
                d = fu.f;
            }

            float * block_dst = dequantized + b * qk;
            for (int i = 0; i < qk; i++) {
                block_dst[i] = d * (float)(int8_t)(*in++);
            }
        }

        // Inverse rotate
        tq_rotate_inverse(ctx, dequantized, dst + t * n_embd, n_embd);
    }

    free(dequantized);
}

// --- Batch rotate + quantize (4-bit with Lloyd-Max codebook) ---

void tq_rotate_quantize_q4_0(
    const tq_layer_ctx * ctx,
    const float * src,
    void * dst,
    int n_tokens,
    int n_embd)
{
    float * rotated = (float *)malloc(n_embd * sizeof(float));
    if (!rotated) return;

    // Q4_0 block: ggml_half d (scale) + QK4_0/2 bytes (nibbles)
    // QK4_0 = 32, so 2 + 16 = 18 bytes per block
    const int qk = 32;
    const int n_blocks_per_row = n_embd / qk;
    uint8_t * out = (uint8_t *)dst;

    for (int t = 0; t < n_tokens; t++) {
        const float * vec = src + t * n_embd;
        tq_rotate_forward(ctx, vec, rotated, n_embd);

        for (int b = 0; b < n_blocks_per_row; b++) {
            const float * block_src = rotated + b * qk;

            // Find absmax
            float amax = 0.0f;
            for (int i = 0; i < qk; i++) {
                float av = fabsf(block_src[i]);
                if (av > amax) amax = av;
            }

            float d = amax / 2.7326f; // max centroid value
            float inv_d = (d != 0.0f) ? 1.0f / d : 0.0f;

            // Write scale as fp16
            union { float f; uint32_t u; } fu = { .f = d };
            uint16_t fp16_d;
            {
                uint32_t f32 = fu.u;
                uint32_t sign = (f32 >> 16) & 0x8000;
                int32_t exp = ((f32 >> 23) & 0xFF) - 127 + 15;
                uint32_t mant = (f32 >> 13) & 0x3FF;
                if (exp <= 0) { fp16_d = (uint16_t)sign; }
                else if (exp >= 31) { fp16_d = (uint16_t)(sign | 0x7C00); }
                else { fp16_d = (uint16_t)(sign | (exp << 10) | mant); }
            }
            memcpy(out, &fp16_d, 2);
            out += 2;

            // Quantize pairs of values into nibbles
            for (int i = 0; i < qk; i += 2) {
                uint8_t q0 = tq_quantize_4bit(block_src[i], inv_d);
                uint8_t q1 = tq_quantize_4bit(block_src[i + 1], inv_d);
                *out++ = (q1 << 4) | (q0 & 0x0F);
            }
        }
    }

    free(rotated);
}

void tq_dequantize_rotate_q4_0(
    const tq_layer_ctx * ctx,
    const void * src,
    float * dst,
    int n_tokens,
    int n_embd)
{
    float * dequantized = (float *)malloc(n_embd * sizeof(float));
    if (!dequantized) return;

    const int qk = 32;
    const int n_blocks_per_row = n_embd / qk;
    const uint8_t * in = (const uint8_t *)src;

    for (int t = 0; t < n_tokens; t++) {
        for (int b = 0; b < n_blocks_per_row; b++) {
            uint16_t fp16_d;
            memcpy(&fp16_d, in, 2);
            in += 2;

            float d;
            {
                uint32_t sign = ((uint32_t)fp16_d & 0x8000) << 16;
                uint32_t exp = (fp16_d >> 10) & 0x1F;
                uint32_t mant = fp16_d & 0x3FF;
                uint32_t f32;
                if (exp == 0) { f32 = sign; }
                else if (exp == 31) { f32 = sign | 0x7F800000; }
                else { f32 = sign | ((exp - 15 + 127) << 23) | (mant << 13); }
                union { uint32_t u; float f; } fu = { .u = f32 };
                d = fu.f;
            }

            float * block_dst = dequantized + b * qk;
            for (int i = 0; i < qk; i += 2) {
                uint8_t packed = *in++;
                block_dst[i]     = tq_dequantize_4bit(packed & 0x0F, d);
                block_dst[i + 1] = tq_dequantize_4bit(packed >> 4, d);
            }
        }

        tq_rotate_inverse(ctx, dequantized, dst + t * n_embd, n_embd);
    }

    free(dequantized);
}

// --- Session Management ---

struct tq_session {
    int n_layers;
    int n_embd;
    tq_layer_ctx ** layers;
};

tq_session * tq_session_create(int n_layers, int n_embd, uint64_t base_seed) {
    tq_session * session = (tq_session *)calloc(1, sizeof(tq_session));
    if (!session) return NULL;

    session->n_layers = n_layers;
    session->n_embd = n_embd;
    session->layers = (tq_layer_ctx **)calloc(n_layers, sizeof(tq_layer_ctx *));
    if (!session->layers) {
        free(session);
        return NULL;
    }

    for (int i = 0; i < n_layers; i++) {
        // Mix layer index into seed for per-layer uniqueness
        uint64_t layer_seed = base_seed ^ ((uint64_t)i * 0x9E3779B97F4A7C15ULL);
        session->layers[i] = tq_layer_ctx_create(n_embd, layer_seed);
        if (!session->layers[i]) {
            tq_session_free(session);
            return NULL;
        }
    }

    return session;
}

void tq_session_free(tq_session * session) {
    if (!session) return;
    if (session->layers) {
        for (int i = 0; i < session->n_layers; i++) {
            tq_layer_ctx_free(session->layers[i]);
        }
        free(session->layers);
    }
    free(session);
}

const tq_layer_ctx * tq_session_get_layer(const tq_session * session, int layer_idx) {
    if (!session || layer_idx < 0 || layer_idx >= session->n_layers) return NULL;
    return session->layers[layer_idx];
}

size_t tq_session_memory_bytes(const tq_session * session) {
    if (!session) return 0;
    // Each layer stores an n_embd x n_embd float matrix
    return (size_t)session->n_layers * session->n_embd * session->n_embd * sizeof(float);
}
```

- [ ] **Step 2: Commit**

```bash
git add apps/mobile-android/src/main/cpp/turboquant.c
git commit -m "feat(turboquant): implement rotation matrix generation, quantize, and dequantize"
```

---

### Task 4: Build System Integration

**Files:**
- Modify: `apps/mobile-android/src/main/cpp/CMakeLists.txt`

- [ ] **Step 1: Add turboquant.c to the native library build**

After the `add_library(pocket_llama ...)` block that lists pocket_llama.cpp, add turboquant.c:

```cmake
# In the pocket_llama library source list, add:
#   ${CMAKE_CURRENT_LIST_DIR}/turboquant.c
```

Find the `add_library(pocket_llama SHARED ...)` line and add turboquant.c to it.

- [ ] **Step 2: Verify the build compiles**

Run: `./gradlew :apps:mobile-android:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (Kotlin compilation, C++ compiles during APK assembly)

- [ ] **Step 3: Commit**

```bash
git add apps/mobile-android/src/main/cpp/CMakeLists.txt
git commit -m "build(android): add turboquant.c to native library compilation"
```

---

### Task 5: Wire TurboQuant into pocket_llama.cpp Model Load

**Files:**
- Modify: `apps/mobile-android/src/main/cpp/pocket_llama.cpp`

The key integration point: when `kvCacheMethodCode == 1` (TURBOQUANT) AND the preset is BALANCED or AGGRESSIVE, we create a `tq_session` during model load and store it as a global. During `generate_locked`, we don't need to modify the decode path because llama.cpp handles KV cache quantization internally via `type_k`/`type_v`. Instead, TurboQuant's rotation is applied as a **pre-processing step** on the KV vectors before they enter the cache.

However, since llama.cpp's `llama_decode()` handles KV cache writes internally, we need to hook into the computation graph. The cleanest approach is to modify `cpy_k`/`cpy_v` in `llama-kv-cache.cpp` to insert rotation before `ggml_set_rows`.

- [ ] **Step 1: Add TurboQuant session globals and include**

At the top of `pocket_llama.cpp`, after existing includes, add:

```cpp
#include "turboquant.h"

// TurboQuant session (per-model rotation matrices)
static tq_session * g_tq_session = nullptr;
static bool g_tq_rotation_enabled = false;
```

- [ ] **Step 2: Create TurboQuant session during model load**

In `nativeLoadModel`, after `g_context = llama_init_from_model(...)` succeeds (around line 3187), add:

```cpp
// Create TurboQuant rotation session when TURBOQUANT method is active
// and preset is not SAFE (SAFE = F16, no rotation needed)
if (g_tq_session) {
    tq_session_free(g_tq_session);
    g_tq_session = nullptr;
}
g_tq_rotation_enabled = false;

if (uses_turboquant_kv_cache(kvCacheMethodCode) && quantized_kv_enabled) {
    const int n_layer = llama_model_n_layer(g_model);
    const int n_embd_head_k = llama_model_n_embd_head_k(g_model);
    const int n_head_kv = llama_model_n_head_kv(g_model);
    const int n_embd_kv = n_embd_head_k * n_head_kv;

    // Use model file size as seed for deterministic rotation across sessions
    g_tq_session = tq_session_create(n_layer, n_embd_kv, g_model_size_bytes);
    if (g_tq_session) {
        g_tq_rotation_enabled = true;
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "TURBOQUANT|rotation_enabled=true|layers=%d|embd_kv=%d|matrix_mb=%.1f",
            n_layer, n_embd_kv,
            tq_session_memory_bytes(g_tq_session) / (1024.0 * 1024.0));
    }
}
```

- [ ] **Step 3: Free TurboQuant session during model unload**

In the unload/cleanup path (where `g_model` is freed), add:

```cpp
if (g_tq_session) {
    tq_session_free(g_tq_session);
    g_tq_session = nullptr;
    g_tq_rotation_enabled = false;
}
```

- [ ] **Step 4: Commit**

```bash
git add apps/mobile-android/src/main/cpp/pocket_llama.cpp
git commit -m "feat(jni): create and manage TurboQuant rotation session during model lifecycle"
```

---

### Task 6: Hook Rotation into KV Cache Write Path

**Files:**
- Modify: `third_party/llama.cpp/src/llama-kv-cache.cpp`
- Modify: `third_party/llama.cpp/src/llama-kv-cache.h`

This is the critical integration. We add an optional rotation callback to `llama_kv_cache` that is invoked before `ggml_set_rows` in `cpy_k` and `cpy_v`. When a rotation callback is registered, `cpy_k`/`cpy_v` insert a `ggml_map_custom1` node into the computation graph that applies the rotation before the values are quantized and stored.

- [ ] **Step 1: Add rotation hook typedef and setter to llama-kv-cache.h**

In `llama-kv-cache.h`, inside `class llama_kv_cache`, add:

```cpp
// TurboQuant rotation hook: if set, applied to K/V before cache write.
// The callback receives the tensor to rotate and the layer index via userdata.
using kv_rotation_fn = void (*)(
    struct ggml_tensor * dst,
    const struct ggml_tensor * src,
    int ith, int nth,
    void * userdata);

void set_kv_rotation_hook(kv_rotation_fn fn, void * userdata);
```

And add private members:

```cpp
kv_rotation_fn rotation_hook = nullptr;
void * rotation_hook_userdata = nullptr;
```

- [ ] **Step 2: Implement setter in llama-kv-cache.cpp**

```cpp
void llama_kv_cache::set_kv_rotation_hook(kv_rotation_fn fn, void * userdata) {
    rotation_hook = fn;
    rotation_hook_userdata = userdata;
}
```

- [ ] **Step 3: Insert rotation into cpy_k**

In `llama_kv_cache::cpy_k`, just before `return ggml_set_rows(ctx, k, k_cur, k_idxs);` (line 1107), add:

```cpp
    // Apply TurboQuant rotation if hook is registered
    if (rotation_hook) {
        k_cur = ggml_map_custom1(ctx, k_cur, rotation_hook, GGML_N_TASKS_MAX, rotation_hook_userdata);
    }

    return ggml_set_rows(ctx, k, k_cur, k_idxs);
```

- [ ] **Step 4: Insert rotation into cpy_v (non-transposed FA path)**

In `llama_kv_cache::cpy_v`, in the `if (!v_trans)` branch, just before `return ggml_set_rows(ctx, v, v_cur, v_idxs);` (line 1142), add:

```cpp
        if (rotation_hook) {
            v_cur = ggml_map_custom1(ctx, v_cur, rotation_hook, GGML_N_TASKS_MAX, rotation_hook_userdata);
        }

        return ggml_set_rows(ctx, v, v_cur, v_idxs);
```

- [ ] **Step 5: Commit**

```bash
git add third_party/llama.cpp/src/llama-kv-cache.cpp third_party/llama.cpp/src/llama-kv-cache.h
git commit -m "feat(llama.cpp): add optional rotation hook to KV cache write path"
```

---

### Task 7: Register Rotation Hook in pocket_llama.cpp

**Files:**
- Modify: `apps/mobile-android/src/main/cpp/pocket_llama.cpp`

- [ ] **Step 1: Implement the rotation callback**

Add this static function before `nativeLoadModel`:

```cpp
// TurboQuant rotation callback for ggml_map_custom1.
// userdata points to a tq_session. The layer index is encoded in the tensor name.
static void turboquant_rotation_callback(
    struct ggml_tensor * dst,
    const struct ggml_tensor * src,
    int ith,
    int nth,
    void * userdata)
{
    if (!userdata || !src || !dst) return;
    tq_session * session = (tq_session *)userdata;

    const int64_t n_embd = src->ne[0]; // embedding dimension (row length)
    const int64_t n_rows  = src->ne[1]; // number of tokens/rows

    // Process rows in parallel across threads
    const int64_t row_start = (ith * n_rows) / nth;
    const int64_t row_end   = ((ith + 1) * n_rows) / nth;

    // Use layer 0 rotation for all layers in this initial implementation.
    // TODO: pass layer index through tensor metadata for per-layer rotation.
    const tq_layer_ctx * layer = tq_session_get_layer(session, 0);
    if (!layer) return;

    for (int64_t r = row_start; r < row_end; r++) {
        const float * src_row = (const float *)((const char *)src->data + r * src->nb[1]);
        float * dst_row = (float *)((char *)dst->data + r * dst->nb[1]);
        tq_rotate_forward(layer, src_row, dst_row, (int)n_embd);
    }
}
```

- [ ] **Step 2: Register the hook after context creation**

After `g_tq_rotation_enabled = true;` in the model load path, add:

```cpp
        // Register rotation hook with the KV cache
        auto * memory = llama_get_memory(g_context);
        if (memory) {
            // Cast to kv_cache to access the hook
            auto * kv_cache = dynamic_cast<llama_kv_cache *>(memory);
            if (kv_cache) {
                kv_cache->set_kv_rotation_hook(turboquant_rotation_callback, g_tq_session);
            }
        }
```

- [ ] **Step 3: Commit**

```bash
git add apps/mobile-android/src/main/cpp/pocket_llama.cpp
git commit -m "feat(jni): register TurboQuant rotation hook with KV cache on model load"
```

---

### Task 8: Add TURBOQUANT_ROTATION to Kotlin Contracts

**Files:**
- Modify: `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/RuntimeBridgeContracts.kt`

Currently `KvCacheMethod.TURBOQUANT` maps to standard quantization types. We need a way to distinguish "TURBOQUANT with rotation" from the current compatibility mapping. However, since rotation is now always applied when TURBOQUANT + quantized preset is active, no contract change is needed -- the C++ layer handles it transparently.

Instead, update the memory estimator to account for rotation matrix overhead.

- Modify: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimeModelMemoryEstimator.kt`

- [ ] **Step 1: Add rotation matrix memory overhead to the estimator**

In `RuntimeModelMemoryEstimator.estimate()`, after computing `kvCacheBytes`, add rotation matrix overhead:

```kotlin
// TurboQuant rotation matrices: one n_embd_kv x n_embd_kv float matrix per layer.
// Only allocated when quantized KV is active (BALANCED or AGGRESSIVE preset).
val rotationMatrixBytes = if (
    kvCacheMethod != KvCacheMethod.AUTO || preset != KvCacheMethodPreset.SAFE
) {
    val nEmbdKv = (headCountKv * keyLength).toLong()
    layerCount.toLong() * nEmbdKv * nEmbdKv * 4L // float32
} else {
    0L
}
```

Update the total:

```kotlin
val estimatedBytes = ((modelFileSizeBytes + kvCacheBytes + computeBufferBytes + rotationMatrixBytes).toDouble() * METADATA_OVERHEAD_MULTIPLIER)
    .toLong()
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :packages:app-runtime:test --tests "com.pocketagent.runtime.*" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimeModelMemoryEstimator.kt
git commit -m "fix(runtime): account for TurboQuant rotation matrix memory in estimator"
```

---

### Task 9: Native Unit Tests for TurboQuant

**Files:**
- Create: `apps/mobile-android/src/main/cpp/test_turboquant.cpp`

- [ ] **Step 1: Write native test that verifies rotation orthogonality and roundtrip**

```cpp
// apps/mobile-android/src/main/cpp/test_turboquant.cpp
// Compile separately: not part of the Android library, used for host testing.
// Build: g++ -std=c++17 -O2 -o test_turboquant test_turboquant.cpp turboquant.c -lm

#include <cstdio>
#include <cmath>
#include <cassert>
#include "turboquant.h"

static bool approx_equal(float a, float b, float tol = 1e-4f) {
    return fabsf(a - b) < tol;
}

static void test_rotation_orthogonality() {
    printf("test_rotation_orthogonality... ");
    const int n = 64;
    tq_layer_ctx * ctx = tq_layer_ctx_create(n, 42);
    assert(ctx);

    // R @ R^T should be identity
    float vec[64], rotated[64], recovered[64];
    for (int i = 0; i < n; i++) vec[i] = (i == 0) ? 1.0f : 0.0f; // unit vector

    tq_rotate_forward(ctx, vec, rotated, n);
    tq_rotate_inverse(ctx, rotated, recovered, n);

    for (int i = 0; i < n; i++) {
        assert(approx_equal(vec[i], recovered[i], 1e-3f));
    }

    // Rotation should preserve norm
    float norm_orig = 0, norm_rot = 0;
    for (int i = 0; i < n; i++) {
        norm_orig += vec[i] * vec[i];
        norm_rot += rotated[i] * rotated[i];
    }
    assert(approx_equal(sqrtf(norm_orig), sqrtf(norm_rot), 1e-3f));

    tq_layer_ctx_free(ctx);
    printf("PASS\n");
}

static void test_q8_0_roundtrip() {
    printf("test_q8_0_roundtrip... ");
    const int n_embd = 128;
    const int n_tokens = 4;
    tq_layer_ctx * ctx = tq_layer_ctx_create(n_embd, 123);
    assert(ctx);

    float src[n_tokens * n_embd];
    for (int i = 0; i < n_tokens * n_embd; i++) {
        src[i] = sinf((float)i * 0.1f) * 2.0f;
    }

    // Q8_0: 34 bytes per block of 32 values
    const int blocks_per_row = n_embd / 32;
    uint8_t quantized[n_tokens * blocks_per_row * 34];

    tq_rotate_quantize_q8_0(ctx, src, quantized, n_tokens, n_embd);

    float recovered[n_tokens * n_embd];
    tq_dequantize_rotate_q8_0(ctx, quantized, recovered, n_tokens, n_embd);

    // Check roundtrip error is small (Q8_0 should have < 1% error)
    float max_err = 0;
    float max_val = 0;
    for (int i = 0; i < n_tokens * n_embd; i++) {
        float err = fabsf(src[i] - recovered[i]);
        if (err > max_err) max_err = err;
        if (fabsf(src[i]) > max_val) max_val = fabsf(src[i]);
    }
    float relative_err = max_err / (max_val + 1e-8f);
    printf("max_err=%.6f relative=%.4f%% ", max_err, relative_err * 100);
    assert(relative_err < 0.02f); // < 2% relative error for Q8_0

    tq_layer_ctx_free(ctx);
    printf("PASS\n");
}

static void test_session_lifecycle() {
    printf("test_session_lifecycle... ");
    tq_session * s = tq_session_create(32, 128, 999);
    assert(s);
    assert(tq_session_get_layer(s, 0) != NULL);
    assert(tq_session_get_layer(s, 31) != NULL);
    assert(tq_session_get_layer(s, 32) == NULL); // out of bounds
    assert(tq_session_memory_bytes(s) == 32UL * 128 * 128 * 4); // 32 layers * 128^2 * float
    tq_session_free(s);
    printf("PASS\n");
}

static void test_deterministic_rotation() {
    printf("test_deterministic_rotation... ");
    const int n = 64;
    float vec[64];
    for (int i = 0; i < n; i++) vec[i] = (float)i / n;

    float out1[64], out2[64];

    tq_layer_ctx * ctx1 = tq_layer_ctx_create(n, 42);
    tq_rotate_forward(ctx1, vec, out1, n);
    tq_layer_ctx_free(ctx1);

    tq_layer_ctx * ctx2 = tq_layer_ctx_create(n, 42); // same seed
    tq_rotate_forward(ctx2, vec, out2, n);
    tq_layer_ctx_free(ctx2);

    for (int i = 0; i < n; i++) {
        assert(out1[i] == out2[i]); // exact equality -- same seed, same PRNG
    }
    printf("PASS\n");
}

int main() {
    printf("=== TurboQuant Tests ===\n");
    test_rotation_orthogonality();
    test_q8_0_roundtrip();
    test_session_lifecycle();
    test_deterministic_rotation();
    printf("=== All tests passed ===\n");
    return 0;
}
```

- [ ] **Step 2: Build and run the test on host**

Run:
```bash
cd apps/mobile-android/src/main/cpp
gcc -std=c11 -O2 -c turboquant.c -o /tmp/turboquant.o -lm
g++ -std=c++17 -O2 -o /tmp/test_turboquant test_turboquant.cpp /tmp/turboquant.o -lm
/tmp/test_turboquant
```

Expected:
```
=== TurboQuant Tests ===
test_rotation_orthogonality... PASS
test_q8_0_roundtrip... max_err=... relative=...% PASS
test_session_lifecycle... PASS
test_deterministic_rotation... PASS
=== All tests passed ===
```

- [ ] **Step 3: Commit**

```bash
git add apps/mobile-android/src/main/cpp/test_turboquant.cpp
git commit -m "test(turboquant): add native unit tests for rotation, roundtrip, and session"
```

---

### Task 10: Integration Test -- End-to-End Verification

**Files:**
- No new files; this task verifies the full stack compiles and the Android app builds.

- [ ] **Step 1: Run the full Kotlin test suite**

Run: `./gradlew :packages:native-bridge:test :packages:app-runtime:test :apps:mobile-android:testDebugUnitTest 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Build the debug APK (triggers C++ compilation)**

Run: `./gradlew :apps:mobile-android:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL (this compiles the native library including turboquant.c)

- [ ] **Step 3: Commit any remaining fixups**

If any compilation issues arose, fix and commit.

---

## Performance Notes

**Rotation matrix memory overhead:** For a 32-layer model with 128-dim KV heads and 8 KV heads (n_embd_kv = 1024), each rotation matrix is 1024 x 1024 x 4 bytes = 4 MB. For 32 layers: 128 MB. This is significant.

**Mitigation strategies (future work, not in this plan):**
1. Use Hadamard rotation (no stored matrix, O(n log n) transform) instead of random orthogonal
2. Share rotation matrix across layers (reduces to 4 MB total)
3. Use block-diagonal rotation (e.g., 128x128 blocks) to reduce from O(n^2) to O(n * block_size)

**For this initial implementation:** Start with shared rotation across all layers (already handled by using layer 0 in the callback). This gives 1 matrix of 4 MB instead of 128 MB.

---

## Phase 3 Status (2026-03-30)

Phase 3 addressed correctness fixes, quality improvements, and test gaps identified during expert review.

### Completed
- **T1** (CRITICAL): Fixed read-path rotation — added Q rotation and output inverse rotation for mathematically correct attention
- **T2** (CRITICAL): Added F16 fallback when rotation session creation fails
- **T3** (IMPORTANT): Switched Q4_0 to sigma-based scaling for Lloyd-Max codebook alignment
- **T4** (IMPORTANT): Memory estimator now mirrors C++ small-model safety clamp
- **T5** (MODERATE): Added inner product distortion tests validating attention metric preservation
- **T6** (MODERATE): Added kurtosis validation test confirming WHT produces near-Gaussian coordinates
- **T7** (LOW): Pre-allocated scratch buffers are now wired into session-backed batch helpers
- **T8** (LOW): Updated KIVI assessment and plan docs

### Phase 4 Progress
- Hook-registration failure now destroys the partially initialized quantized context and retries exactly once with F16 KV types
- TurboQuant diagnostics now surface the effective mode and fallback reason in backend diagnostics JSON
- Test-only failpoints were added for session allocation failure, unsupported rotation, and hook registration failure via environment flags
- Experimental custom 3-bit and 2-bit Lloyd-Max batch helpers now exist beside the stable ggml-backed production presets
- Experimental QJL residual encode/decode helpers now exist for benchmarking and future runtime wiring, but they are not yet part of the shipping ggml KV cache path

### Deferred to Later Runtime Integration
- Wire the experimental low-bit Lloyd-Max and QJL helpers into a live custom KV storage path
- NEON intrinsics for WHT butterfly (performance optimization)
- Multiple SRHT rounds for better concentration bounds

---

## Future Improvements (Not In Scope)

- [ ] Per-layer rotation matrices with layer index passed through tensor metadata
- [ ] Hadamard rotation for O(n log n) and zero storage
- [ ] ARM NEON vectorized rotation kernel
- [ ] Mixed-precision: 3-bit for outlier channels, 2-bit for rest (paper's 2.5-bit mode)
- [ ] Entropy coding for additional 5% compression
