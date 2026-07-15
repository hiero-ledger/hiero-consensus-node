---
type: decision
id: ADR-002
title: Fixture decision with historical citations
historical: [RemovedByPlan.java, MovedClass.java]
---

# ADR-002 — Fixture decision with historical citations

The refactor deleted `RemovedByPlan.java`, so both the bare name and the full path
(`platform-sdk/module-a/src/main/java/com/x/RemovedByPlan.java`) are gone as documented.
`MovedClass.java` is also claimed deleted here, but it still exists — that contradiction must assert.
