## Consensus Layer — Module Categories and Modularization Rules

The consensus layer is in the middle of a modularization effort. The definitions below are
canonical — use them when reading or updating per-module `CLAUDE.md` files, adding new modules,
or deciding where a new dependency belongs.

### Modularization Rules

1. Base modules (`base-*`) must never depend on any non-base modules.
2. Supporting modules must not depend on functional-api or functional-impl modules.
3. Nothing must depend on impl modules except test code and test fixtures.
4. Test fixtures must not expose impl classes transitively to other modules.
5. Classes in `internal` packages must not be used outside their defining module.
6. Functional-api modules must not depend on each other.

### swirlds-* Module Rules

`swirlds-*` modules follow the same impl/API layering principles as `consensus-*` modules.

**Allowed in all modules:**
`swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`

**Allowed in functional-api and functional-impl modules only (not supporting modules):**
`swirlds-component-framework`

**Allowed in `consensus-platformstate` and `consensus-roster` only:**
`swirlds-state-api`, `swirlds-state-impl`

**Allowed in `consensus-state` only:**
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

**Transitional — currently present in `consensus-gossip`, `consensus-gossip-impl`, and
`consensus-reconnect-impl` but not permitted in the final architecture:**
`swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

**Prohibited everywhere — legacy modules being eliminated:**
`swirlds-common`, `swirlds-platform-core`

**Prohibited everywhere — implementation modules; depend on the API instead:**
`swirlds-metrics-impl`, `swirlds-logging-log4j-appender`

### Module Categories

**Supporting modules** — shared data model, helpers, metrics, and concurrency primitives
consumed across the layer. They form a strict DAG; no circular dependencies are permitted:

- `consensus-model` — **foundation**: holds the consensus data structures all other modules
  build on. Must not depend on any other supporting module or any other consensus module.
- `consensus-concurrent` — concurrency primitives; may depend on `consensus-model`.
- `consensus-metrics` — metrics infrastructure; may depend on `consensus-model`,
  `consensus-concurrent`.
- `consensus-roster` — roster data and lookup; may depend on `consensus-model`. Will
  eventually move to the execution layer.
- `consensus-platformstate` — `PlatformState` Merkle node; may depend on `consensus-model`.
  Will eventually move to the execution layer.
- `consensus-utility` — general-purpose helpers; may depend on `consensus-model`,
  `consensus-concurrent`, `consensus-metrics`, `consensus-roster`.
- `consensus-event-stream` — event-stream file writing; may depend on `consensus-model`,
  `consensus-concurrent`, `consensus-metrics`, `consensus-utility`.

**Functional-api modules** — the public-facing API of each business-logic topic:
- `consensus-event-creator`
- `consensus-event-intake`
- `consensus-gossip`
- `consensus-hashgraph`
- `consensus-pces`
- `consensus-reconnect`

**Functional-impl modules** — implementations of the functional APIs. May depend on their
paired API and any supporting module. Must not depend on other impl modules:
- `consensus-event-creator-impl`
- `consensus-event-intake-impl`
- `consensus-event-intake-concurrent`
- `consensus-gossip-impl`
- `consensus-hashgraph-impl`
- `consensus-pces-impl`
- `consensus-pces-noop-impl`
- `consensus-reconnect-impl`

**Structural-transitional modules** — treated like impl modules (rule 3 applies) until they
move to the execution layer:
- `consensus-state`

**Tooling modules** — not part of the runtime module graph; have relaxed dependency rules:
- `consensus-gui`
- `consensus-network-simulation`
- `consensus-otter-docker-app`
- `consensus-otter-tests`
- `consensus-sloth`
