# Add OpenTelemetry monitor exporter

Labels: `area/observability`, `type/feature`, `priority/p2`  
Milestone: `M3 Production hardening`  
Dependencies: #14

## Problem

Production teams need to export inference route metrics to their observability stack.

## Proposal

Implement optional OpenTelemetry exporter module for route spans and metrics.

## Acceptance criteria

- [ ] Request route is represented as span(s).
- [ ] Provider attempts include attributes.
- [ ] Fallback reasons are exported.
- [ ] Prompts/outputs are redacted by default.
- [ ] Docs include setup and attribute list.

## Notes

This issue is part of the initial InferenceStore planning backlog. Adjust scope after API validation.
