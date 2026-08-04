---
type: concept
title: Symbol refs fixture
last_reviewed: 2026-08-04
---

# Symbol refs fixture

Line references whose line is a declaration migrate to `#symbol`:

- a method: `WithMethod.java:6`
- a type: `WithMethod.java:5`
- an enum constant: `PaletteFixture.java:6`
- a field: `FieldFixture.java:6`

A line inside a body (not a declaration) does not migrate: `WithMethod.java:7`.

Already-symbol references are checked directly: `WithMethod.java#foo` resolves,
but `WithMethod.java#nope` names no declared symbol.
