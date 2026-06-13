# Add route journal interface

Labels: `area/reliability`, `type/feature`, `priority/p2`  
Milestone: `M4 Production hardening`  
Dependencies: #5, #14

## Problem

Policies may need recent failure/cooldown information to avoid repeatedly choosing bad providers.

## Proposal

Define `RouteJournal` and in-memory implementation for provider attempt history, failures, and cooldowns.

## Acceptance criteria

- [ ] Attempt outcomes can be recorded.
- [ ] Recent failures can be queried by provider.
- [ ] Cooldown can be represented.
- [ ] Policy can optionally consume journal.
- [ ] Docs include examples.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
