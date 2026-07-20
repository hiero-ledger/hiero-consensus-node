---
name: kb-freshness
description: Check the consensus-layer knowledge base (platform-sdk/docs/consensus-layer) for drift against the code. Runs the deterministic engine, then performs the semantic (prose-vs-code) reading and presents a combined report. Use when asked to check, refresh, or audit the consensus-layer KB.
---

# KB freshness — orchestrate a run + semantic pass

You are running the consensus-layer KB freshness check. It has two layers:

1. A **deterministic Java engine** that resolves the KB's code anchors against the current checkout
   and emits machine-readable findings. It **never** guesses — every assertion carries one-look
   evidence.
2. A **semantic pass that you perform** — reading a topic's prose claims against the *current source
   the engine located*, never from memory. This catches drift that is true-but-no-longer-accurate
   prose, which no deterministic check can safely assert.

Follow these steps exactly. **Do not modify any KB or source files** — this check only reports.

## Step 1 — Run the deterministic engine

Run the bundled script and capture the output directory:

```bash
bash "${CLAUDE_SKILL_DIR}/scripts/run.sh"
```

It prints the output directory (default `<repo>/build/kb-freshness`). Read these artifacts from it:

- `report.md` — the human drift report (deterministic assertions a curator acts on). Its **Summary**
  counts the pending semantic worklist (your Step 2 workload); its **Scan coverage** section states
  what was scanned and checked; its **Root causes (rollup)** section groups findings that share one
  underlying change.
- `findings.json` — the machine-readable finding set (stable ids; reproducible).
- `quiet-log.md` — unverifiable checks (generated/external symbols) — **not** drift.
- `auto-fix.md` — proposed line-reference and path corrections (applied only by `--fix`).
- `suggestions.md` — non-asserting did-you-mean hints for gone targets, including config-key
  migrations (a gone key another record now declares) and ready link rewrites for misdirected doc
  links.
- `coverage.md` — documentation gaps (coverage lane): undocumented code and config keys, config
  records with no tunables section at all, topics anchoring no source, interface docs not checked at
  Tier-2, and cited topic slugs with no document — **not** drift.
- `worklist.json` — the semantic worklist (below).

Do not re-derive or second-guess the deterministic findings; present them as-is.

## Step 2 — Semantic pass (you perform this)

Read `worklist.json`. Process **only** entries whose `status` is `review` or `unknown` (their
anchored source changed since `last_reviewed`, or freshness is unknown). Skip `fresh` entries.

**Fan out for large worklists.** When more than ~4 topics need processing, or a single topic lists
many changed sources (say, 10+), spawn one subagent per topic instead of reading everything in one
context: each subagent reads only its topic doc and that topic's changed sources, judges the claims
by the rules below, and returns its `contradicted`-with-citation items (file + symbol/line per
claim). Merge the returns into one Advisory section, applying the same only-cited-contradictions
bar to what comes back — a subagent's uncited judgment is dropped exactly like your own. Small
worklists are faster inline; do not fan out for one or two topics.

For each `review` entry:

1. Read the topic doc at `entryPath`.
2. Read the **current** source files listed in `changedPaths` (and any other source the topic
   anchors). Never rely on memory of what the code does — open the files.
3. For each **load-bearing prose claim** about behavior in the topic, judge it three ways:
   - `supported` — the current code backs the claim.
   - `contradicted` — the current code makes the claim false.
   - `can't-determine` — you cannot tell from the source available.

An `unknown` entry has no `changedPaths` to read — its `note` names why (usually
`no anchored sources`). Handle it by the note:

- `no anchored sources` — the doc carries no mechanically-checkable code anchor (it also appears in
  `coverage.md`). Read the doc, **locate** the code it describes (search by the class/component names
  in its prose), and judge its claims against what you find. If you can identify the code, also
  recommend anchoring the doc to it (add source citations) so future runs can track freshness. If you
  cannot identify the code, say so — do not guess.
- `git unavailable` / `no commit dates for anchored sources` — freshness could not be dated; treat the
  entry like `review` and read the sources the doc anchors.

## Step 3 — Report only contradicted-with-citation

- Keep **only** `contradicted` claims, and **only** those where you can cite the specific current
  code (file + symbol/line) that contradicts the specific claim. Drop `supported`,
  `can't-determine`, and any `contradicted` you cannot cite.
- Present them in a clearly separated **`## Advisory (semantic)`** section, *after* and *distinct
  from* the deterministic report. Never intermix semantic findings with deterministic assertions —
  they are advisory, not facts the engine verified.

Each advisory item cites: the topic + claim, and the current code (path + symbol) that contradicts
it. An uncited judgment is dropped, not reported.

## Step 4 — Present the combined result

Show, in order:
1. The deterministic **report.md** (new drift, carried drift, resolved), summarized — lead with the
   **Root causes (rollup)** section when present: one underlying code move often explains dozens of
   findings, and the curator should read cause-level first.
2. Pointers to `quiet-log.md`, `auto-fix.md`, `coverage.md` for the non-drift lanes.
3. Your **`## Advisory (semantic)`** section (or "none" if nothing survived).

## Step 5 — Recommend next actions

Close with a short, concrete action list derived from this run (skip lines that do not apply):

1. **Apply the certain fixes**: if the summary counts anything under "Fixable now with `--fix`",
   suggest re-running the engine with `--fix` (it applies exactly the `auto-fix.md` diffs).
2. **Hand-fix the GONE findings**: point at `suggestions.md` for did-you-mean hints — including
   config-key migration hints and ready link rewrites; all of them need a human decision and are
   never auto-applied.
3. **Close coverage gaps**: mention `coverage.md` items worth acting on (unanchored topics, interface
   docs not opted into Tier-2, undocumented config keys, config records with no tunables section,
   topic slugs with no document).
4. **Close the review loop**: for each worklisted topic whose semantic pass found every claim
   `supported` (or whose contradictions have since been fixed), suggest bumping its `last_reviewed`
   date — mechanically, via `--mark-reviewed <entry-key>[=<yyyy-MM-dd>]` (repeatable; a spec without
   a date uses `--date`). Without the bump, every future run re-worklists the same topics. Never
   suggest bumping a topic that still has an unresolved contradiction.
5. **Adopt the baseline**: after fixes are applied and re-checked, suggest `--write-baseline` (or
   copying `baseline.proposed.tsv`) and triaging the rows.

Do **not** perform any of these yourself unless the user asks.

If the user asks to triage or dismiss a finding, explain the baseline flow (see the module README):
add the finding's `id` to `platform-sdk/consensus-kb-freshness/baseline/kb-freshness-baseline.tsv` with
`accepted`/`dismissed`/`deferred`. Do not edit the baseline yourself unless asked.
