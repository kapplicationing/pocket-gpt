# AI Tester Trip Report — {{tester_id}} ({{tester_kind}})

- Device: {{device.label}} (api {{device.android_api}}, model {{device.model}})
- Build: commit {{build.commit}} on branch tip {{build.branch_tip}}, APK {{build.apk_path}}
- Window: {{timestamps.started_utc}} → {{timestamps.ended_utc}}

## PRD Workflows

| Workflow | Completed | Duration (s) | Blocker | Confusion notes |
|---|---|---|---|---|
| A — Offline quick answer | {{workflows.A.completed}} | {{workflows.A.duration_seconds}} | {{workflows.A.blocker}} | {{workflows.A.confusion_notes_joined}} |
| B — Local tool | {{workflows.B.completed}} | {{workflows.B.duration_seconds}} | {{workflows.B.blocker}} | {{workflows.B.confusion_notes_joined}} |
| C — Continuity + image | {{workflows.C.completed}} | {{workflows.C.duration_seconds}} | {{workflows.C.blocker}} | {{workflows.C.confusion_notes_joined}} |

## Failure-State Journeys (PROD-10 rows S-D, S-F, S-G)

| Journey | Recovered | Deterministic code | CTA taken | Duration (s) |
|---|---|---|---|---|
| Recovery NotReady→Ready | {{failure_states.recovery_not_ready.recovered}} | {{failure_states.recovery_not_ready.deterministic_code_seen}} | {{failure_states.recovery_not_ready.cta_path_taken}} | {{failure_states.recovery_not_ready.duration_seconds}} |
| Stuck send / timeout    | {{failure_states.stuck_send.recovered}} | {{failure_states.stuck_send.deterministic_code_seen}} | {{failure_states.stuck_send.cta_path_taken}} | {{failure_states.stuck_send.duration_seconds}} |
| Manifest outage         | {{failure_states.manifest_outage.recovered}} | {{failure_states.manifest_outage.deterministic_code_seen}} | {{failure_states.manifest_outage.cta_path_taken}} | {{failure_states.manifest_outage.duration_seconds}} |

## Advanced Controls

- Profiles visible: {{advanced_controls.profiles_visible}}
- GPU toggle observed: {{advanced_controls.gpu_toggle_observed}}
- Diagnostics export OK: {{advanced_controls.diagnostics_export_ok}}
- Keep-alive options visible: {{advanced_controls.keepalive_options_visible}}

## Summary

- S0 count: {{summary.s0_count}}
- S1 count: {{summary.s1_count}}
- Blockers: {{summary.blockers_joined}}
- Runtime confusion: {{summary.confusion_runtime_pct}} %
- Privacy confusion: {{summary.confusion_privacy_pct}} %

## Recommendation

**{{recommendation}}**
