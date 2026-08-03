# consensus-kb-freshness

![](assets/fressness_checker_logo.png)

A deterministic checker that detects when the curated consensus-layer knowledge base
(`platform-sdk/docs/consensus-layer/`) has drifted out of sync with the code it documents, so a
human curator can repair it.

## Why this exists

The consensus-layer KB is *anchored to code*: topics, rules, invariants, and catalog entries cite
specific classes, methods, files, config keys, and line numbers. As the code moves, those anchors
rot. A stale anchor that still *looks* resolved — a class that has since moved packages, a method
whose signature changed — is more dangerous than a missing one: it **reads as verified** when it is
not. Today freshness is held up by hand through `last_reviewed` dates and `status` fields; this tool
mechanically finds the drift so that hand-maintenance is spent where it actually matters.

**Precision is the whole point.** A false alarm — flagging something that is actually fine — trains
the curator to ignore the report, which defeats the tool. So the design is precision-first: a slow
run is acceptable, a false positive is not. Every check is *three-valued* — `present`, `absent`, or
`unverifiable` — and **only a certain `absent` is reported as drift.** Anything the tool cannot
decide with certainty is kept quiet rather than guessed at, and every assertion carries one-look
evidence a curator can confirm at a glance.

## How it works

The checker is built in two layers, because "is this documentation still accurate?" splits into two
very different questions.

### Layer 1 — the deterministic engine (this module)

A pure Java CLI. It reads every KB document, extracts its *anchors* (the concrete code references in
the prose and frontmatter), and resolves each one against the current checkout. It **never calls a
model and does no network I/O**, so the same checkout in always yields the same findings out — byte
for byte. Symbol resolution is done by *parsing* the cited source with the JDK compiler's Tree API
(no build, no classpath, no execution): it reads the *declared* symbols and *as-written* signatures
straight from the source text.

It answers questions with a mechanical yes/no answer: *does this file exist? does this class still
live in the module the doc names? does this method still take these parameters? is this catalog ID
real?*

### Layer 2 — the semantic pass (the skill)

Some drift can't be detected mechanically. Prose might say "the intake stage discards events older
than the latest immutable round" — the class and method still exist, so the engine sees nothing
wrong, but the *behavior described* may no longer match the code. Deciding that requires reading and
understanding both the prose and the code. That is the **semantic pass**: a reasoning step performed
by the `kb-freshness` skill in Claude Code (see [The semantic pass](#the-semantic-pass-tier-3)). The
engine hands it a **worklist** — the topics whose anchored source changed since they were last
reviewed — so the expensive reading is spent only where the code actually moved.

## Why there are tiers

The deterministic checks are organized into **tiers of increasing depth — and increasing risk of a
false positive.** A check is placed in a tier only if it can be made at that depth *without
guessing*. The tiering is the mechanism that enforces the precision mandate: the cheap, unambiguous
checks run first and assert freely; the deeper a check reaches, the more carefully it is fenced, and
anything too fuzzy to settle mechanically is pushed up to the semantic pass rather than risk a false
assert.

|    Tier    |                                                                                                                            What it verifies                                                                                                                             |                                                                                                                  How                                                                                                                   |                                             Asserts drift?                                             |
|------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------|
| **Tier 0** | Existence of cited files (including allowlisted external ones), module directories, cross-doc links, `#headings`, catalog IDs (INV/RUL/ADR/SCN/HEU/SYM/TUN), and prose-cited Java *packages* (backtick reverse-domain names, checked against the indexed package tree). | Filesystem + text. Near-zero false-positive risk. A *missing* external file stays quiet (it may be generated), but its absence is flagged in the quiet log. A package outside every indexed namespace is external and stays quiet too. | Yes — a cited file, link target, catalog ID, or in-namespace package that is simply absent.            |
| **Tier 1** | A cited *type* still exists in the *module the doc names* (and a prose fully-qualified type in its cited *package*); a `verification:` method still exists on its class; a tunables-catalog *key* is still a declared `@ConfigProperty` of its `@ConfigData` record.    | Parse the cited source; look for the declared type/method/record component.                                                                                                                                                            | Yes — but a type found in a *different* module (or package) is reported as a **move**, not as gone.    |
| **Tier 2** | A cited *method signature* still matches (parameters/return), a documented *interface's method set* still matches the source, and a tunables-catalog *default* still matches the `@ConfigProperty(defaultValue = …)` literal.                                           | Parse and compare *as-written* signatures and annotation literals. Opt-in for interfaces via `interface:`/`methods:` frontmatter; automatic for the tunables catalog (its column conventions are the contract).                        | Yes — a signature that no longer matches, a documented method that is gone, or a default that changed. |
| **Tier 3** | *Behavioral* prose claims — what the code actually does.                                                                                                                                                                                                                | The **semantic pass** (the skill). Not deterministic.                                                                                                                                                                                  | No — advisory only, and only a `contradicted`-with-citation claim.                                     |

Two principles drive the split:

- **Depth vs. safety.** Deeper checks catch more drift but are easier to get wrong, so each is
  constrained to what the source states unambiguously. Line numbers, for instance, are *never*
  asserted on: a named symbol that resolves but whose cited line moved yields an *auto-fix
  suggestion*, not a finding, because a shifted line is a navigation nit, not drift. Overloads,
  inheritance, and generics-as-written are compared at Tier 2 only where the source is unambiguous;
  anything looser is left to Tier 3.
- **Escalation.** Each tier assumes the ones beneath it hold — there is no point comparing a method
  signature (Tier 2) if the class is gone (Tier 1) or the file is missing (Tier 0). The report
  leads with the cheapest, safest findings.

The Tier-2/Tier-3 boundary is deliberate: if a claim can be settled by comparing symbols the
compiler can see, it is deterministic (Tier ≤ 2); if it needs an understanding of what the code
*means* or *does*, it is semantic (Tier 3). Loose interface prose, for example, is routed to the
semantic pass rather than scraped into a brittle Tier-2 assertion that would misfire.

## The semantic pass (Tier 3)

The semantic pass is the only part that *reasons* rather than *resolves*, so it is fenced in tightly
to keep it from becoming a false-positive source of its own:

- **It reads the current source, never memory.** For each worklisted topic it opens the exact files
  the engine located and judges each load-bearing prose claim against what the code now says.
- **Only `contradicted`-with-citation survives.** Each claim is judged `supported`, `contradicted`,
  or `can't-determine`. Only `contradicted` claims that can point at the specific current code
  (file + symbol) that falsifies them are reported; `supported`, `can't-determine`, and any
  un-citable judgment are dropped.
- **It is advisory, not fact.** Its output lands in a clearly separated `## Advisory (semantic)`
  section, after and distinct from the deterministic report — never intermixed with engine-verified
  findings.
- **It looks only where the code moved.** It processes only worklist entries whose status is
  `review` or `unknown`; `fresh` topics are skipped.

The worklist that drives it is built by the engine from git history: for each topic it flags review
when any anchored source was committed **on or after** the topic's `last_reviewed` date.

The loop closes by bumping `last_reviewed`: a topic whose semantic pass found every claim supported
(or whose contradictions were fixed) should be marked reviewed — mechanically, via
`--mark-reviewed <entry-key>[=<yyyy-MM-dd>]` (repeatable; rewrites only an *existing*
`last_reviewed:` frontmatter line) — or every future run re-worklists the same topics. A reference to
a renamed or removed symbol counts as a contradiction (even if the behavior survives), so it blocks
the bump.

The date to record is the topic's **newest anchored-source commit date** — the state this run
reviewed, shown as `newestAnchoredCommit` in `worklist.json`. A bare `--mark-reviewed <key>` records
it automatically, derived from the scanned checkout, never the wall clock — so a run against a stale
`main` cannot mark commits it never reviewed as reviewed. (`--date` is used only as a fallback for a
topic that anchors no dated source, where there is no freshness signal anyway.)

## Lanes — how a result is routed

Not every resolved check is "drift." Each is routed into one of four **lanes**, and each lane has its
own output file so the drift report itself stays pure signal:

- **assert** → `report.md`. A certain `absent` (or a move). The drift a curator acts on.
- **quiet-log** → `quiet-log.md`. `unverifiable` — the symbol is generated or external (PBJ,
  protobuf, `.proto` sources), so the engine cannot see its source and refuses to guess. Not drift.
- **auto-fix** → `auto-fix.md`. The symbol resolves but a cited line number moved. A ready
  correction, applied only on request (`--fix`). Not drift. A package/path move with exactly one new
  location *is* drift (it asserts), but additionally gets a ready path-rewrite proposal here (which
  `--fix` also applies, along with any stale on-line `Module:` label).
- **coverage-gap** → `coverage.md`. A documentation gap — the inverse of drift — tracked separately:
  code the docs do not mention (e.g. an interface method with no documentation), an architecture topic
  that anchors no source, or an interface doc that does not opt into the Tier-2 method-set diff.

### Expected-gone citations (`historical:`)

Some documents legitimately cite deleted code — an ADR describing a removal cites the very file the
removal deleted. Listing such sources in the document's `historical:` frontmatter (basenames or
paths, e.g. `historical: [NonDeterministicGeneration.java]`) inverts the check for them: a gone
source is the expected state and lands in the quiet log, while a listed source that still *exists*
asserts — the doc claims a deletion that never happened or was reverted.

## Two ways to run it

### 1. Via the skill (recommended — adds the semantic pass)

Start Claude Code **inside this module** (`platform-sdk/consensus-kb-freshness/`) so the skill is
discovered, then invoke the `kb-freshness` skill. It runs the deterministic engine, reads the
semantic worklist, checks each changed topic's prose against the current source, and presents a
combined report with a clearly separated **Advisory (semantic)** section.

### 2. The deterministic core, standalone (no model)

```bash
./gradlew :consensus-kb-freshness:run --args="\
  --kb platform-sdk/docs/consensus-layer \
  --repo $(pwd) \
  --out build/kb-freshness \
  --baseline platform-sdk/consensus-kb-freshness/baseline/kb-freshness-baseline.tsv"
```

Or as a standalone jar, no Gradle:

```bash
./gradlew :consensus-kb-freshness:assemble
java -jar build/libs/consensus-kb-freshness-*-all.jar --kb platform-sdk/docs/consensus-layer --repo "$(pwd)"
```

Options (`--help` for the full list):

|             Option             |           Default           |                                                     Purpose                                                      |
|--------------------------------|-----------------------------|------------------------------------------------------------------------------------------------------------------|
| `--kb <path>`                  | *(required)*                | KB root to scan; resolved against `--repo`.                                                                      |
| `--repo <path>`                | `.`                         | Repo root all relative paths and source resolution anchor to.                                                    |
| `--out <dir>`                  | `<repo>/build/kb-freshness` | Where the artifacts are written.                                                                                 |
| `--baseline <file>`            | *(none)*                    | Baseline TSV to join findings against (see below).                                                               |
| `--modules <csv>`              | `platform-sdk,hedera-node`  | Source roots to index for symbol resolution.                                                                     |
| `--allowlist <file>`           | *(none)*                    | Extra generated/external allowlist directives (see `allowlist.example.txt`).                                     |
| `--date <str>`                 | `""`                        | Run date recorded as `first_seen` for newly-seen findings.                                                       |
| `--write-baseline`             | off                         | Overwrite `--baseline` with the proposed baseline.                                                               |
| `--fail-on-drift`              | off                         | Exit `2` if any new (not-baselined, not-dismissed) assertion is found — for future CI.                           |
| `--fix`                        | off                         | Apply the certain auto-fix edits (moved lines and unique path moves) to the KB in place.                         |
| `--mark-reviewed <key[=date]>` | *(none)*                    | Bump an entry's existing `last_reviewed:` frontmatter date (repeatable). A bare spec records the topic's newest anchored-source commit date (`newestAnchoredCommit`), falling back to `--date` only when the topic anchors no dated source. |

Exit codes: `0` success · `1` usage/IO error · `2` new drift with `--fail-on-drift`.

`--fix` writes exactly the diffs shown in `auto-fix.md` — moved line numbers and unique package/path
moves (including a stale on-line `Module:` label) — straight into the KB files. Each edit is guarded by
an exact match of the line it rewrites, so it is idempotent (a re-run finds nothing left to do) and
never touches a line that has since diverged. Fuzzy "did you mean" renames in `suggestions.md` are
deliberately **not** applied — those need a human decision. Re-run afterwards to refresh the artifacts.

## The generated files

A run writes all artifacts into `--out` (default `build/kb-freshness/`). Exactly one of them is the
drift report to act on; the rest are supporting lanes and inputs.

|              File               |              Lane / role               |             Read it when              |
|---------------------------------|----------------------------------------|---------------------------------------|
| `report.md`                     | **assert** — the drift report          | Always. This is the signal.           |
| `findings.json`                 | machine-readable finding set           | Tooling / CI; diffing two runs.       |
| `quiet-log.md`                  | **quiet-log** — `unverifiable`         | Auditing what was skipped, and why.   |
| `auto-fix.md`                   | **auto-fix** — ready line/path fixes   | Tidying stale lines/paths (`--fix`).  |
| `suggestions.md`                | non-asserting "did you mean" hints     | Acting on a GONE finding.             |
| `coverage.md`                   | **coverage-gap** — documentation gaps  | Finding docs worth writing/anchoring. |
| `worklist.md` / `worklist.json` | semantic-pass input                    | Driving or reviewing the Tier-3 pass. |
| `baseline.proposed.tsv`         | the next baseline this run would write | Adopting or refreshing the baseline.  |

**`report.md` — the drift report.** The one file to act on. Its summary counts the pending
**semantic worklist** (`review`/`unknown` topics) so a standalone engine run never reads as
"everything was checked" when the Tier-3 pass has not run. It opens with a **Scan coverage**
section — entries scanned, anchors extracted, checks resolved (the counting rule is stated in the
section itself) — so "no findings" is auditable as *checked and clean* rather than *never looked
at*, and a **Root causes (rollup)** section that groups findings sharing one underlying change: path
moves by their old-to-new rewrite, gone targets cited by several entries, and gone config keys by
the record that now declares a same-named key (a key-extraction refactor reads as one cause; the
migration direction is a hint per `suggestions.md`, not an asserted fact — the gone keys themselves
are the assertions). The findings are split into **new**, **carried**,
and **resolved** sections (from the baseline join, below). Each finding states the exact question
that was asked, one-look **evidence**, and the KB **occurrences** (line-hints) where the claim
appears. A finding is labelled **GONE** (the cited class/file is absent) or **MOVED** (it exists,
but in a different module than cited — a package/path move, reported instead of a false "gone").

**`findings.json` — the stable, machine-readable set.** The same findings as a JSON document under
the `kb-freshness/findings/v1` schema. It contains **only reproducible fields** — no dates, no
triage (those live in the baseline) — so it is **byte-identical across runs on the same checkout**,
which makes it safe to diff in CI: any change is real drift, not run-to-run noise. Each finding's
`id` is a stable hash of `(entry, target, check)` (see [Triage and the baseline](#triage-and-the-baseline)).

**`quiet-log.md` — the `unverifiable` lane.** Every check the engine deliberately refused to decide:
symbols that are generated or external (PBJ, protobuf, `.proto` files) and therefore have no source
the engine can parse. **This is not drift** — it is the audit trail proving the tool chose silence
over a guess. Skim it to confirm nothing that *should* be checkable is silently landing here.

**`auto-fix.md` — ready corrections.** Three shapes: a *named* symbol that still resolves but whose
cited line number moved (a navigation nit, never a drift finding), a **MOVED** source that
resolves at exactly one new path (a drift finding in `report.md`, repeated here as a ready
before/after path rewrite in whatever citation style the KB line uses, plus a stale on-line
`Module:` label), and a **MOVED** prose fully-qualified type rewritten to its new FQN. When the move
is also a *rename* — certain only for a config record located by its `@ConfigData` prefix (below) —
mentions of the old class name in link text and section headings are rewritten too. A path rewrite
whose citation carries a `:NN` line hint that cannot exist in the moved file (it exceeds the file's
length) keeps the rewrite but gains a re-verify note — the hint is navigation only and is never
asserted on, but an impossible one must not survive silently. These are the *certain* fixes, so
**`--fix` applies them in place** (guarded by an exact line match, hence idempotent); without it they
are shown for hand-editing.

**`suggestions.md` — non-asserting "did you mean" hints.** For each **GONE** target (a missing
cross-doc link, source path, bare source basename, or config key) the tool offers replacement
candidates — a definite git rename when history has one, else the commit that deleted the target,
plus the closest near-name matches against the KB docs or the source index. Where a hint is
unambiguous it is made **actionable**: a topics-slug tag with a single strong match becomes a
`rename topics: slug X → Y`, a body doc link resolving at exactly one other KB doc gets a ready
relative-link rewrite, a gone config key another `@ConfigData` record now declares is reported as a
**key migration**, and a source an ADR cites as removed gets a nudge to mark it `historical:`. A
closing **Prose naming moved packages** section lists doc lines still naming the *old package* of a
moved citation — text the ready rewrites cannot touch. **Hints, not facts** (it never asserts, and
`--fix` never applies them), and kept out of `findings.json` so the machine artifact stays reproducible.

**`coverage.md` — the `coverage-gap` lane.** Documentation gaps — the inverse of drift — in five
sections: (1) code the docs don't mention (a method present on a documented interface but absent
from its `methods:` frontmatter, or a `@ConfigProperty` its tunables section doesn't document);
(2) config *records* the tunables catalog has no section for at all — scoped to `consensus-*`
modules and modules the catalog already documents, so a key that migrates into a brand-new config
record cannot silently fall out of coverage; (3) architecture *topics* that anchor no source, so no
claim can be checked against code; (4) interface docs that carry no `interface:`/`methods:`
frontmatter, so the Tier-2 method-set diff never runs for them (making its dormancy visible rather
than reading as "all clear"); (5) cited topic *slugs* whose document does not exist — when several
entries tag a topic that was never written, the fix may be to write it rather than retarget every
citation. Use it to find documentation worth adding or anchoring; tracked apart from the drift
report on purpose.

**`worklist.md` / `worklist.json` — the semantic-pass input.** For each topic, the engine compares
the last-commit date of its anchored source against its `last_reviewed` date and assigns a status:
`review` (a source was committed on or after `last_reviewed`), `fresh` (every source predates it), or
`unknown` (freshness can't be determined — the entry's note names the reason: no anchored sources, git
unavailable, or no commit dates). Anchored source includes the KB's abbreviated inline citations (`module/.../File.java`),
resolved through the source index — so a topic anchored only in that style is tracked, not dropped to
`unknown`. A **moved** anchor — a citation whose location is stale but whose basename resolves at
exactly one other indexed path — is tracked at its *new* location: the topics whose code moved
wholesale are exactly the ones whose prose most needs re-reading, so a move must never silently
weaken the freshness signal. A topic that genuinely anchors nothing (`anchoredSourceCount` 0) is
surfaced in `coverage.md`. The semantic pass consumes the JSON and reads only the
`review`/`unknown` entries.

**`baseline.proposed.tsv` — the next baseline.** The baseline this run *would* write. Adopt it with
`--write-baseline`, or copy it over the committed baseline, then triage the rows.

## Triage and the baseline

The baseline (`baseline/kb-freshness-baseline.tsv`) is a human-owned, version-controlled record of
`{id, triage, first_seen, note}`. Each run joins the current findings against it by `id`:

- an `id` only in the current run → **new drift** (the signal);
- an `id` in both → its triage carries forward;
- an `id` only in the baseline → **resolved**, auto-closed.

### Dismissing a false positive

1. Find the finding's `id` in `report.md` (or `findings.json`).
2. Add a row to `baseline/kb-freshness-baseline.tsv`:

   ```
   <id><TAB>dismissed<TAB>2026-07-06<TAB>false positive: <reason>
   ```

   (`accepted` and `deferred` are the other dispositions.)

3. Re-run: the finding is suppressed from the report.

Because a finding's identity is keyed on **what the KB says** — entry + target + check, *never* line
numbers or file paths — a dismissal can't silently silence a *different* problem: if the KB claim
later changes, the finding gets a new `id` and re-surfaces as **new**.

To adopt the current findings wholesale as the baseline, run with `--write-baseline` (or copy
`baseline.proposed.tsv` over the baseline) and then triage the rows.

## What it checks (and deliberately doesn't)

- **Tier 0** — files, module dirs, cross-doc links, `#headings`, catalog IDs (INV/RUL/ADR/SCN/HEU/
  SYM/TUN), existence of cited allowlisted-external files (present resolves cleanly; missing
  stays quiet but is flagged), and prose-cited Java packages (backtick reverse-domain names; a parent
  of an indexed package counts as existing, and absence asserts only when the package's own two-segment
  namespace is indexed — an external library package is quiet, never a guess). Catalog `README.md`
  index files are scanned like entries — their rows are a sanctioned duplication with a sync
  obligation — while `FORMAT`/`LAYOUT`/`CLAUDE` stay unscanned (placeholder examples by design).
  Fenced code blocks and HTML comments are never claims.
- **Tier 1** — class/file existence in the cited module (with package-move detection), prose
  fully-qualified type citations (resolved by package + simple name, under the same indexed-namespace
  guard; a unique move gets a ready FQN rewrite), `verification:` method-on-class, and
  tunables-catalog key existence (each documented key must be a declared `@ConfigProperty` of its
  section's `@ConfigData` record).
- **Tier 2** — method-signature equality (`Class.method(params)` citations), interface method-set
  diffs (opt-in via `interface:`/`methods:` frontmatter; undocumented methods → coverage lane), and
  tunables-catalog default equality (documented default vs the `defaultValue` string literal).
  Non-literal defaults and type differences → quiet log (except the closed well-known-constant
  whitelist, e.g. `Configuration.EMPTY_LIST` = `[]`); undocumented keys and whole undocumented config
  records → coverage lane. A section whose cited config class is gone is additionally resolved by its
  `@ConfigData` **prefix**: exactly one indexed record declaring the prefix *and* every documented key
  is a certain rename/move — asserted, with the heading/`Source:`/`Module:` rewrite ready for `--fix`,
  and the Tier-0 GONE finding for the same citation subsumed rather than double-reported.
- **Tier 3 (semantic)** — prose-vs-behavior, performed by the skill against current source; advisory
  only, `contradicted`-with-citation only.

It does **not** assert on loose prose, generated/external symbols (PBJ/protobuf — see
`allowlist.example.txt`), invariant *design arguments* (which carry no code anchor by design), or
line numbers. Those are routed to the quiet log, handled by the semantic pass, or emitted as
non-asserting auto-fix / coverage lanes.
