# consensus-event-stream

Writes the consensus event-stream files: serializing consensus-ordered events to disk for
durability and external consumers.

## Architecture

A structural-transitional module — treated like an impl module: nothing should depend
on it except the platform wiring and test code. It serializes consensus events; it could have been split into api/impl, but was not, because it will be deleted once
the consensus event stream is superseded by the block stream. It will not move to the execution
layer.

## Dependency Rules

May depend on:
- `consensus-model`, `consensus-metrics`, `consensus-utility`
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`,
`consensus-wiring-framework`

Must not depend on:
- Any functional-api or impl module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`, `swirlds-state-api`,
`swirlds-state-impl`, `swirlds-virtualmap`

No known violations.
