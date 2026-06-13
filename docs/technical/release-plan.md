# Release plan

Generated: 2026-06-13

## Release philosophy

Ship the core semantics early. Delay runtime breadth until policy, events, validation, and tests feel right.

## Versioning

Before 1.0:

- breaking changes allowed
- use clear migration notes
- keep releases frequent
- mark adapters experimental

After 1.0:

- semantic versioning
- binary compatibility checks
- adapter compatibility matrix

## Milestones

### M0: Validation docs

Deliverables:

- README
- strategy
- PRD
- architecture
- API sketch
- issues
- user interview plan
- demo route traces

Exit criteria:

- 10-15 user conversations
- decision on MVP adapter set
- public/private go decision

### M1: Core prototype

Deliverables:

- KMP project
- core API
- fake providers
- simple policy
- streaming events
- route trace
- validation interface
- testkit

Exit criteria:

- local success demo
- local unavailable -> cloud fallback demo
- schema fail -> cloud repair demo
- privacy denies cloud demo

### M2: Alpha

Deliverables:

- OpenAI-compatible adapter
- docs quickstart
- sample app
- route monitor
- in-memory cache
- request dedupe
- CI/publishing snapshots

Exit criteria:

- external developer can follow quickstart
- at least one non-maintainer issue/PR
- API feedback collected

### M3: Mobile proof

Deliverables:

- one Android local/platform adapter
- one iOS local/platform adapter or adapter design
- sample running on Android/iOS
- adapter guide

Exit criteria:

- common code request works across iOS and Android
- route trace shows platform differences
- docs explain capability differences

### M4: Production hardening

Deliverables:

- SQLDelight artifact store
- route journal
- OpenTelemetry exporter
- privacy recipes
- cancellation hardening
- binary compatibility setup

Exit criteria:

- early adopter can run in a production-like app
- route telemetry is useful
- privacy defaults are documented/tested

### M5: Meeseeks lifecycle

Deliverables:

- provider inventory refresh worker
- model warmup worker
- model download worker interface
- telemetry upload worker
- lifecycle docs

Exit criteria:

- background model lifecycle demo
- Meeseeks integration docs
- clear foreground/background boundary

## Module maturity policy

### Experimental

- may break every release
- no compatibility promise
- documented limitations

### Alpha

- public feedback requested
- rough compatibility
- tests cover core behavior

### Beta

- API mostly stable
- migration notes
- production-minded docs

### Stable

- semantic versioning
- compatibility checks
- long-term maintenance promise

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

## Suggested first release train

```text
0.1.0-dev       docs + skeleton
0.2.0-alpha01  core + fake providers
0.3.0-alpha01  policy + fallback + validation
0.4.0-alpha01  OpenAI-compatible adapter + quickstart
0.5.0-alpha01  sample app + dedupe + monitor
0.6.0-alpha01  first local adapter
0.7.0-alpha01  artifact store interfaces
0.8.0-alpha01  Meeseeks provider inventory
```

## Public communication cadence

- Launch RFC before code.
- Share API sketch in Kotlin Slack.
- Invite Store users to critique analogy.
- Invite local inference maintainers to implement adapters.
- Publish short demos for each milestone.

## What not to promise before beta

- production-ready local inference
- universal model management
- automatic best quality routing
- all providers
- stable adapter APIs
- semantic cache
