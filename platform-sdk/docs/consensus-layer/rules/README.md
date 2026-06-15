# Rules — Index

Properties that hold for the **current implementation** but may legitimately
change if the implementation changes. Breaking a rule is a flag for
confirmation, not automatically a defect.

These are the contingent truths — they hold because of how the code is
written today, not because the protocol demands it. A redesign may
deliberately supersede a rule; that is a `retired` rule, not a bug. The
Change Reviewer must still flag any change that breaks one, because an
*accidental* break is indistinguishable from a deliberate one until someone
confirms intent.

- Entry format: see `FORMAT.md`.
- Allowed `topics` values: see top-level `topics.md`.

Pair this catalog with `invariants/` (permanent properties that must never
change). When unsure where an entry belongs, apply the test: "if a correct
reimplementation broke this, would that be a bug?" No → rule, file it here.
Yes → invariant.

A rule is born `holds`. It becomes `divergent` if the code is found to
already violate it (a suspected defect pending triage) and `retired` when a
design or code change has deliberately superseded it. Treat `status` as
load-bearing — a `retired` rule must not be enforced by review, and a
`divergent` rule is an open question, not settled knowledge.

## Index

|   ID    |                                                        Title                                                        |   Class    |                               Topics                                | Status |
|---------|---------------------------------------------------------------------------------------------------------------------|------------|---------------------------------------------------------------------|--------|
| RUL-001 | A SignedState must remain reserved while any consumer can still access it                                           | structural | signed-state-management                                             | holds  |
| RUL-002 | The intake pipeline is flushed component-by-component in topological order so every event advances as far as it can | structural | restart-and-pces, event-intake, wiring-framework                    | holds  |
| RUL-003 | Every node contributing to consensus is independently restartable                                                   | structural | restart-and-pces, signed-state-management, reconnect, event-creator | holds  |

<!--
Row convention, one line per entry, kept in RUL-NNN order:
| RUL-NNN | Title from entry frontmatter | class | topic-slug[, topic-slug] | holds\|divergent\|retired |
-->
