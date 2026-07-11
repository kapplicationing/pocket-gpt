# Android UI RenderThread evidence — 2026-07-11

This ledger preserves the small, reviewable facts behind the corresponding
[RCA](../../../architecture/performance/android-ui-renderthread-rca-2026-07-11.md).
It intentionally excludes the 26.31 MB raw trace because UI traces can carry
device-specific data and are too large for the source tree.

## Provenance

- Device: Samsung SM-A515F, Android 13, 60 Hz
- App: native-enabled, nondebuggable benchmark APK
- Journey: settings navigation with inference unloaded
- Thermal status: 0 before and after
- Trace processor: Perfetto v57.2-da1d152cf
- Raw trace SHA-256: `74d7d518d72faa9707c751cc05910093523ea2023cb5948ffea3a02201295e1c`
- Trace health: 45 negative-timestamp packets were dropped

The runtime, download, and voice conditions were manually inspected during this
diagnostic capture. This predates the current fail-closed summary schema and is
not acceptance evidence.

## Retained artifacts

- `trace-queries.sql`: the exact trace-processor query.
- `trace-query-results.csv`: the query output used by the RCA.
- `device-gfx-manifest.json`: sanitized device, thermal, and `gfxinfo` facts.

To reproduce against a retained copy of the raw trace:

```bash
trace_processor_shell query -f trace-queries.sql settings-nav.perfetto-trace
```

Verify the trace hash first. A different hash is different evidence.
