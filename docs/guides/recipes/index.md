# Recipes

Short, task-oriented walkthroughs for the things you'll actually do with InferenceStore.
Each recipe states a goal, shows complete code, and tells you how to verify it.

<div class="grid cards" markdown>

- :material-backup-restore:{ .lg .middle } __Add cloud fallback__

    ---

    Prefer the on-device model and fall back to cloud when it's unavailable, slow, or
    wrong.

    [:octicons-arrow-right-24: Recipe](cloud-fallback.md)

- :material-shield-lock:{ .lg .middle } __Enforce local-only privacy__

    ---

    Keep sensitive requests on-device and prove — in a test — that zero cloud calls
    happen.

    [:octicons-arrow-right-24: Recipe](local-only-privacy.md)

- :material-check-decagram:{ .lg .middle } __Validate &amp; repair output__

    ---

    Validate structured output against a schema and repair invalid local results in the
    cloud.

    [:octicons-arrow-right-24: Recipe](validate-and-repair.md)

- :material-database-arrow-down:{ .lg .middle } __Cache results__

    ---

    Reuse results with a fingerprint-keyed, privacy-safe cache and a TTL.

    [:octicons-arrow-right-24: Recipe](cache-results.md)

- :material-test-tube:{ .lg .middle } __Test routing deterministically__

    ---

    Use the testkit's fake providers and route assertions — no model, no network.

    [:octicons-arrow-right-24: Recipe](test-routing.md)

- :material-chart-timeline-variant:{ .lg .middle } __Export traces to OpenTelemetry__

    ---

    Turn redacted route telemetry into OpenTelemetry spans.

    [:octicons-arrow-right-24: Recipe](opentelemetry.md)

</div>
