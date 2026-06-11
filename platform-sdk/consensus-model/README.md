# consensus-model

The shared data model for the consensus layer: events, rounds, judges, roster metadata,
`PlatformStatus`, notifications, and the types that travel across the consensus/execution
boundary.

## Architecture

This module is the foundation of the consensus layer — every other module depends on it.
To keep it importable anywhere without introducing circular dependencies, it has no
consensus-layer dependencies of its own: only `base-*` modules and external libraries.

## Dependency Rules

May depend on:
- `base-*` modules, `swirlds-base`, and external libraries only

Must not depend on:
- Any other `consensus-*` module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-component-framework`, `swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`

No known violations.
