# consensus-event-stream

Writes the consensus event-stream files: serializing consensus-ordered events to disk for
durability and external consumers.

## Architecture

A supporting module that serializes the output of the consensus algorithm. For the events it
writes, see the [hashgraph topic](../docs/consensus-layer/architecture/topics/hashgraph.md)
and [event lifecycle](../docs/consensus-layer/concepts/event-lifecycle.md).

## Dependency Rules

May depend on:
- `consensus-model`, `consensus-concurrent`, `consensus-metrics`, `consensus-utility`
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`

Must not depend on:
- Any functional-api or impl module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-component-framework`, `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

Known violation — `requires transitive com.swirlds.component.framework`: as a supporting
module this should not depend on `swirlds-component-framework`; needs investigation.
