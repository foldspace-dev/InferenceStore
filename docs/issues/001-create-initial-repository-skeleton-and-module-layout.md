# Create initial repository skeleton and module layout

Labels: `area/build`, `type/task`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: None

## Problem

The project needs a KMP repository layout that can support a small stable core plus optional provider, storage, testkit, and Meeseeks modules.

## Proposal

Create Gradle/KMP skeleton with core, testkit, provider-openai-compatible placeholder, samples, docs, and CI-ready build structure.

## Acceptance criteria

- [ ] Repository builds with `./gradlew build`.
- [ ] `inferencestore-core` compiles for common/JVM/Android/iOS targets.
- [ ] `inferencestore-testkit` module exists.
- [ ] Placeholder provider module exists but does not affect core dependencies.
- [ ] README includes current module map.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
