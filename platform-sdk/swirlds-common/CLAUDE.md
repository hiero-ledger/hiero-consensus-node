# Consensus Layer Knowledge Base

`swirlds-common` is shared infrastructure used by **both the consensus and execution layers**,
not a consensus module per se. But it hosts platform-runtime primitives the consensus layer
leans on — `NotificationEngine`, `PlatformContext`, and the `com.swirlds.common.platform`
package — so when your work here touches those, the consensus-layer knowledge base at
[`../docs/consensus-layer/`](../docs/consensus-layer/) is the reference for how they are used.
It documents the current implementation as canonical, anchored to specific files, classes, and
methods.

**Most relevant when working on the consensus-facing parts:**

- [`architecture/interfaces/consensus-execution-boundary.md`](../docs/consensus-layer/architecture/interfaces/consensus-execution-boundary.md) — the `NotificationEngine` (reached via `Platform.getNotificationEngine()`) and the notification listeners that carry boundary traffic.
- [`architecture/topics/wiring-framework.md`](../docs/consensus-layer/architecture/topics/wiring-framework.md) — how the platform runtime is composed.

The bulk of this module (threading, IO, crypto helpers, generic utilities) is general
infrastructure and outside the knowledge base's scope.

**Navigation.** Start at [`architecture/overview.md`](../docs/consensus-layer/architecture/overview.md).
Vocabulary lives in [`glossary.md`](../docs/consensus-layer/glossary.md) and
[`concepts/`](../docs/consensus-layer/concepts/); rationale in
[`decisions/`](../docs/consensus-layer/decisions/); properties to preserve in
[`invariants/`](../docs/consensus-layer/invariants/) and
[`rules/`](../docs/consensus-layer/rules/).
