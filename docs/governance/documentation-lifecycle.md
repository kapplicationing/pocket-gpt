# Documentation CRUD and Lifecycle Rules

Last updated: 2026-05-04

Use this guide when creating, updating, renaming, archiving, or deleting repository docs.

## Primary signifiers

Use repository structure as the first signifier of meaning:

1. Path and directory decide the lifecycle class first.
2. File name decides the document purpose second.
3. Section `README.md` files explain what belongs in that subtree and what does not.
4. Inline headers or banners are optional and should only be added when docs with different purposes must live close together or when generated/historical status would otherwise be unclear.

Examples:

- `docs/operations/tickets/` means active work specs.
- `docs/operations/tickets/archive/` means completed ticket history.
- `docs/operations/templates/` means reusable skeletons.
- `docs/testing/generated/` means machine-derived testing truth.
- `docs/operations/evidence/` means retained proof, not policy.

## Core rules

1. Update the canonical doc for the area in the same change as the product, process, or policy change.
2. Put durable human-readable docs under `docs/`; put generated artifacts, local captures, and operator-specific scratch output under `tmp/`, `build/`, or another non-committed path.
3. When you add or remove a doc, update the nearest index/README in the same change so navigation does not drift.
4. Do not create a new top-level docs subtree without also adding an index entry and updating `config/devctl/docs-governance.json` if that index must stay enforced.

See `docs/start-here/source-of-truth-matrix.md` for canonical owners and `docs/governance/README.md` for the automation checks that enforce parts of this policy.

## Create

1. Choose the existing docs area that matches the doc's job before creating a file:
   - product scope and open questions: `docs/product/`
   - UX and implemented behavior: `docs/ux/`
   - architecture and ADRs: `docs/architecture/`
   - testing policy and runbooks: `docs/testing/`
   - launch/tickets/evidence: `docs/operations/`
   - governance rules and manifests: `docs/governance/`
2. Prefer updating an existing canonical doc over creating a parallel explainer.
3. Create a new doc only when it owns a distinct contract, index, runbook, decision log, or retained evidence note.
4. Link the new doc from the closest maintained index, usually `docs/README.md` or the subtree `README.md`.

## Update

1. Edit the canonical source first, then trim or relink any secondary docs that duplicated old wording.
2. Keep file paths stable unless the current name is actively misleading.
3. For behavior or feature-surface changes, run:
   - `python3 tools/devctl/main.py governance docs-health`
   - `python3 tools/devctl/main.py governance docs-accuracy`
4. For structure-only changes, `docs-health` is the minimum gate because it checks links, required index pointers, and retention policy.

## Rename or move

Treat a rename or move as a contract change.

1. Keep the destination inside the correct docs area instead of creating an ad hoc category.
2. Update every local link, README pointer, manifest entry, and config reference in the same change.
3. Rename only when the new path is clearly more accurate than the old one.

## Archive and retain

1. Keep active human-readable evidence notes under `docs/operations/evidence/` using the retention policy in `docs/operations/evidence/index.md`.
2. Keep raw run output under artifact roots such as `tmp/devctl-artifacts/`, `tmp/qa-agents/`, `scripts/benchmarks/runs/`, or `build/devctl/`; those paths are for machine output, not curated docs.
3. Use `docs/operations/assets/` only for retained raw or reviewed launch assets that are intentionally part of the repository record.
4. Prune superseded evidence notes once they are no longer referenced by active roadmap, PRD, ticket, or incident docs.

## Delete

1. Delete a doc only after its durable content has been moved into the surviving canonical doc or confirmed obsolete.
2. Remove inbound links and index entries in the same change.
3. If governance config or manifests reference the deleted path, update those files too.

## Naming rules

1. Stable docs use lowercase kebab-case names such as `error-recovery-guide.md`.
2. Ticket docs use the work item id plus slug, such as `eng-24-startup-readiness-metadata-self-healing.md`.
3. Evidence notes use `YYYY-MM-DD-<scope>-<slug>.md` inside the correct `docs/operations/evidence/wp-xx/` directory.
4. ADRs use `ADR-###-<slug>.md`.
5. Dated plans or research notes keep the date prefix and stay in their established subtree, such as `docs/superpowers/plans/`.
6. Avoid generic names like `notes.md`, `misc.md`, `draft.md`, or `new-doc.md`.

## Non-committed and sensitive inputs

1. Do not commit operator-specific reviewer payloads, local bundle seeds, or other generated inputs that are recreated from current artifacts.
2. Current example: `tools/qa-agents/_inputs/*.json` is generated reviewer output and should stay local.
3. If a workflow needs both a committed template and a local filled-in result, commit the template/schema and ignore the filled-in result.
