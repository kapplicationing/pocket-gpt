# Source of Truth Matrix

Last updated: 2026-04-26

Use this matrix before editing docs. Each area has one canonical owner doc.

| Area | Canonical Doc | Owner | Notes |
|---|---|---|---|
| Runtime + architecture context | `docs/architecture/system-context.md` | Engineering | System boundaries and stream/runtime contract overview |
| Product contract (MVP) | `docs/prd/phase-0-prd.md` | Product | Broad MVP scope and acceptance contracts; current launch-claim lock is narrower and must be read with launch-program canon |
| Implemented user behavior | `docs/ux/implemented-behavior-reference.md` | Product + Android | Must match shipped UI/runtime behavior |
| Privacy + security claims | `docs/security/privacy-model.md` | Product + Security | Only implemented controls are publishable |
| Testing docs entrypoint | `docs/testing/README.md` | Engineering + QA | Navigation layer for canonical testing docs and runbooks |
| Test strategy + release gates | `docs/testing/test-strategy.md` | Engineering + QA | Canonical test process and gate contract |
| Command contract | `scripts/dev/README.md` | Engineering | Canonical runnable commands |
| Launch program + dependencies | `docs/operations/play-store-launch-program.md` | Product + Tech Lead | Canonical launch-scope lock, execution ordering, and claim boundary from blockers through Play Store submission |
| Active execution status | `docs/operations/execution-board.md` | Product Ops | Only mutable status board |
| Active ticket specs | `docs/operations/tickets/` | Functional owners | Task-level acceptance + delivery |
| Evidence retention/index | `docs/operations/evidence/index.md` | Product Ops + QA | Evidence inventory and prune policy |
| Documentation drift tracking | `docs/start-here/documentation-drift-register.md` | Product + Engineering | Open drift risks and closure state |

## Rule

If a change touches one area above, update the canonical doc in the same PR.
