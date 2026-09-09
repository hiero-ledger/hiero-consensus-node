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
- a link: [WithMethod.java:6](../../../module-a/src/main/java/com/x/WithMethod.java:6)

A line inside a body (line 7) does not migrate — it is suggested: `WithMethod.java:7`.
A line past the file's end is suggested too: `WithMethod.java:99`.

Already-symbol references are checked directly: `WithMethod.java#foo` resolves,
but `WithMethod.java#nope` names no declared symbol.
