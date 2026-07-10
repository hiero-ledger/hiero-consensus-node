# consensus-kb-freshness — module guide

Deterministic drift checker for the curated consensus-layer KB (`platform-sdk/docs/consensus-layer/`).
This file covers what differs from repo defaults; see `README.md` for user-facing usage.

## Commands

```bash
./gradlew :consensus-kb-freshness:build          # compile + javadoc + spotless + tests + checks
./gradlew :consensus-kb-freshness:test           # unit + fixture tests
./gradlew :consensus-kb-freshness:qualityGate    # compile + checks + auto-format (spotlessApply)
./gradlew :consensus-kb-freshness:run --args="--kb platform-sdk/docs/consensus-layer --repo $(pwd) --out build/kb-freshness"
```

The application module also produces a standalone shadow jar (`org.hiero.gradle.feature.shadow`):
`./gradlew :consensus-kb-freshness:assemble` → `build/libs/consensus-kb-freshness-*-all.jar`, runnable
with `java -jar`.

## Structure

- `extract/` — KB scanner + minimal-YAML frontmatter parser + anchor extractor + `TunablesCatalog`
  (parses tunables.md sections/rows for the config-record checks).
- `resolve/` — parse-only source index (`JavaParsing` via the JDK Compiler Tree API; also reads
  `@ConfigData` prefixes and `@ConfigProperty` record components), the generated/external
  `Allowlist`, and `AnchorResolver` (Tier 0/1/2 per-anchor checks).
- `findings/` — collapse to stable-id findings, `InterfaceDiffAssembler` (Tier 2 method-set diff),
  `TunablesDiffAssembler` (Tier 1/2 config key/default/prefix checks), baseline TSV + join.
- `worklist/` + `git/` — the semantic worklist (git freshness vs `last_reviewed`). Anchored-source
  resolution mirrors the resolver: abbreviated `module/.../File.java` citations are resolved through the
  `SourceIndex` (by basename within the cited module), so an abbreviated-only topic is tracked rather
  than reported as having no anchored sources — and a *moved* anchor (stale location, basename resolving
  at exactly one other indexed path) is tracked at its new location, so the topics whose code moved
  wholesale keep feeding the freshness signal instead of silently dropping out. Each entry carries
  `anchoredSourceCount`; a zero count (topic anchors nothing) is surfaced in the coverage lane, not the
  drift report.
- `engine/` also carries `ScanStats` — what the run scanned and checked (entries, anchors, check
  groups, findings by lane, Tier-2 surfaces), rendered as the report's "Scan coverage" section so
  silence is auditable as checked-and-clean rather than never-scanned.
- `render/` — report / quiet-log / auto-fix / suggestions / coverage / findings.json / worklist renderers.
  The report also renders a "Root causes (rollup)" section grouping moves by (old path → new path) and
  gone targets cited by multiple entries — one code move often explains dozens of findings.
  `AutoFix` is the shared planner (structured `Edit`s) that both `AutoFixRenderer` (Markdown) and
  `apply/AutoFixApplier` (writes) consume, so the proposal a curator reads is exactly the edit `--fix`
  would apply. (`suggestions.md` = non-asserting "did you mean" hints for GONE targets: git rename first,
  else the deleting commit, plus guarded fuzzy basename matches — a unique strong topics-slug match is
  promoted to an actionable `rename topics: slug X → Y`, and an ADR-cited gone source gets a `historical:`
  nudge. Frontmatter-title tokens and pool-unique distinctive tokens also score, capped below promotion
  strength — they may offer, never promote. Deliberately excluded from `findings.json` to keep it
  reproducible.)
- `apply/` — `AutoFixApplier` (`--fix`): writes the certain auto-fix `Edit`s to the KB in place, guarded
  by an exact line match (idempotent); never applies fuzzy `suggestions.md` renames.
- `engine/` + `cli/` — orchestration and the picocli entry point.
- `.claude/skills/kb-freshness/` — the skill that runs the engine and performs the semantic pass.
- `baseline/kb-freshness-baseline.tsv` — the committed, human-owned baseline.

## Design invariants (do not regress)

- **Three-valued outcomes**: `present` / `absent` / `unverifiable`. Only certain-`absent` (and a
  package/path-move `present`) asserts into the report. When in doubt → `unverifiable` (quiet log).
  A package/path move that resolves at exactly one new location still asserts, but also carries
  `resolvedPath` (in `findings.json`) and a ready path-rewrite diff in `auto-fix.md`.
- **Never assert on line numbers.** A moved line for a *named* symbol → an `auto-fix` proposal, never
  an assert. Bare `File.java:NN` links carry no line (the KB uses them for members too).
- **`--fix` applies only the certain fixes** (moved lines, unique path moves, on-line `Module:` label,
  and — for a config record located by its `@ConfigData` prefix — the renamed class in headings/link
  text) — the exact `auto-fix.md` diffs, guarded by a full-line before-match so it is idempotent. It
  must never apply fuzzy `suggestions.md` renames (topics-slug, near-name): those need a human decision.
- **Tunables checks assert only on literal facts.** A documented key missing from its resolved
  `@ConfigData` record asserts; a documented default differing from a plain-literal `defaultValue`
  asserts. A *type* difference is quiet-log only (the catalog documents semantic types, e.g. `Path`
  for a `String` key); a non-literal `defaultValue` (constant reference) is quiet. Prefix-based
  resolution of a gone config class asserts a move only when exactly one indexed record declares the
  prefix **and** declares every documented key — do not weaken either guard.
- **External files still get the Tier-0 existence look.** An allowlisted external path that exists
  resolves cleanly (no quiet-log noise); a missing one stays unverifiable (it may be generated at
  build time) but its quiet-log evidence flags the absence. Never assert absence for external paths.
- **Determinism**: `findings.json` is byte-identical across runs — it contains only reproducible
  fields (no dates, no triage; those live in the baseline). Keep all ordering stable.
- **Identity** = hash of `(entry key, target, check kind)` — no line numbers, no file path — so a
  finding survives file moves and code renames until the KB claim itself changes.

## Gotchas that differ from repo defaults

- **No Javadoc block above `module {}` in `module-info.java`.** Spotless mis-reflows a `/** … */`
  before a module declaration and pulls the `exports` line *into* the comment (silently dropping the
  export). Use `//` line comments on `module-info`, like the other modules.
- **Parse-only, no build of the target.** `JavaParsing` uses `JavacTask.parse()` (not `.analyze()`),
  so it reads *declared* symbols and *as-written* signatures without a classpath. It needs
  `requires jdk.compiler` (for `com.sun.source.*`) and `requires java.compiler` (for `javax.tools`).
- **Tier-2 interface method-set diff is opt-in.** It fires only on `architecture/interfaces/*`
  entries with explicit frontmatter `interface:` (a platform-sdk-relative source path) and
  `methods:` (documented names) — see `InterfaceDiffAssembler.optsIntoTier2`. Loose interface prose is
  deliberately left to the semantic pass to avoid false positives — do not "improve" it into scraping
  prose. Interface docs that do *not* opt in are surfaced in the coverage lane so the dormancy is
  visible rather than reading as "all clear".
- **`components:`/`verification:` paths are platform-sdk-relative** (first segment = module dir); the
  extractor prefixes `platform-sdk/`. Body code spans accept both the full `platform-sdk/…` form and
  the same module-relative form (`<module>/src/…`). Markdown links resolve relative to the doc's
  directory.
- **Frontmatter `topics:` slugs fall back to `architecture/interfaces/<slug>.md`** when no topic doc
  exists (resolver-side, so finding ids stay keyed on the topics/ target). Real body links get no
  fallback — their href must resolve as written.
- **`historical:` frontmatter marks expected-gone sources** (deliberately deleted code cited as
  history, e.g. in an ADR describing the removal): gone → quiet log; still existing → assert.
- **The skill is module-local**: it lives in this module's `.claude/skills/` and is discovered only
  when Claude Code starts within `platform-sdk/consensus-kb-freshness/` (or a subdirectory).
