# Issues

Updated: 2026-06-13

This directory contains the planning backlog for InferenceStore. The backlog is generated in three forms:

- per-issue Markdown files;
- `issues.csv`;
- `issues.json`;
- `backlog-summary.md`.

## Milestones

| Milestone | Meaning |
|---|---|
| M0 Validation | Validate demand, first adapter decision, and gate M1 on 8/15 interview signal. |
| M1 Core prototype | Core API, canonical contracts, fake/testkit, OpenAI-compatible adapter, LiteRT-LM Android/JVM adapter. |
| M2 Alpha | Quickstart, sample app, route monitor, in-memory cache, dedupe, docs, publishing. |
| M3 Mobile proof | iOS adapter decision/prototype and mobile sample proof. |
| M4 Production hardening | Persistent storage, route journal, telemetry exporters, privacy recipes, compatibility hardening. |
| M5 Meeseeks lifecycle | Background provider/model lifecycle tasks. |

## Import note

Review the issues before importing. The `scripts/create-github-issues.sh` helper intentionally creates issues with the generated body files, labels, and milestones, but it does not create labels or milestones for you.
