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
- Any consensus-layer module whose API it fakes — supporting, functional-api, and
structural-transitional modules alike
- `consensus-wiring-framework` — a fake of a wired module has to expose the same components as
the real one
- `swirlds-base`, `swirlds-logging`, `swirlds-config-api`, `swirlds-metrics-api`
- `swirlds-state-api`, `swirlds-state-impl`, `swirlds-virtualmap` — otherwise permitted only in
the state-adjacent modules, but a fake of one of those exposes their types in its signatures

Must not depend on:
- Any `consensus-*-impl` module — a fake stands in for an API and never builds on the real
implementation
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated
- `swirlds-metrics-impl`, `swirlds-logging-log4j-appender` — depend on the API instead

No known violations.
