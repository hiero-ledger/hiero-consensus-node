---
title: Quiescence
kind: architecture-topic
last_reviewed: TBD
---

# Quiescence

> **Status:** opt-in feature; not enabled on all networks. [TBD: confirm
> default and network coverage.]
>
> **Placeholder.** This topic is stubbed; full content is pending.

## Responsibilities

[TBD]

## State

[TBD]

## Inputs and outputs

[TBD]

## Behaviour

[TBD]

## Interactions with other topics

Known so far:

- **Stale-events routing.** Any event already routed to Execution's
  prehandle must, if it later goes stale, be reported as stale to
  Execution as well so prehandle state can be reconciled. Events
  dropped as ancient before reaching prehandle are not reported. See
  [`../../concepts/stale-events.md`](../../concepts/stale-events.md).

[TBD: list additional affected topics.]

## Cross-references

**Concepts.**

- [`../../concepts/stale-events.md`](../../concepts/stale-events.md)

**Sibling topics.**

- [TBD]

**Invariants.** [TBD]

**Decisions.** [TBD]

**Scenarios.** [TBD]
