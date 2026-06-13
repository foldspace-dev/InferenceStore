# Issue tracker: Linear

Issues and PRDs for this repo live in **Linear**, in the **OSS** team under the **InferenceStore** project. Use the Linear MCP tools for all issue operations. Code and pull requests live in GitHub (`foldspace-dev/InferenceStore`, via the `gh` CLI), but issues are tracked in Linear — not GitHub Issues.

## Conventions

- **Create an issue**: `save_issue` with `team: "OSS"`, `project: "InferenceStore"`, `title`, `description` (Markdown — use literal newlines, no escape sequences), `milestone`, and `labels`. Omit `id` when creating.
- **Read an issue**: `get_issue` by identifier (e.g. `OSS-123`); fetch discussion with `list_comments`.
- **List issues**: `list_issues` filtered by `team`, `project`, `label`, `milestone`, or `state`.
- **Comment**: `save_comment` against the issue.
- **Apply labels / milestone / dependencies**: `save_issue` with `id` set. Relation fields (`blockedBy`, `blocks`, `relatedTo`) are **append-only** — existing relations are never removed.
- **Move state**: `save_issue` with `id` and `state` (state type, name, or ID; discover via `list_issue_statuses`).

Projects and milestones are created/updated with `save_project` and `save_milestone`.

## When a skill says "publish to the issue tracker"

Create a Linear issue in the **OSS** team, **InferenceStore** project, with the appropriate milestone and the `ready-for-agent` label (unless instructed otherwise — see `triage-labels.md`).

## When a skill says "fetch the relevant ticket"

Run `get_issue` with the Linear identifier and include comments.
