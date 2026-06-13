# Implement error category to fallback mapping contract

Labels: `area/core`, `area/reliability`, `type/feature`, `priority/p0`  
Milestone: `M1 Core prototype`  
Dependencies: #5, #6, #9, #37

## Problem

The error taxonomy is useful only if every category has stable retry/fallback/terminal behavior.

## Proposal

Implement the default mapping table from `docs/technical/error-fallback-mapping.md` and expose route assertions for it.

## Acceptance criteria

- [ ] All stable error categories are represented in public/core model.
- [ ] Default fallback behavior matches the mapping table.
- [ ] Policy can restrict fallback but cannot redefine category defaults silently.
- [ ] Provider adapters can attach raw causes without leaking them to redacted monitor events.
- [ ] Tests cover each category and its fallback/terminal behavior.
- [ ] Docs explain adapter error mapping expectations.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
