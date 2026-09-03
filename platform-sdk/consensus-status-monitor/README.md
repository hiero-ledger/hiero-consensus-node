# consensus-status-monitor

Owns the platform status state machine: consumes typed status actions (self event reached
consensus, replay finished, fallen behind, state written to disk, time elapsed), advances
[`PlatformStatus`](../consensus-model/src/main/java/org/hiero/consensus/model/status/PlatformStatus.java),
and publishes each change to the rest of the layer. Also tracks per-node uptime and self
degradation, which is what produces the self-event-reached-consensus signal the machine runs on.

## Architecture

A self-contained functional module — it bundles its public API and implementation in one module
rather than a split api/impl pair, and unlike the structural-transitional modules that share
that shape, it is a permanent part of the layer. Other modules are expected to depend on it. It
exports only `StatusMonitorModule`, the action vocabulary, and the config records — the state
machine, per-status logic, and uptime tracking are kept out of the exported packages.

For the statuses, the actions that drive them, and who consumes the result, see
[platform status](../docs/consensus-layer/architecture/topics/platform-status.md); for how a
quiescing node holds `ACTIVE`, see
[quiescence](../docs/consensus-layer/architecture/topics/quiescence.md).

## Dependency Rules

May depend on:
- Supporting modules: `consensus-model`, `consensus-metrics`
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`consensus-wiring-framework`

Must not depend on:
- Any functional-api or impl module — status is an input to those modules, not the reverse
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`, `swirlds-state-api`,
`swirlds-state-impl`, `swirlds-virtualmap`

Known violation — `requires org.hiero.consensus.roster`: `consensus-roster` is a
structural-transitional module that nothing outside that category should depend on (rules 3 and
7). This does not resolve on its own — the dependency has to be removed before `consensus-roster`
can move to the execution layer.
