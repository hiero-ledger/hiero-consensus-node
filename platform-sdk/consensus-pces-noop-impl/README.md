# consensus-pces-noop-impl

No-op implementation of PCES file handling: satisfies the PCES interface without writing any
files, for use in environments where durability is not required.

## Architecture

Implements the [`consensus-pces`](../consensus-pces) API as a no-op. To understand what
behavior this omits, see
[restart and PCES](../docs/consensus-layer/architecture/topics/restart-and-pces.md).

## Dependency Rules

May depend on:
- `consensus-pces` (its API)
- `swirlds-base`, `swirlds-config-api`

Must not depend on:
- Other `consensus-*-impl` modules
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`

No known violations.
