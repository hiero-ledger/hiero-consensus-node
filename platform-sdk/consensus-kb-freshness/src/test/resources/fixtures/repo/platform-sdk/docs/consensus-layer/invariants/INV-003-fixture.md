---
type: invariant
id: INV-003
title: Fixture invariant verified by a regression test
verification: module-a/src/test/java/com/x/RegressionFixtureTest.java — `guardsTheInvariant`
---

# INV-003 — Fixture invariant verified by a regression test

The KB cites regression tests as verification, so a test source must resolve like any other
citation rather than reading as a gone file. Cited in the abbreviated form
`module-a/.../x/RegressionFixtureTest.java`, which resolves through the basename index rather
than a filesystem check.
