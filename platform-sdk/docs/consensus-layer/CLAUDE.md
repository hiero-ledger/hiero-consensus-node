# Authoring the Consensus Layer KB

Instructions for any agent (or person) adding to or updating this knowledge
base. Read this before editing. The structural contract — directory layout, ID
conventions, frontmatter, and the canonicalization rule — lives in
[`LAYOUT.md`](LAYOUT.md); the per-catalog entry schemas live in each catalog's
`FORMAT.md`. Read the relevant one before you write.

## Before you start

- Read `LAYOUT.md` for where the content belongs, and the target catalog's
  `FORMAT.md` for the entry shape. Do not infer the format from a sibling file —
  siblings drift; `FORMAT.md` is the schema.
- Match the prevailing voice: terse, declarative statements. Detail goes in the
  sections the format designates for it, not in the headline claim.

## Non-duplication is the prime directive

`LAYOUT.md` defines the rule (one canonical home per fact; reference, don't
restate; the narrative-restatement exception). This is the part that is easy to
get wrong, so apply it deliberately:

- **Before changing any fact, find every place it already appears.** Search the
  whole KB for the ID, the term, the parameter value, and the file path before
  you edit. An agent that updates "4 of the 5" copies of a value silently
  corrupts the KB — the surviving stale copy is indistinguishable from the truth.
  Grepping first is not optional.
- **The reason the rule exists is to make that search return one hit.** When you
  add content, prefer a reference over a copy precisely so the next editor has
  one place to change, not five. If you find yourself copying a load-bearing
  value, stop and link instead.
- **When you do restate for the reader, restate the summary, not the value.**
  Link to the canonical home for the authoritative version. See `LAYOUT.md`,
  "Narrative restatement vs. duplicated source of truth."

## Adding entries

- **Search before you create.** Check the catalog's `README.md` index and grep
  for the concept before opening a new `INV`/`RUL`/`SCN`/`HEU`/`ADR`. A
  near-duplicate entry is the same disease as a duplicated fact, one level up.
- **Apply the discriminator; don't guess.** Invariant vs. rule, scenario vs.
  heuristic — each catalog's `README.md` states the test. A single fact is filed
  in one catalog, never both.
- **Allocate the ID and write the index row in the same change.** The
  `README.md` index row is a sanctioned duplication with a sync obligation
  (`LAYOUT.md`). An entry without its row, or a row without its entry, is a bug.

## Anchor every claim to code

This is the single most important property of this KB and the easiest to let
slip. The current entries hold a high bar: claims about how the system behaves
are tied to the specific code that makes them true. Match that bar — it is what
separates this KB from prose that rots.

- **Coverage, not just honesty.** A behavioral claim names the class, and where
  it matters the method, that realizes it — the way RUL-002 ties its ordering
  claim to `PlatformCoordinator.flushIntakePipeline()`. A load-bearing claim a
  reader cannot trace to code is either raised to a cited authority (a paper or
  protocol, the way invariants use `source`) or cut. "Don't fabricate an anchor"
  is the floor; **"every load-bearing claim carries one" is the bar.**
- **By document type — apply the right kind of anchor, don't over- or
  under-anchor:**
  - *Rules* — `components:` frontmatter is required and lists full paths; the
    body names the guard, ordering, or structure the property rests on.
    Enforced by `rules/FORMAT.md`; keep `last_verified_against` honest.
  - *Invariants* — anchor the *basis* to the external `source` (paper/protocol)
    **and** the *enforcement* to the code site, in `verification` and the body,
    as INV-001 does (`ConsensusImpl.recalculateAndVote`). Do not conflate the
    two: the authority is permanent, the code site is contingent.
  - *Architecture topics* — these carry the heaviest anchor load and have **no
    `FORMAT.md` enforcing it**, so the discipline rests entirely here: name
    every component with its class and file path. This is a de facto standard
    held up by hand — a new or expanded topic that names components without
    anchoring them is a regression, even though no schema will reject it.
  - *Concepts, ADRs, scenarios* — anchor proportionally. Concepts cite code only
    where a mental model touches it; ADRs anchor the mechanism, not every
    sentence of rationale; scenarios anchor the events and identifiers in the
    timeline. Lighter is correct here — do not force file paths where they don't
    belong.
- **Path convention.** Inline anchors use the abbreviated form
  `module/.../File.java` (e.g.
  `consensus-event-creator-impl/.../tipset/TipsetEventCreator.java`); full,
  unabbreviated paths belong in `components:` frontmatter. Follow the
  surrounding file.
- **Verify before you write; refresh on touch.** Confirm every class, method,
  path, and commit exists before citing it — never invent line numbers or
  commit hashes. When you change a claim, re-check that its anchors still
  resolve and bump the file's freshness marker (`last_verified_against` for
  rules, `last_reviewed` for narrative docs). **A stale-but-present anchor is
  more dangerous than none** — it reads as verified. If you cannot verify an
  anchor, say so in the entry rather than guessing.

## Keep load-bearing frontmatter in sync

`status` especially — tools read it. When a rule goes `divergent`, an ADR goes
`superseded`, or a scenario reaches `verified`, update the entry, its
`README.md` row, and any field the format marks load-bearing, together.

## Scope and altitude

- The KB covers the consensus-layer topics named in `LAYOUT.md`. Content
  drifting into execution-layer internals, block production, TSS, or application
  semantics is out of scope — flag it, do not absorb it.
- Structural changes (new catalog, new ID prefix, convention change) land in the
  same PR as the corresponding `LAYOUT.md` update. Content additions to existing
  catalogs do not touch `LAYOUT.md` — that is what the catalog `README.md` files
  are for.
