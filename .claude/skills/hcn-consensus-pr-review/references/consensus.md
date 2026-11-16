# Consensus

Checks that only apply to consensus-layer code. The knowledge lives in the
[consensus-layer KB](/platform-sdk/docs/consensus-layer/). This file says how to use it. Don't copy KB content here.

## Finding the KB entries a PR touches

Search the KB for each changed file's path and for the names of the classes it changes. The hits are spread across the
catalogs:

| Catalog | Where it names code |
|---|---|
| `rules/` | the `components:` field |
| `invariants/` | the `verification:` field and the body |
| `decisions/` (ADRs) | the body |
| `scenarios/` | the body |
| `architecture/`, `concepts/`, `glossary.md` | the body |

Read every hit before judging the change. Each catalog's `README.md` and `FORMAT.md` explain its fields.

**Status decides whether an entry is enforced:**

| Catalog | Enforced | Open question, not settled fact | Not enforced |
|---|---|---|---|
| Rules | `holds` | `divergent` | `retired` |
| Invariants | `enforced` | `divergent` | `proposed` |
| ADRs | `accepted` | | `proposed`, `superseded`, `deprecated` |

For an entry with `divergent` status, raise a question if the change touches it. Never a finding.

## Invariants

An invariant holds under any correct implementation. Breaking one is always a defect.

**Blocking finding when** the change matches a mechanism in the invariant's `## Change risk` section, or otherwise makes
the statement false. Name the invariant and the mechanism.

## Rules

A rule holds for the current implementation and may legitimately change.

**When a change breaks a rule:**

- **No sign it was intended:** blocking finding.
- **The PR says it was intended:** still blocking until the PR also updates the rule entry, either retiring it or
  rewriting it.

A sign of intent is the PR description or linked issue saying so, or the PR changing the rule entry itself.

## ADRs

**When a change goes against an accepted ADR:**

- **No sign it was intended:** blocking finding.
- **The PR says it was intended:** still blocking until the PR adds a new ADR that supersedes the old one.

**Example:** reintroducing nGen-style ordering after ADR-008 replaced it with sequence numbers.

## Scenarios

Scenarios record past incidents and edge cases.

- **Reviewer mode:** list each scenario whose code the PR touches in the orientation, as background.
- **Both modes:** check that the change doesn't reintroduce the scenario. If it might, that's a blocking finding. Name
  the scenario and the step of its sequence the change makes possible again.

## Determinism

Some code must produce the same result on every node, given the same events. Examples: consensus order, consensus
timestamps, judges, round decisions. The KB basis is INV-002 and INV-003. Their `verification:` fields, and those of
related invariants, show which code that is.

**Blocking finding when** that code gains a possible source of nondeterminism:

- iteration over a `HashMap`, `HashSet` or other collection with no defined order, where the order affects the result
- the wall clock, or anything else local to the node
- unseeded or node-local randomness
- floating-point arithmetic in a computed result
- a result that depends on thread timing

Name the source and the result it can affect.

## The KB going out of date

**Non-blocking finding when:**

- the PR changes behaviour the KB describes, so a KB statement is now false. This covers architecture topics, concepts,
  the glossary, and the text of rules and invariants.
- the PR renames, moves or removes code the KB cites: a file, class, method or config key.

Name the KB file and quote the statement or reference. If the PR doesn't fix it, the item goes in the follow-up block.

The [`consensus-kb-freshness`](/platform-sdk/consensus-kb-freshness/README.md) tool also finds broken references, but it
doesn't run in CI, so the skill checks for them itself.

## Edits to the KB

When the PR edits files under `platform-sdk/docs/consensus-layer/`, check the edits against the KB's own authoring rules.
Read them first; don't restate them here:

- [`CLAUDE.md`](/platform-sdk/docs/consensus-layer/CLAUDE.md): authoring rules
- [`LAYOUT.md`](/platform-sdk/docs/consensus-layer/LAYOUT.md): structure, IDs, the one-home-per-fact rule
- the `FORMAT.md` of each catalog the PR touches: the entry schema

**Non-blocking finding when** an edit breaks one of those rules. These are the rules most often broken:

- a fact copied into a second place instead of linked
- a new entry without its `README.md` index row, or a row without its entry
- a behavioural claim with no anchor to code, or an anchor that points at a line number instead of a symbol
- `last_reviewed` changed by hand. Only the freshness checker writes it.
- a `status` change not made in the entry, its index row, and related fields together
- future-state design written as current behaviour
- a structural change (new catalog, new ID prefix) without the matching `LAYOUT.md` update

Name the rule, quoting the KB file it comes from. The clarity rules in
[clarity-and-naming.md](clarity-and-naming.md), topic 11, apply to KB edits as well.

## Terminology

Glossary terms are checked in [clarity-and-naming.md](clarity-and-naming.md), topic 8.
