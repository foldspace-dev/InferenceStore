# Release plan

Updated: 2026-06-13

## Release philosophy

Validate the need first, then ship the core semantics with one real local adapter. Delay runtime breadth until policy, events, validation, privacy, timeout/error behavior, and tests feel right.

## Versioning

Before 1.0:

- breaking changes allowed;
- use clear migration notes;
- keep releases frequent;
- mark adapters experimental.

After 1.0:

- semantic versioning;
- binary compatibility checks;
- adapter compatibility matrix.

## Milestones

### M0: Validation docs and gate

Deliverables:

- README;
- strategy;
- PRD;
- architecture;
- API sketch;
- issues;
- user interview plan;
- static/scripted route traces;
- first local adapter decision.

Exit criteria:

- 15 user conversations completed;
- at least 8 of 15 say they would try it, or maintainer waiver is recorded;
- decision on MVP adapter set: OpenAI-compatible + LiteRT-LM Android/JVM;
- public/private go decision.

### M1: Core prototype with real local adapter

Deliverables:

- KMP project;
- core API;
- fake providers;
- simple policy presets;
- streaming events;
- canonical route trace;
- privacy enforcement;
- validation interface;
- timeout/error mapping contracts implemented;
- testkit;
- OpenAI-compatible adapter;
- LiteRT-LM Android/JVM adapter.

Exit criteria:

- local success demo with fake provider;
- local success demo with LiteRT-LM when model path exists;
- local unavailable -> cloud fallback demo;
- schema fail -> cloud repair demo;
- privacy denies cloud demo;
- attempt timeout -> fallback demo;
- cancellation does not fallback;
- non-maintainer can understand route trace.

### M2: Alpha

Deliverables:

- docs quickstart;
- sample app;
- route monitor;
- in-memory cache;
- request dedupe;
- CI/publishing snapshots;
- security/privacy guide;
- provider adapter authoring guide.

Exit criteria:

- external developer can follow quickstart;
- at least one non-maintainer issue/PR;
- API feedback collected;
- LiteRT-LM and OpenAI-compatible adapters have documented limitations.

### M3: Mobile proof

Deliverables:

- Android + iOS sample shell;
- iOS local/platform adapter decision;
- one iOS adapter prototype or design spike;
- Firebase/Apple adapter exploration;
- adapter guide refinements.

Exit criteria:

- common code request works across iOS and Android sample shells;
- route trace shows platform differences;
- docs explain capability differences.

### M4: Production hardening

Deliverables:

- SQLDelight artifact store;
- route journal;
- OpenTelemetry exporter;
- privacy recipes;
- cancellation hardening;
- binary compatibility setup.

Exit criteria:

- early adopter can run in a production-like app;
- route telemetry is useful;
- privacy defaults are documented/tested;
- adapter compatibility matrix exists.

### M5: Meeseeks lifecycle

Deliverables:

- provider inventory refresh worker;
- model warmup worker;
- model download worker interface;
- telemetry upload worker;
- lifecycle docs.

Exit criteria:

- background model lifecycle demo;
- Meeseeks integration docs;
- clear foreground/background boundary.

## Module maturity policy

### Experimental

- may break every release;
- no compatibility promise;
- documented limitations.

### Alpha

- public feedback requested;
- rough compatibility;
- tests cover core behavior.

### Beta

- API mostly stable;
- migration notes;
- production-minded docs.

### Stable

- semantic versioning;
- compatibility checks;
- long-term maintenance promise.

## Release checklist

- [ ] Changelog updated.
- [ ] Migration notes updated.
- [ ] API docs generated.
- [ ] Samples compile.
- [ ] Tests pass across targets.
- [ ] Adapter compatibility matrix updated.
- [ ] README install version updated.
- [ ] GitHub release notes include breaking changes.
- [ ] Maven artifacts verified.
- [ ] Privacy model tests pass.
- [ ] Timeout/error/fallback mapping tests pass.

## Suggested first release train

```text
0.1.0-dev       docs + validation gate + skeleton
0.2.0-alpha01  core + fake providers + canonical events
0.3.0-alpha01  policy + privacy + fallback + validation
0.4.0-alpha01  OpenAI-compatible adapter + timeout/error mapping
0.5.0-alpha01  LiteRT-LM Android/JVM adapter + sample mode
0.6.0-alpha01  quickstart + sample app + dedupe + monitor
0.7.0-alpha01  artifact store interfaces
0.8.0-alpha01  mobile proof/iOS adapter spike
0.9.0-alpha01  Meeseeks provider inventory
```

## Public communication cadence

- Launch RFC before code.
- Share API sketch in Kotlin Slack.
- Invite Store users to critique analogy.
- Invite local inference maintainers to implement adapters.
- Publish short demos for each milestone.

## What not to promise before beta

- production-ready local inference;
- universal model management;
- automatic best quality routing;
- all providers;
- stable adapter APIs;
- semantic cache;
- iOS/Android local adapter parity.
