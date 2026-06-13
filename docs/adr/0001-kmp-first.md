# ADR-0001: KMP-first core

Status: Accepted  
Updated: 2026-06-13

## Context

The target users are KMP/mobile teams. Store and Meeseeks both establish the maintainer's credibility in Kotlin Multiplatform architecture.

## Decision

Core APIs will live in commonMain and use Kotlin coroutines/Flow.

Platform-specific APIs and dependencies will live in adapter modules.

## Consequences

Positive:

- shared feature code
- testable common policies
- adapter isolation
- Store-like ecosystem fit

Negative:

- some platform APIs require bridging
- not all capabilities can be represented equally
- adapter modules may feel fragmented
