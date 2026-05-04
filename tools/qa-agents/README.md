# QA Agents

## AI Human-Proxy Moderator

Use `tools/qa-agents/prepare_human_proxy_bundle.py` as the entrypoint for a sub-agent or reviewer that needs a complete human-like usability evaluation bundle.

Typical flow:

```bash
python3 tools/qa-agents/prepare_human_proxy_bundle.py --tester cloud-1 --run
```

That command:

1. Reuses `tools/qa-agents/run_ai_tester.py` for deterministic capture when `--run` is set.
2. Finds the artifact root for the tester.
3. Creates `tmp/qa-agents/<tester>/<stamp>/human-proxy-bundle/` with:
   - `moderator-prompt.md`
   - `workflow-checklist.md`
   - `bundle-manifest.json`
   - `trip_report.schema.json`
   - `trip_report.template.md`

The generated prompt points the reviewer at:

- the artifact root for logs, JUnit, screenshots, and logcat
- the flow-truth file
- the seeded report flow via `tools/qa-agents/fill_trip_from_skeleton.py`
- the final aggregate step via `tools/qa-agents/aggregate_wp13.py --packet-kind ai-human-proxy`

If deterministic artifacts already exist, skip the rerun and point at them directly:

```bash
python3 tools/qa-agents/prepare_human_proxy_bundle.py \
  --tester cloud-1 \
  --artifacts-root tmp/qa-agents/cloud-1/<stamp>
```

For controlled-MVP fallback closure, the reviewer packet stays explicitly labeled
`AI human-proxy` and writes to
`docs/operations/evidence/wp-13/2026-05-03-wp13-packet-ai-human-proxy.md`.
