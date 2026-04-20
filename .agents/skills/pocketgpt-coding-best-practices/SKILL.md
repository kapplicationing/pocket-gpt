---
name: pocketgpt-coding-best-practices
description: Use when editing PocketGPT code, reviewing refactors, or deciding how to keep boundaries, abstractions, and tests consistent with the repo's established conventions.
---

# PocketGPT Coding Best Practices

Use this skill when making code changes in PocketGPT or reviewing code for structure drift.

## Core Rules

- Keep layer boundaries intact.
- Keep UI thin; move workflow logic into coordinators, use cases, or mappers.
- Prefer typed contracts and sealed results over stringly-typed branching.
- Centralize repeated policy, parsing, and reporting logic.
- Avoid mutable service-locator style globals.
- Keep tests stable, semantic, and selector-driven.
- Remove task-note clutter from production code.

## What To Preserve

- Tiny portable domain contracts.
- Descriptor-driven model policy and runtime config.
- Coordinators/use-cases for stateful workflows.
- Composition roots that only assemble dependencies.
- Reusable helper flows for test automation.

## What To Fix First

- Classes that own multiple axes of change.
- Heuristic string parsing spread across several files.
- Duplicate flow/report discovery helpers.
- Broad composables or activity shells that own too much orchestration.
- Legacy compatibility paths that shadow newer typed contracts.

## Workflow

1. Identify the owning layer before editing.
2. If logic repeats, extract one helper or mapper.
3. If a class mixes orchestration with policy, split the policy out.
4. If a branch keys off model IDs or message text, replace it with a typed helper or descriptor-driven lookup.
5. Keep test selectors and scenario names semantic.

## Findings

See [repo conventions and anti-patterns](references/repo-conventions.md).
