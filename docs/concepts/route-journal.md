# Route journal

A `RouteJournal` records provider attempt outcomes so a policy can **avoid repeatedly
choosing a failing provider**. After enough recent failures a provider enters a
*cooldown*; a policy can skip cooled-down providers until they recover.

`MemoryRouteJournal` is the in-memory implementation; cooldown behavior is configured by
`CooldownPolicy` (failure threshold, window, cooldown duration).

```kotlin
val journal = MemoryRouteJournal(policy = CooldownPolicy(failureThreshold = 3, cooldown = 30.seconds))

// Record outcomes (e.g. from a monitor or after each request):
journal.record(providerId, AttemptOutcome.Failed, ErrorCategory.TransientProviderError)

journal.recentFailures(providerId)  // recent failures still in the window
journal.cooldown(providerId)        // active Cooldown(remaining, recentFailures) or null
```

## Consuming it from a policy

`InferencePolicy.selectRoute` is non-suspending, so read a cooldown snapshot first, then
pass it to `excluding(...)`:

```kotlin
val cooled = journal.cooledDownProviders()
store.generate(
    request.copy(policy = Policies.preferLocalThenCloud().excluding(cooled)),
)
// A cooled-down provider is dropped from the route; routing falls back to the next one.
```

Cooldowns expire on their own (driven by the journal's clock), so a recovered provider is
automatically eligible again on the next snapshot.

Learn more: [storage model](../technical/storage-model.md),
[policy](policy.md).
