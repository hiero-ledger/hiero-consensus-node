---
type: architecture-topic
title: Moved-anchor topic
last_reviewed: 2020-01-01
---

# Moved-anchor topic

This topic's only anchored source has moved to another module since it was cited:
[MovedClass](../../../../module-a/src/main/java/com/y/MovedClass.java) now lives in `module-b`.
A worklist that dropped moved anchors would call this topic un-anchored and never
re-check its prose — exactly the topics whose code moved wholesale need the semantic
pass most.
