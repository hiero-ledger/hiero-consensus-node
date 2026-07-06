# kb-freshness

A deterministic checker that detects when the curated consensus-layer knowledge base
(`platform-sdk/docs/consensus-layer/`) has drifted out of sync with the code it documents, so a
human curator can fix it.

It is **manual-first** and **precision-first**: long runs are fine, but a false alarm is the worst
outcome, so every assertion carries evidence a curator can verify in one look, and anything uncertain
is kept quiet rather than reported.

## Two ways to run it

### 1. Via the skill (recommended — adds the semantic pass)

Start Claude Code **inside this module** (`platform-sdk/kb-freshness/`) so the skill is discovered,
then invoke the `kb-freshness` skill. It runs the deterministic engine, then reads the semantic
worklist and checks each changed topic's prose against the current source, and presents a combined
report with a clearly separated **Advisory (semantic)** section.

### 2. The deterministic core, standalone (no model)

```bash
./gradlew :kb-freshness:run --args="\
  --kb platform-sdk/docs/consensus-layer \
  --repo $(pwd) \
  --out build/kb-freshness \
  --baseline platform-sdk/kb-freshness/baseline/kb-freshness-baseline.tsv"
```

Or as a standalone jar, no Gradle:

```bash
./gradlew :kb-freshness:assemble
java -jar build/libs/kb-freshness-*-all.jar --kb platform-sdk/docs/consensus-layer --repo "$(pwd)"
```

Key options (`--help` for all): `--kb` (required), `--repo`, `--out`, `--baseline`, `--modules`
(default `platform-sdk,hedera-node`), `--allowlist`, `--date`, `--write-baseline`, `--fail-on-drift`
(exit 2 on new drift, for future CI).

## Reading the output

The run writes to `--out` (default `build/kb-freshness/`):

|              File               |                                             What it is                                              |
|---------------------------------|-----------------------------------------------------------------------------------------------------|
| `report.md`                     | **The drift report.** New drift (the signal), carried drift, resolved. Act on this.                 |
| `findings.json`                 | Machine-readable finding set; stable ids; byte-identical across runs.                               |
| `quiet-log.md`                  | `unverifiable` checks (generated/external symbols). **Not** drift.                                  |
| `auto-fix.md`                   | Proposed line-reference corrections (a symbol resolves but its cited line moved). Suggestions only. |
| `coverage.md`                   | Undocumented code (coverage lane). **Not** drift.                                                   |
| `worklist.md` / `worklist.json` | Topics whose anchored source changed since `last_reviewed` — the semantic pass's input.             |
| `baseline.proposed.tsv`         | The baseline the current run would write back.                                                      |

A report finding reads e.g. **GONE** (the cited class/file is absent) or **MOVED** (it exists, but in
a different module than cited — a package/path move). Each carries the exact question asked, one-look
evidence, and the occurrence line-hints in the KB.

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

Because a finding's identity is keyed on **what the KB says** (entry + target + check — never line
numbers or file paths), a dismissal can never silently silence a *different* problem: if the KB claim
later changes, the finding gets a new id and re-surfaces as **new**.

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
line numbers. Those are either routed to the quiet log, handled by the semantic pass, or emitted as
non-asserting auto-fix/coverage lanes.
