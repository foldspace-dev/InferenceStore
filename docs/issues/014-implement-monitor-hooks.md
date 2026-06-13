# Implement monitor hooks

Labels: `area/observability`, `type/feature`, `priority/p1`  
Milestone: `M2 Alpha`  
Dependencies: #5, #7, #8

## Problem

Apps need route, latency, fallback, and validation telemetry.

## Proposal

Add `InferenceMonitor`, `MonitorEvent`, redaction defaults, and execution controller callbacks.

## Acceptance criteria

- [ ] Monitor receives request started/completed/failed.
- [ ] Monitor receives route selected/provider attempt/fallback/validation events.
- [ ] Monitor events contain no raw prompt/output by default.
- [ ] Tests cover event order and redaction.
- [ ] Docs include monitor example.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
