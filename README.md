# PocketAgent

Offline, privacy-first AI assistant for Android — runs large language models directly on your phone.

## Features

- **Offline-first** — all AI processing happens locally, no cloud dependencies
- **Streaming responses** — real-time token-by-token display
- **Model routing** — automatic model selection based on device state (0.8B/2B)
- **Performance profiles** — BALANCED / FAST / BATTERY modes
- **GPU acceleration** — OpenCL/Hexagon backend support
- **Local tools** — date/time, notes lookup, web search
- **Memory** — persistent context across sessions
- **Image Q&A** — vision-capable multimodal inference

## Tech Stack

| Layer | Technology |
|-------|------------|
| UI | Jetpack Compose, Kotlin |
| Runtime | llama.cpp (JNI) |
| Architecture | Modular monolith (KMP) |
| Testing | Maestro, JUnit, Kotest |

## Supported Models

- Qwen2.5-0.5B / 0.8B / 2B (Q4_K_M, Q8_0)
- SmolLM2-135M / 360M / 1.7B
- Phi3.5-mini-instruct
- Gemma 4 E2B-it

## Quick Start

```bash
# Clone and enter directory
git clone https://github.com/your-org/pocket-gpt.git
cd pocket-gpt

# Run tests
./gradlew test

# Build debug APK
./gradlew :apps/mobile-android:assembleDebug
```

**Requirements:** Android SDK 34+, Kotlin 1.9+, JDK 17+

For full setup including device testing, see [scripts/dev/README.md](scripts/dev/README.md).

## Architecture

### Module Dependency Graph

```mermaid
flowchart LR
  CoreDomain["packages/core-domain"]
  InferenceAdapters["packages/inference-adapters"]
  ToolRuntime["packages/tool-runtime"]
  MemoryPkg["packages/memory"]
  NativeBridge["packages/native-bridge"]
  AppRuntime["packages/app-runtime"]
  MobileAndroid["apps/mobile-android"]
  MobileHost["apps/mobile-android-host"]

  InferenceAdapters --> CoreDomain
  NativeBridge --> InferenceAdapters
  AppRuntime --> CoreDomain
  AppRuntime --> InferenceAdapters
  AppRuntime --> ToolRuntime
  AppRuntime --> MemoryPkg
  AppRuntime --> NativeBridge
  MobileAndroid --> AppRuntime
  MobileAndroid --> NativeBridge
  MobileHost --> AppRuntime
  MobileHost --> NativeBridge
```

### Request Flow

When a user sends a message:

```mermaid
sequenceDiagram
    participant User
    participant UI as ChatViewModel
    participant Runtime as AppRuntime
    participant Router as InferenceAdapters
    participant Tools as ToolRuntime
    participant Memory as Memory
    participant Bridge as NativeBridge
    participant LLM as llama.cpp

    User->>UI: Send message
    UI->>Runtime: execute(request)
    Runtime->>Memory: Prune & retrieve context
    Memory-->>Runtime: Enriched prompt
    Runtime->>Router: Resolve model (routing policy)
    Router-->>Runtime: Selected model
    Runtime->>Tools: Parse tool calls
    Tools-->>Runtime: Validated tools
    Runtime->>Bridge: Forward to inference
    Bridge->>LLM: Run inference (JNI)
    LLM-->>Bridge: Stream tokens
    Bridge-->>Runtime: Token stream
    Runtime-->>UI: Stream to UI
    UI-->>User: Display response
```

### Key Responsibilities

| Module | Responsibility |
|--------|----------------|
| `core-domain` | Domain models, interfaces, contracts |
| `inference-adapters` | Model selection policy, runtime abstraction |
| `tool-runtime` | Tool schema validation, execution |
| `memory` | Context pruning, retrieval, persistence |
| `native-bridge` | JNI bindings to llama.cpp |
| `app-runtime` | Orchestration, startup guards, benchmarks |
| `mobile-android` | Compose UI, ViewModels, Android integration |

## Directory Layout

| Path | Purpose |
|------|---------|
| `apps/mobile-android/` | Android app module |
| `apps/mobile-android-host/` | Host/JVM smoke tests |
| `packages/app-runtime/` | Orchestration layer |
| `packages/native-bridge/` | JNI + llama.cpp runtime |
| `packages/core-domain/` | Shared domain contracts |
| `packages/tool-runtime/` | Local tool execution |
| `packages/memory/` | Memory & retrieval |
| `scripts/dev/` | Dev/test entrypoints |
| `tests/maestro/` | E2E mobile flows |

## Contributing

Contributions are welcome. Please ensure tests pass before submitting PRs:

```bash
# Run unit tests
bash scripts/dev/test.sh fast

# Run full test suite
bash scripts/dev/test.sh merge
```

## License

MIT License — see LICENSE file (to be added).

---

For detailed documentation, see [`docs/`](docs/).
