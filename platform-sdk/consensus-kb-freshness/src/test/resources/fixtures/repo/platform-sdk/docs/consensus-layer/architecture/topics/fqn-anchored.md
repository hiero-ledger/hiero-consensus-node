---
type: architecture-topic
title: FQN-anchored topic
last_reviewed: 2020-01-01
---

# FQN-anchored topic

This topic anchors its claims by fully-qualified type names only. The component lives in
`com.x.PresentClass` and cooperates with `com.x.MovedClass`, which has since moved packages.
A deleted helper `com.x.NoSuchClass` is also cited, plus the external type
`io.grpc.StreamObserver` the engine cannot see.

Package references: the helpers live in `com.x.sub`, the retired ones lived in `com.x.gonepkg`,
and logging goes through `org.apache.logging`.
