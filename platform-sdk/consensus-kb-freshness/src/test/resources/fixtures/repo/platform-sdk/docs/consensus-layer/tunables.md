---
type: tunable-catalog
title: Tunables — Catalog
last_reviewed: 2020-01-01
---

# Tunables — Catalog

## `fix.a.*` — FixtureConfig

Module: `module-a`. Source: [FixtureConfig.java](../../module-a/src/main/java/com/x/FixtureConfig.java).

|   ID    |          Key          |        Type        | Default |                   Effect                   | Range | Fragility |
|---------|-----------------------|--------------------|---------|--------------------------------------------|-------|-----------|
| TUN-001 | `fix.a.alpha`         | int                | `5`     | Matches the source.                        |       | —         |
| TUN-002 | `fix.a.goneKey`       | int                | `1`     | Property no longer exists.                 |       | —         |
| TUN-003 | `fix.a.beta`          | Duration           | `10s`   | Default changed in source.                 |       | —         |
| TUN-004 | `fix.a.gamma`         | Path               | `x`     | Semantic type for a String.                |       | —         |
| TUN-005 | `fix.a.delta`         | int                | `7`     | Non-literal default.                       |       | —         |
| TUN-007 | `fix.a.listy`         | List&lt;String&gt; | (empty) | Escaped generics, empty default.           |       | —         |
| TUN-008 | `fix.a.emptyListy`    | List&lt;String&gt; | (empty) | Well-known EMPTY_LIST constant default.    |       | —         |
| TUN-009 | `fix.a.emptyMismatch` | List&lt;String&gt; | `x,y`   | Documented default contradicts EMPTY_LIST. |       | —         |

## `fix.b.*` — OldNameConfig

Module: `module-a`. Source: [OldNameConfig.java](../../module-a/src/main/java/com/x/OldNameConfig.java).

|   ID    |     Key     | Type | Default |            Effect             | Range | Fragility |
|---------|-------------|------|---------|-------------------------------|-------|-----------|
| TUN-006 | `fix.b.one` | int  | `1`     | Class renamed during a merge. |       | —         |
