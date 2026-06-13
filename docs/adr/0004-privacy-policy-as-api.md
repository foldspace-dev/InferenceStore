# ADR-0004: Privacy policy as API

Status: Accepted  
Generated: 2026-06-13

## Context

Inference requests may contain sensitive user data. Local/cloud routing is a privacy decision, not only a performance decision.

## Decision

Every request carries a `PrivacyPolicy` with safe defaults. The execution controller enforces privacy before provider invocation.

## Consequences

Positive:

- prevents accidental cloud fallback
- makes tests possible
- supports enterprise governance
- aligns with local-first value proposition

Negative:

- more upfront API complexity
- developers must understand privacy profiles
