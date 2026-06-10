# consensus-reconnect

Public API for reconnect: configuration for recovering a node that has fallen too far behind
for gossip to catch it up.

## Architecture

The API half of the reconnect module pair. Intentionally thin — the orchestration entry point
lives in `swirlds-platform-core` today. For reconnect mechanics, see the
[reconnect topic](../docs/consensus-layer/architecture/topics/reconnect.md) and the
[consensus/execution boundary](../docs/consensus-layer/architecture/interfaces/consensus-execution-boundary.md).

## Dependency Rules

May depend on:
- `swirlds-config-api`; no consensus-layer modules currently needed

Must not depend on:
- Other functional-api modules
- Any `*-impl` module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`

No known violations.
