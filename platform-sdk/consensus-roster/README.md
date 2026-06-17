# consensus-roster

Roster data and lookup for the consensus layer. Will eventually move to the execution layer.

## Architecture

A passive data module — holds and exposes the current and future roster structures without
orchestrating anything, analogous to `consensus-model` but scoped to roster data. Rosters are
carried as round metadata so every module agrees on which roster applies to which round.

## Dependency Rules

May depend on:
- `consensus-model`
- `swirlds-base`, `swirlds-config-api`, `swirlds-state-api`, `swirlds-state-impl`

Must not depend on:
- Other supporting modules (`consensus-concurrent`, `consensus-metrics`, `consensus-utility`,
`consensus-platformstate`)
- Any functional-api or impl module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-component-framework`, `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-virtualmap`

No known violations.
