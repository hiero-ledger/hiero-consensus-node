# consensus-roster

Roster data and lookup for the consensus layer. Will eventually move to the execution layer.

## Architecture

A structural-transitional module, and a passive data module within that category — it holds and
exposes the current and future roster structures without orchestrating anything, analogous to
`consensus-model` but scoped to roster data. Rosters are carried as round metadata so every module
agrees on which roster applies to which round.

## Dependency Rules

May depend on:
- `consensus-model`, `consensus-metrics`
- `swirlds-base`, `swirlds-config-api`, `swirlds-state-api`, `swirlds-state-impl`

Must not depend on:
- Supporting module `consensus-utility`; structural-transitional module `consensus-platformstate`
- Any functional-api, functional-impl, or self-contained functional module
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `consensus-wiring-framework`, `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`,
`swirlds-virtualmap`

No known violations.
