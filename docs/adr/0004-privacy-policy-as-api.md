# ADR-0004: Privacy policy as API

Status: Accepted  
Updated: 2026-06-13

## Context

Inference requests may contain sensitive user data. Local/cloud routing is a privacy decision, not only a performance decision.

## Decision

Every request carries a `PrivacyPolicy` with safe defaults. `docs/technical/privacy-model.md` is the single source of truth. The execution controller enforces privacy before provider invocation and before provider-level fallback.

## Consequences

Positive:

- prevents accidental cloud fallback
- makes tests possible
- supports enterprise governance
- aligns with local-first value proposition

Negative:

- more upfront API complexity
- developers must understand privacy profiles
- quickstarts must make privacy explicit
