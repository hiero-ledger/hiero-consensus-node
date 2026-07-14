---
type: architecture-topic
title: Present topic
last_reviewed: 2020-01-01
---

# Present topic

The method [`WithMethod::foo`](../../../../module-a/src/main/java/com/x/WithMethod.java:3)
has moved to a new line since it was cited.

The signature [`WithMethod.baz(int, String)`](../../../../module-a/src/main/java/com/x/WithMethod.java)
still matches, but [`WithMethod.baz(long)`](../../../../module-a/src/main/java/com/x/WithMethod.java)
does not.

A dead symbol cited three times in this one entry:
first [GhostFile.java](../../../../module-a/src/main/java/com/x/GhostFile.java),
second [GhostFile.java](../../../../module-a/src/main/java/com/x/GhostFile.java),
third [GhostFile.java](../../../../module-a/src/main/java/com/x/GhostFile.java).

A valid cross-doc link to [the rule](../../rules/RUL-001-fixture.md) and its
[heading](../../rules/RUL-001-fixture.md#fixture-rule).

The annotated method [`AnnotatedMethod::run`](../../../../module-a/src/main/java/com/x/AnnotatedMethod.java:4)
was cited at a stale line; it sits below Javadoc and an annotation.

Module: `wrong-module`. Source: [LabeledClass.java](../../../../module-a/src/main/java/com/x/LabeledClass.java).

A broken cross-doc link to [old rule](../../rules/RUL-001-fixtur.md) that should suggest the real one.
