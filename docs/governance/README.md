# Docs Governance

Last updated: 2026-05-04

Docs governance has three layers:

1. `docs-drift`: checks canonical-vs-noncanonical command/process duplication policy.
2. `docs-health`: checks link integrity (markdown links + backtick path refs), status-field policy, required indexes, and evidence-retention policy from config.
3. `docs-accuracy`: checks feature-doc-code parity using `docs/governance/docs-accuracy-manifest.json` and emits a machine-readable drift report under the build output directory.

Contributor CRUD and naming/lifecycle rules live in `docs/governance/documentation-lifecycle.md`.

## Contracts

- Policy config: `config/devctl/docs-governance.json`
- Feature parity manifest: `docs/governance/docs-accuracy-manifest.json`
- Drift report output: generated under the build output directory as `docs-drift-report.json`

## Commands

```bash
python3 tools/devctl/main.py governance docs-drift
python3 tools/devctl/main.py governance docs-health
python3 tools/devctl/main.py governance docs-accuracy
python3 tools/devctl/main.py governance screenshot-inventory-check
```

## Drift Report Schema (v1)

The generated drift report file contains:

1. `schema`
2. `generated_at_utc`
3. `status`
4. `checks[]`
5. `violations[]`

Use this file in CI annotations or release-readiness summaries.

## Scope and Limits

Use these jobs as targeted signals, not a complete product-truth proof.

### `docs-drift`

What it catches:

1. Missing source-of-truth pointers in non-canonical docs.
2. Duplicate runnable command blocks in non-canonical docs.
3. Missing canonical docs required by policy.

What it misses:

1. Behavioral mismatches where docs exist but describe stale values.
2. Broken references outside the scoped file set.
3. Product/runtime correctness.

### `docs-health`

What it catches:

1. Broken local markdown links.
2. Broken local backtick path references.
3. Illegal `Status:` usage outside board/tickets.
4. Missing required index pointers and evidence-retention policy violations.

What it misses:

1. Semantic drift when links are valid but meaning is stale.
2. Evidence-note correctness beyond structural retention rules.
3. External URL quality/availability.

### `docs-accuracy`

What it catches:

1. Explicit doc/code marker parity for features defined in manifest.
2. Missing files or code globs in the declared parity surface.
3. Drift report output for CI/review.

What it misses:

1. Any feature not represented in the manifest.
2. Behavioral nuance not captured by marker strings.
3. Runtime validation; this is a static parity check.
