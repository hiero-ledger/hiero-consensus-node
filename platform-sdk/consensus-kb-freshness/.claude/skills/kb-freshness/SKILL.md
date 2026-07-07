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

- `report.md` — the human drift report (deterministic assertions a curator acts on).
- `findings.json` — the machine-readable finding set (stable ids; reproducible).
- `quiet-log.md` — unverifiable checks (generated/external symbols) — **not** drift.
- `auto-fix.md` — proposed line-reference corrections (suggestions only).
- `coverage.md` — undocumented code (coverage lane) — **not** drift.
- `worklist.json` — the semantic worklist (below).

Do not re-derive or second-guess the deterministic findings; present them as-is.

## Step 2 — Semantic pass (you perform this)

Read `worklist.json`. Process **only** entries whose `status` is `review` or `unknown` (their
anchored source changed since `last_reviewed`, or freshness is unknown). Skip `fresh` entries.

For each such entry:

1. Read the topic doc at `entryPath`.
2. Read the **current** source files listed in `changedPaths` (and any other source the topic
   anchors). Never rely on memory of what the code does — open the files.
3. For each **load-bearing prose claim** about behavior in the topic, judge it three ways:
   - `supported` — the current code backs the claim.
   - `contradicted` — the current code makes the claim false.
   - `can't-determine` — you cannot tell from the source available.

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
1. The deterministic **report.md** (new drift, carried drift, resolved), summarized.
2. Pointers to `quiet-log.md`, `auto-fix.md`, `coverage.md` for the non-drift lanes.
3. Your **`## Advisory (semantic)`** section (or "none" if nothing survived).

If the user asks to triage or dismiss a finding, explain the baseline flow (see the module README):
add the finding's `id` to `platform-sdk/consensus-kb-freshness/baseline/kb-freshness-baseline.tsv` with
`accepted`/`dismissed`/`deferred`. Do not edit the baseline yourself unless asked.
