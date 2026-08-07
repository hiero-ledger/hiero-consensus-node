---
type: decision
id: ADR-003
title: Anchorless decision fixture
status: accepted
last_reviewed: TBD
---

# Anchorless decision fixture

This decision cites no source file at all — pure prose, no `.java` paths, no fully-qualified type
spans. With a non-ISO `TBD` marker and no anchored source, it must resolve to
`unknown (no anchored sources)`: the no-sources check wins over the marker check.
