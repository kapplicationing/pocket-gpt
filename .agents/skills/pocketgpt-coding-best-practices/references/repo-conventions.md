# PocketGPT Repo Conventions

## Consistent Patterns

- Core domain files stay tiny and portable.
- Runtime failures are modeled with sealed result types.
- Model policy is descriptor-driven, not branch-driven.
- Runtime config/env parsing is centralized and catalog-aware.
- Persistence-to-UI translation is isolated in mappers.
- Stateful workflows live in coordinators or use cases.
- Composition roots and boundary tests guard dependency direction.
- Reusable Maestro helper flows are preferred over copy-paste.
- Stable `id:` selectors and semantic scenario names are preferred in UI automation.

## Anti-Patterns To Avoid

- `RuntimeOrchestrator`, `AndroidMvpContainer`, `AppRuntimeDependencies`, `ChatApp`, or similar files absorbing too many responsibilities.
- Startup or readiness logic based on ad hoc string matching scattered across files.
- Legacy string protocols living beside typed contracts without a clear migration boundary.
- Duplicate report, artifact, or flow-discovery helpers.
- UI files carrying task-note comments or implementation breadcrumbs.
- Broad smoke tests or benchmark flows that bundle too many unrelated scenarios.
- Mutable global state that behaves like a service locator.

## Refactor Heuristics

- If a branch keys off a model ID, routing mode, or message text, push it into a descriptor or classifier.
- If a composable or activity owns permissions, downloads, voice, navigation, and modal state, split orchestration out.
- If a class has both state mutation and policy decisions, separate them.
- If a helper is copied across tools, make it shared.
- Prefer one typed source of truth over multiple string conventions.
