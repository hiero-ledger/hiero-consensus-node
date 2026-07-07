# Consensus Layer Knowledge Base

`swirlds-platform-core` is the **wiring root of the consensus layer**: where the consensus
modules are composed into a running platform and where the consensus / execution boundary is
drawn today (`ExecutionLayer`, `ConsensusStateEventHandler`, `PlatformBuilder`, reconnect
orchestration, notifications). Nearly all of the knowledge base is relevant here. Consult it at
[`../docs/consensus-layer/`](../docs/consensus-layer/) — it documents the current
implementation as canonical, anchored to specific files, classes, and methods.

**Most relevant to this module:**

- [`architecture/overview.md`](../docs/consensus-layer/architecture/overview.md) — the module map and where the consensus / execution boundary runs.
- [`architecture/interfaces/consensus-execution-boundary.md`](../docs/consensus-layer/architecture/interfaces/consensus-execution-boundary.md) — the full boundary surface (`PlatformBuilder`, `ExecutionLayer`, `ConsensusStateEventHandler`, `StateLifecycleManager`, `ApplicationCallbacks`, notification listeners).
- [`architecture/topics/wiring-framework.md`](../docs/consensus-layer/architecture/topics/wiring-framework.md) — how modules are soldered together here.
- The lifecycle topics rooted in this module: [`reconnect.md`](../docs/consensus-layer/architecture/topics/reconnect.md), [`signed-state-management.md`](../docs/consensus-layer/architecture/topics/signed-state-management.md), [`freeze-and-upgrade.md`](../docs/consensus-layer/architecture/topics/freeze-and-upgrade.md), [`health-monitor-and-backpressure.md`](../docs/consensus-layer/architecture/topics/health-monitor-and-backpressure.md).

**Navigation.** Start at [`architecture/overview.md`](../docs/consensus-layer/architecture/overview.md).
Vocabulary lives in [`glossary.md`](../docs/consensus-layer/glossary.md) and
[`concepts/`](../docs/consensus-layer/concepts/); rationale in
[`decisions/`](../docs/consensus-layer/decisions/); properties to preserve in
[`invariants/`](../docs/consensus-layer/invariants/) and
[`rules/`](../docs/consensus-layer/rules/); tunable parameters in
[`tunables.md`](../docs/consensus-layer/tunables.md); known incidents and diagnostics in
[`scenarios/`](../docs/consensus-layer/scenarios/) and
[`heuristics/`](../docs/consensus-layer/heuristics/).
