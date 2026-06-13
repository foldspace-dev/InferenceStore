# Create Meeseeks telemetry upload worker

Labels: `area/meeseeks`, `area/observability`, `type/feature`, `priority/p3`  
Milestone: `M5 Meeseeks lifecycle`  
Dependencies: #14, #28

## Problem

Apps may want to batch and retry telemetry upload without impacting foreground inference.

## Proposal

Add optional worker that uploads redacted route telemetry batches.

## Acceptance criteria

- [ ] Telemetry payload excludes raw prompt/output by default.
- [ ] Worker retries transient upload failures.
- [ ] Worker respects telemetry permission.
- [ ] Docs include privacy notes.
- [ ] Tests cover redaction.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
