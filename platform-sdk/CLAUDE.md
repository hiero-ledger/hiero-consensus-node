## Consensus Layer — Module Categories and Modularization Rules

The consensus layer is in the middle of a modularization effort. The definitions below are
canonical — use them when reading or updating per-module `CLAUDE.md`/`README.md` files, adding
new modules, or deciding where a new dependency belongs.

### Modularization Rules

1. Base modules (`base-*`) must never depend on any non-base modules.
2. Supporting modules must not depend on functional-api or functional-impl modules.
3. Nothing must depend on impl modules except test code and test fixtures.
4. Test fixtures must not expose impl classes transitively to other modules.
5. Classes in `internal` packages must not be used outside their defining module.
6. Functional-api modules must not depend on each other.

### base-* Modules

Foundation utilities shared across the platform. They have no `CLAUDE.md` or `README.md` of
their own, so they are described here.

- `base-concurrent` — threading and concurrency primitives (atomics, futures, interrupt handling).
- `base-crypto` — cryptographic primitives: hashing, signatures, key handling.
- `base-utility` — general-purpose helpers: constructable registry, IO, exceptions.

### base-* vs consensus-* Primitives

Some `base-*` modules have a `consensus-*` counterpart that occupies the same niche one layer
up: `base-concurrent`/`consensus-concurrent`, `base-utility`/`consensus-utility`. Both hold
primitives, but the dividing line is knowledge of the consensus layer:

- A `base-*` module holds **layer-agnostic** primitives — no dependency on `consensus-model`
  or any other consensus type. They could be used by any project, consensus or not.
- Its `consensus-*` counterpart holds primitives **specific to the consensus layer** — typically
  because they depend on `consensus-model`.

**Rule of thumb:** if a concurrency tool or utility does not require `consensus-model` (or any
other consensus type), it belongs in the `base-*` module, not its `consensus-*` counterpart.

### swirlds-* Modules

Shared platform infrastructure consumed by the consensus layer. They have no `CLAUDE.md` or
`README.md` of their own, so they are described here. They follow the same impl/API layering
principles as `consensus-*` modules.

- `swirlds-base` — foundational primitives (time, lifecycle/state, formatting, functions) used everywhere.
- `swirlds-logging` — structured logging API.
- `swirlds-logging-log4j-appender` — log4j appender bridging `swirlds-logging` output to log4j.
- `swirlds-config-api` — configuration API.
- `swirlds-metrics-api` — metrics API.
- `swirlds-metrics-impl` — metrics implementation; depend on `swirlds-metrics-api` instead.
- `swirlds-component-framework` — wiring framework for composing components into data pipelines.
- `swirlds-state-api` — state access and lifecycle API (singleton, queue, key-value).
- `swirlds-state-impl` — implementation of `swirlds-state-api`.
- `swirlds-virtualmap` — disk-backed virtual merkle map for large state.
- `swirlds-common` — legacy shared platform runtime (`NotificationEngine`, `PlatformContext`); being eliminated.
- `swirlds-platform-core` — legacy wiring root composing the consensus modules into a running platform; being eliminated.
- `swirlds-cli` — command-line tooling. A tooling module: not part of the runtime module graph, may depend on anything.

**Usage rules:**

- **Allowed in all modules:** `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`
- **Allowed in functional-api and functional-impl modules only (not supporting modules):** `swirlds-component-framework`
- **Allowed in `consensus-platformstate`, `consensus-roster`, and `consensus-iss-detection` only:** `swirlds-state-api`, `swirlds-state-impl`
- **Allowed in `consensus-state`, `consensus-state-management`, and `consensus-transaction-handling` only:** `swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`
- **Transitional — currently present in `consensus-gossip`, `consensus-gossip-impl`, and `consensus-reconnect-impl` but not permitted in the final architecture:** `swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`
- **Prohibited everywhere — legacy modules being eliminated:** `swirlds-common`, `swirlds-platform-core`
- **Prohibited everywhere — implementation modules; depend on the API instead:** `swirlds-metrics-impl`, `swirlds-logging-log4j-appender`

### consensus-* Module Categories

**Supporting modules** — shared data model, helpers, metrics, and concurrency primitives
consumed across the layer. They form a strict DAG; no circular dependencies are permitted. See
each module's `README.md` for its description and dependency rules.

- `consensus-model` — **foundation**: holds the consensus data structures all other modules build on. Must not depend on any other supporting module or any other consensus module.
- Remaining supporting modules: `consensus-concurrent`, `consensus-metrics`, `consensus-roster`, `consensus-platformstate`, `consensus-utility`.

**Functional-api modules** — the public-facing API of each business-logic topic:
- `consensus-event-creator`, `consensus-event-intake`, `consensus-gossip`, `consensus-hashgraph`, `consensus-pces`, `consensus-reconnect`

**Functional-impl modules** — implementations of the functional APIs. May depend on their
paired API and any supporting module. Must not depend on other impl modules:
- `consensus-event-creator-impl`, `consensus-event-intake-impl`, `consensus-event-intake-concurrent`, `consensus-gossip-impl`, `consensus-hashgraph-impl`, `consensus-pces-impl`, `consensus-pces-noop-impl`, `consensus-reconnect-impl`

**Structural-transitional modules** — treated like impl modules (rule 3 applies); temporary, awaiting either a move to the execution layer or removal:
- `consensus-state` — will move to the execution layer.
- `consensus-state-management` — will move to the execution layer alongside the signed-state machinery it drives.
- `consensus-transaction-handling` — will move to the execution layer.
- `consensus-event-stream` — will be deleted once the consensus event stream is superseded by the block stream.
- `consensus-iss-detection` — will move to the execution layer alongside the state machinery it validates.

**Tooling modules** — not part of the runtime module graph; have relaxed dependency rules:
- `consensus-gui`, `consensus-network-simulation`, `consensus-otter-docker-app`, `consensus-otter-tests`, `consensus-sloth`
