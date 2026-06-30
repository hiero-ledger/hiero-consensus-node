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
- Prefer short sentences and lists over flowing prose. A multi-step mechanism is
  a numbered list, not a run-on paragraph; split any sentence that needs three
  clauses to land. A long explanatory paragraph is a smell — it buries the fact
  and hides unanchored claims in the gaps between sentences.

## Non-duplication is the prime directive

`LAYOUT.md` defines the rule: one canonical home per fact; reference, don't
restate; the narrative-restatement exception. Easy to get wrong — apply it
deliberately:

- **Before changing any fact, find every place it already appears.** Search the
  whole KB for the ID, the term, the parameter value, and the file path before
  you edit. An agent that updates "4 of the 5" copies of a value silently
  corrupts the KB — the surviving stale copy is indistinguishable from the truth.
  Grepping first is not optional.
- **Prefer a reference over a copy.** The rule exists so the next editor's
  search returns one hit, not five places to change in lockstep. If you find
  yourself copying a load-bearing value, stop and link instead.
- **When you do restate for the reader, restate the summary, not the value.**
  Link to the canonical home for the authoritative version. See `LAYOUT.md`,
  "Narrative restatement vs. duplicated source of truth."

## Stop and ask on conflict

If a grep surfaces two places that disagree — two values, two statuses, two
accounts of one behavior — do not guess and do not silently reconcile. A
conflict means a fact has two homes, or code moved under one of them; choosing
at random can promote the stale copy. Surface it; let the curator adjudicate.

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
- **Symptoms are a shared index, not a knowledge file.** `symptoms.md` is a
  controlled vocabulary; the diagnostic knowledge lives in `heuristics/` and
  `scenarios/`, which cite a `SYM-NNN` rather than describe the symptom inline.
  So symptoms are *reused*, not created per entry: point a `symptoms:` field at
  an existing `SYM-NNN` where one fits, and only when none does, append a new
  one to `symptoms.md` first — never cite an uncatalogued symptom. The integrity
  rule is enforced by `heuristics/FORMAT.md` and `scenarios/FORMAT.md`; the
  append mechanics and ID discipline live in `symptoms.md`.

## Anchor every claim to code

The single most important property of this KB, and the easiest to let slip.
Behavioral claims tie to the specific code that makes them true. Match that bar
— it is what separates this KB from prose that rots.

- **Coverage, not just honesty.** A behavioral claim names the class, and where
  it matters the method, that realizes it — the way RUL-002 ties its ordering
  claim to `PlatformCoordinator.flushIntakePipeline()`. A load-bearing claim a
  reader cannot trace to code is either raised to a cited authority (a paper or
  protocol, the way invariants use `source`) or cut. "Don't fabricate an anchor"
  is the floor; **"every load-bearing claim carries one" is the bar.**
- **No narrative exemption; anchor the surprising claim hardest.** A behavioral
  sentence keeps its anchor mid-paragraph and in "explanatory" prose — anchor
  each claim, not just the one that first names the component. And the
  counter-intuitive assertion is the one a reader cannot take on faith, so it
  gets the line-level cite, not a hand-wave: under-anchoring there is the worst
  place to skimp.
- **By document type — apply the right kind of anchor, don't over- or
  under-anchor:**
  - *Rules* — `components:` frontmatter is required and lists full paths; the
    body names the guard, ordering, or structure the property rests on.
    Enforced by `rules/FORMAT.md`. Rules carry no date marker; keep `status`
    honest (mark `divergent` the moment the code no longer matches) and
    `provenance` traceable.
  - *Invariants* — anchor the *basis* to the external `source` (paper/protocol)
    **and** the *enforcement* to the code site, in `verification` and the body,
    as INV-001 does (`ConsensusImpl.recalculateAndVote`). Do not conflate the
    two: the authority is permanent, the code site is contingent.
  - *Architecture topics* — the heaviest anchor load, with **no `FORMAT.md`
    enforcing it**: name every component with its class and file path. The
    standard is held up by hand — naming a component without anchoring it is a
    regression no schema will catch.
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
  resolve, then refresh the file's currency marker (see
  [When to bump the freshness marker](#when-to-bump-the-freshness-marker)).
  **A stale-but-present anchor is more dangerous than none** — it reads as
  verified. If you cannot verify an anchor, say so rather than guessing.

## When to bump the freshness marker

`last_reviewed` (a date) is the freshness marker on narrative and
single-file-catalog docs (`concepts/`, `architecture/**`, `glossary.md`,
`symptoms.md`, `tunables.md`). It asserts one thing: the file's code-anchored
claims were checked against current code on that date. Bump it only when that
is true. ID-catalog entries (`rules/`, `invariants/`, …) carry no date marker —
their currency is `status`, kept honest under
[Keep load-bearing frontmatter in sync](#keep-load-bearing-frontmatter-in-sync).

- **Bump when** you add, change, or re-confirm a code-anchored claim: you
  re-anchor to moved code, confirm existing anchors still resolve against
  current code, or revise what a claim asserts.
- **Do not bump** for edits that touch no claim and no anchor: typo fixes,
  formatting, link repair, reordering, wording changes that leave every claim
  intact.
- **Never bump without verifying.** Advancing the date is itself a claim —
  that you re-checked the anchors. If you edit a claim but cannot re-verify it,
  leave the date and flag the gap in the entry. A falsely-fresh marker is the
  stale-anchor failure one level up.

## Keep load-bearing frontmatter in sync

`status` especially — tools read it. Tools do not just read frontmatter; they
**query by `id` and by frontmatter field**. A malformed, missing, or
off-format field drops the entry from query results as surely as deleting it.
The format is a hard requirement, not a style preference. When a rule goes
`divergent`, an ADR goes `superseded`, or a scenario reaches `verified`, update
the entry, its `README.md` row, and any field the format marks load-bearing,
together.

## Scope and altitude

- **The KB describes the current code; current code is canonical.** Entries
  state what runs today. Proposed or future-state design goes only into a
  clearly-marked `## Future state (sidebar)` in the topic file and/or the
  topic's `delta-map/` entry — never into the body of a claim about how the
  system behaves. See [`README.md`](README.md) and
  [`delta-map/FORMAT.md`](delta-map/FORMAT.md).
- The KB covers the consensus-layer topics named in `LAYOUT.md`. Content
  drifting into execution-layer internals, block production, TSS, or application
  semantics is out of scope — flag it, do not absorb it.
- Structural changes (new catalog, new ID prefix, convention change) land in the
  same PR as the corresponding `LAYOUT.md` update. Content additions to existing
  catalogs do not touch `LAYOUT.md` — that is what the catalog `README.md` files
  are for.
