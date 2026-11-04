# consensus-fakes

Fake implementations of platform interfaces for tools and tests: no-op implementations,
deliberately insecure cryptographic entities, and the like.

## Architecture

A fake module — nothing should depend on it except tooling and test code, and it must never be
reached from production code. It ships as a normal main-source module rather than as test
fixtures of the module it fakes, so any module's tests, benchmarks, and tooling can depend on
it without inheriting that module's test fixtures (modularization rule 4 in
[`../CLAUDE.md`](../CLAUDE.md)).

## Dependency Rules

May depend on:
- Any supporting, functional-api, functional-impl, or self-contained functional module whose API it fakes
- `consensus-wiring-framework` — a fake of a wired module has to expose the same components as
the real one
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`

Must not depend on:
- Any structural-transitional module — those are on their way out of the consensus layer, and a
fake must not pin them in place
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender` — depend on the API instead
- `swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap`

No known violations.
