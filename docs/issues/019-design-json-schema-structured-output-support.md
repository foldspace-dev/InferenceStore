# Design JSON/schema structured output support

Labels: `area/validation`, `type/rfc`, `priority/p1`  
Milestone: `M2 Alpha`  
Dependencies: #8

## Problem

Structured output is a major use case, but provider support varies and schema validation can be complex.

## Proposal

Write RFC and initial API for JSON/schema validation, parsing, and repair fallback.

## Acceptance criteria

- [ ] RFC covers serializer-based parsing.
- [ ] RFC covers schema-constrained providers vs post-hoc validation.
- [ ] RFC covers streamed partial JSON as post-MVP.
- [ ] Initial API can validate final JSON text.
- [ ] Tests demonstrate malformed local JSON -> cloud repair.

## Notes

This issue is part of the InferenceStore planning backlog. Adjust scope after API validation.
