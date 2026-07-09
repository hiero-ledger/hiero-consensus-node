# consensus-kb-freshness

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

|    Tier    |                                                         What it verifies                                                          |                                                    How                                                    |                                     Asserts drift?                                     |
|------------|-----------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------|
| **Tier 0** | Existence of cited files, module directories, cross-doc links, `#headings`, and catalog IDs (INV/RUL/ADR/SCN/HEU/SYM/TUN).        | Filesystem + text. Near-zero false-positive risk.                                                         | Yes — a cited file, link target, or catalog ID that is simply absent.                  |
| **Tier 1** | A cited *type* still exists in the *module the doc names*; a `verification:` method still exists on its class.                    | Parse the cited source; look for the declared type/method.                                                | Yes — but a type found in a *different* module is reported as a **move**, not as gone. |
| **Tier 2** | A cited *method signature* still matches (parameters/return), and a documented *interface's method set* still matches the source. | Parse and compare *as-written* signatures. Opt-in for interfaces via `interface:`/`methods:` frontmatter. | Yes — a signature that no longer matches, or a documented method that is gone.         |
| **Tier 3** | *Behavioral* prose claims — what the code actually does.                                                                          | The **semantic pass** (the skill). Not deterministic.                                                     | No — advisory only, and only a `contradicted`-with-citation claim.                     |

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

The worklist that drives it is built by the engine from git history: for each topic it compares the
last-commit date of the topic's anchored source against the topic's `last_reviewed` date.

## Lanes — how a result is routed

Not every resolved check is "drift." Each is routed into one of four **lanes**, and each lane has its
own output file so the drift report itself stays pure signal:

- **assert** → `report.md`. A certain `absent` (or a move). The drift a curator acts on.
- **quiet-log** → `quiet-log.md`. `unverifiable` — the symbol is generated or external (PBJ,
  protobuf, `.proto` sources), so the engine cannot see its source and refuses to guess. Not drift.
- **auto-fix** → `auto-fix.md`. The symbol resolves but a cited line number moved. A suggested
  correction, never applied automatically. Not drift.
- **coverage-gap** → `coverage.md`. The *code* has something the *docs* do not (e.g. an interface
  method with no documentation). The inverse of drift; tracked separately.

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

|        Option        |           Default           |                                        Purpose                                         |
|----------------------|-----------------------------|----------------------------------------------------------------------------------------|
| `--kb <path>`        | *(required)*                | KB root to scan; resolved against `--repo`.                                            |
| `--repo <path>`      | `.`                         | Repo root all relative paths and source resolution anchor to.                          |
| `--out <dir>`        | `<repo>/build/kb-freshness` | Where the artifacts are written.                                                       |
| `--baseline <file>`  | *(none)*                    | Baseline TSV to join findings against (see below).                                     |
| `--modules <csv>`    | `platform-sdk,hedera-node`  | Source roots to index for symbol resolution.                                           |
| `--allowlist <file>` | *(none)*                    | Extra generated/external allowlist directives (see `allowlist.example.txt`).           |
| `--date <str>`       | `""`                        | Run date recorded as `first_seen` for newly-seen findings.                             |
| `--write-baseline`   | off                         | Overwrite `--baseline` with the proposed baseline.                                     |
| `--fail-on-drift`    | off                         | Exit `2` if any new (not-baselined, not-dismissed) assertion is found — for future CI. |

Exit codes: `0` success · `1` usage/IO error · `2` new drift with `--fail-on-drift`.

## The generated files

A run writes all artifacts into `--out` (default `build/kb-freshness/`). Exactly one of them is the
drift report to act on; the rest are supporting lanes and inputs.

|              File               |              Lane / role               |             Read it when              |
|---------------------------------|----------------------------------------|---------------------------------------|
| `report.md`                     | **assert** — the drift report          | Always. This is the signal.           |
| `findings.json`                 | machine-readable finding set           | Tooling / CI; diffing two runs.       |
| `quiet-log.md`                  | **quiet-log** — `unverifiable`         | Auditing what was skipped, and why.   |
| `auto-fix.md`                   | **auto-fix** — line corrections        | Tidying stale line numbers.           |
| `coverage.md`                   | **coverage-gap** — undocumented code   | Finding docs worth writing.           |
| `worklist.md` / `worklist.json` | semantic-pass input                    | Driving or reviewing the Tier-3 pass. |
| `baseline.proposed.tsv`         | the next baseline this run would write | Adopting or refreshing the baseline.  |

**`report.md` — the drift report.** The one file to act on. It is split into **new**, **carried**,
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

**`auto-fix.md` — the `auto-fix` lane.** Cases where a *named* symbol still resolves but its cited
line number moved. Each entry is a proposed line-reference correction. **Never applied
automatically** and never a drift finding — a moved line is a navigation nit, not a broken claim.

**`coverage.md` — the `coverage-gap` lane.** The inverse of drift: code the docs don't mention — for
example, a method present on a documented interface but absent from that interface's `methods:`
frontmatter. Use it to find documentation worth adding; it is tracked apart from the drift report on
purpose.

**`worklist.md` / `worklist.json` — the semantic-pass input.** For each topic, the engine compares
the last-commit date of its anchored source against its `last_reviewed` date and assigns a status:
`review` (source changed since last review), `fresh` (up to date), or `unknown` (freshness can't be
determined). The semantic pass consumes the JSON and reads only the `review`/`unknown` entries.

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
  SYM/TUN).
- **Tier 1** — class/file existence in the cited module (with package-move detection), and
  `verification:` method-on-class.
- **Tier 2** — method-signature equality (`Class.method(params)` citations) and interface method-set
  diffs (opt-in via `interface:`/`methods:` frontmatter; undocumented methods → coverage lane).
- **Tier 3 (semantic)** — prose-vs-behavior, performed by the skill against current source; advisory
  only, `contradicted`-with-citation only.

It does **not** assert on loose prose, generated/external symbols (PBJ/protobuf — see
`allowlist.example.txt`), invariant *design arguments* (which carry no code anchor by design), or
line numbers. Those are routed to the quiet log, handled by the semantic pass, or emitted as
non-asserting auto-fix / coverage lanes.
