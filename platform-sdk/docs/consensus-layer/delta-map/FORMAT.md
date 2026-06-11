# Delta map — Entry Format

Specification for `delta-map/<topic>.md` entries. This file defines the
structure only; the allowed topics are those listed in `README.md`,
each matching an architecture topic file under `../architecture/topics/`
(exception: `sheriff.md` covers a proposal-only module that has no
architecture topic yet).
What counts as a delta-map entry — a discrete change the consensus-layer
proposal calls for, scored against current code — is defined in `README.md`.

## File naming

- `delta-map/<topic>.md`
- The topic slug is identical to the corresponding
  `../architecture/topics/<topic>.md` file. No ID prefix.
- Cross-references from other files use the topic slug (e.g., "see the
  gossip delta map").

## Frontmatter

```yaml
---
title: Delta map — <topic>
kind: delta-map
last_reviewed: TBD
---
```

Field discipline:

- **`kind`** — always `delta-map`. Fixed; tools use it to distinguish
  delta-map files from architecture topics and other catalogs.
- **`last_reviewed`** — `TBD` until an engineer has confirmed the file's
  status calls against code, then the date of that review. Tools treat a
  `TBD` file as unreviewed: its statuses are drafted from code reading, not
  engineer-confirmed.
- No `curated_by` / `provenance` — delta-map files are living per-topic
  references, not ID-numbered catalog entries; `last_reviewed` carries the
  review discipline.

## Body

Exactly three sections, in this order.

### `## Summary`

Two or three sentences: where this topic sits relative to the proposed end
state, at a glance. A reader decides from this alone whether the topic is
mostly done, mostly future, or pulled in a different direction.

### `## Changes`

A table, one row per discrete change the proposal explicitly calls for — a
module split is a row, a module renaming is a row, an API method
introduction is a row, a behaviour shift is a row.

| Change | Proposal state | Current state | Status | Anchor / TBD |
|---|---|---|---|---|

- **Change** — short noun phrase identifying the discrete item.
- **Proposal state** — one or two sentences on what the proposal calls for.
- **Current state** — one or two sentences on what current code does.
- **Status** — exactly one of the four values below, bolded and hyphenated
  (`**not-started**`, never `not started`) so the catalog greps uniformly:
  - **done** — the proposed change is reflected in current code.
  - **partial** — the proposed change is partly reflected; one sentence on
    what is in place and what is missing.
  - **not-started** — the proposed change is not present; the current shape
    is unchanged from the pre-proposal state.
  - **divergent** — current code has moved, but in a different direction
    than the proposal (or another proposal has overtaken the change); one
    sentence on the divergence.
- **Anchor / TBD** — a code anchor for the current state: backticked
  module/file/class/method names, owning module in parentheses, no URLs, no
  line numbers. Every **done**, **partial**, or **divergent** row carries an
  anchor. A **not-started** row carries either an anchor proving the
  pre-proposal shape is intact or a
  `[TBD: question for engineer — <specific question>]` marker. A row may
  carry both an anchor and a TBD.

Status calls must have an anchored basis; if the call cannot be made from
code alone, use the TBD marker rather than guessing. Keep rows to changes
the proposal calls for — no tunable values (→ `../tunables.md`), no
invariants (→ `../invariants/`), no implementation walkthrough.

Scoring rules:

- Consensus-internal restructuring — extracting code into its own module
  that still lives in the consensus layer — is not **divergent**; score the
  ownership change **not-started** until responsibility actually moves.
- Where the team has permanently decided an end state that differs from the
  proposal text (e.g., PCES remaining its own module), score against the
  decided end state and note the decision in the row.

### `## Cross-references`

- Topic: `../architecture/topics/<topic>.md`.
- Proposal: the section(s) of
  `../../proposals/consensus-layer/Consensus-Layer.md` covering this topic.
- Invariants: `[TBD: INV-NNN once invariants.md catalog populates]` until
  the invariants catalog has entries to cite.
- `reconnect.md` only: additionally cite
  `../../proposals/reconnect-refactor/reconnect-refactor-proposal.md`,
  which has overtaken parts of the consensus-layer proposal for this topic.

## Skeleton

```markdown
---
title: Delta map — <topic>
kind: delta-map
last_reviewed: TBD
---

# Delta map: <topic>

## Summary

...

## Changes

| Change | Proposal state | Current state | Status | Anchor / TBD |
|---|---|---|---|---|
| ... | ... | ... | **done** | `Class` (`module`) |

## Cross-references

- Topic: [../architecture/topics/<topic>.md](../architecture/topics/<topic>.md)
- Proposal: [`Consensus-Layer.md` § <heading>](../../proposals/consensus-layer/Consensus-Layer.md#<anchor>)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
```
