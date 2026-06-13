# Metrics

Updated: 2026-06-13

## Product metrics

### Adoption

- GitHub stars
- number of public adapters
- number of sample apps
- Maven downloads
- unique issue/discussion participants
- external blog posts or talks
- companies/teams in early adopter list

### Activation

- users who run quickstart
- users who configure two providers
- users who write a custom policy
- users who write a custom validator
- users who use testkit route assertions

### Retention

- repeat contributors
- adapters maintained across releases
- issues closed by non-maintainers
- teams using the library in production or prototypes

## Runtime metrics exposed by the library

Every request should be able to emit:

- request ID
- key/fingerprint
- policy ID/version
- selected provider ID
- selected model ID/version
- provider kind: local/cloud/platform/test
- route attempts
- fallback reasons
- availability probe result
- capability check result
- time to first token
- total latency
- token count if available
- estimated cost if available
- cache hit/miss/stale
- validator result
- privacy classification
- error category
- cancellation reason

## Library quality metrics

- route decision tests
- API binary compatibility once stable
- adapter contract compatibility
- CI target matrix pass rate
- documentation coverage
- sample freshness
- issue response time
- release cadence

## MVP success metrics

Before M1 build starts:

- 15 discovery interviews completed
- 8+ interviewees say they would try it, or maintainer waiver recorded
- first local adapter decision recorded

Within 30 days of public alpha:

- 100+ GitHub stars or equivalent signal from Store/KMP audience
- 10+ meaningful GitHub discussions/issues
- 3+ external developers trying the sample
- 2+ people asking for a specific adapter
- 1+ non-maintainer PR
- 1 real local adapter included in the alpha/MVP, or a documented blocker explains why it was disabled

Within 90 days:

- second adapter decision or prototype
- 1 production-minded team evaluating
- 3+ custom policies from users
- 3+ custom validators from users
- at least one documented case study or demo

## Metrics deliberately not optimized early

- raw model performance
- benchmark superiority over runtimes
- number of providers
- hosted dashboard usage
- semantic cache hit rate

The early question is whether the architecture is valuable.
