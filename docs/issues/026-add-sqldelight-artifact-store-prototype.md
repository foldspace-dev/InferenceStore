# Add SQLDelight artifact store prototype

Labels: `area/storage`, `type/feature`, `priority/p2`  
Milestone: `M4 Production hardening`  
Dependencies: #12, #20

## Problem

Early adopters may need persistent artifacts and route traces.

## Proposal

Implement SQLDelight-backed artifact store and route journal prototype.

## Acceptance criteria

- [ ] Artifact records persist across process restart.
- [ ] Route attempts persist.
- [ ] Privacy no-prompt/no-output persistence is honored.
- [ ] Schema migrations are tested.
- [ ] Docs include retention guidance.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
