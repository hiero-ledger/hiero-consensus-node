# consensus-reconnect

Public API for reconnect: configuration for recovering a node that has fallen too far behind
for gossip to catch it up.

## Architecture

A structural-transitional module, and the API half of the reconnect module pair — treated like an
impl module: nothing outside that category should depend on it except platform wiring, tooling, and
test code. Intentionally thin — the orchestration entry point lives in `swirlds-platform-core`
today. The entire reconnect function will move to the execution layer. For reconnect mechanics, see the
[reconnect topic](../docs/consensus-layer/architecture/topics/reconnect.md).

## Dependency Rules

May depend on:
- `swirlds-config-api`; no consensus-layer modules currently needed

Must not depend on:
- Any `consensus-*-impl` module, its own `consensus-reconnect-impl` included
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`

No known violations.
