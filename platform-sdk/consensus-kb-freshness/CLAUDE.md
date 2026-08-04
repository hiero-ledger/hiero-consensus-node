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
  (parses tunables.md sections/rows for the config-record checks). Catalog `README.md` files are
  scanned as `INDEX` entries (their rows carry a sync obligation); `FORMAT`/`LAYOUT`/`CLAUDE` are
  skipped (placeholder examples by design). Backtick code spans also yield fully-qualified type
  anchors (`CLASS`, primary type in `citedScope`) and package anchors (`PACKAGE_REF` — reverse-domain
  root plus ≥ 3 segments, so a config prefix like `state.management.wiring` can never read as a
  package claim). Fenced code blocks and HTML comments are blanked before anchor extraction —
  commented-out text is not a claim.
- `resolve/` — parse-only source index (`JavaParsing` via the JDK Compiler Tree API; also reads
  `@ConfigData` prefixes and `@ConfigProperty` record components, plus the as-written expression of a
  non-literal `defaultValue`; the index additionally records every `src/main/java` package for the
  prose package/FQN checks), the generated/external `Allowlist`, `AnchorResolver` (Tier 0/1/2
  per-anchor checks), and `ConfigRecords` (the shared scan of every indexed `@ConfigData` record —
  `src/main/java` trees only, so a test-resource fixture copy never masquerades as a real record).
- `findings/` — collapse to stable-id findings, `InterfaceDiffAssembler` (Tier 2 method-set diff),
  `TunablesDiffAssembler` (Tier 1/2 config key/default/prefix checks; also the undocumented-record
  coverage check, scoped to `consensus-*` modules plus modules the catalog already documents),
  baseline TSV + join. The engine subsumes a Tier-0 source-path GONE finding when a `CONFIG_PREFIX`
  finding already asserts the same citation as a class move (one root cause, one finding).
- `worklist/` + `git/` — the semantic worklist (git freshness vs `last_reviewed`), built for **every**
  scanned document: it is flagged for review when any anchored source was committed **on or after**
  `last_reviewed`. The boundary is inclusive because commit dates are day-granular — a same-day merge is
  never skipped, at the cost of not clearing a document until the day after its last change. A document
  that anchors no code is `unknown` regardless of its marker (the no-sources check runs first).
  Anchored-source resolution mirrors the resolver (abbreviated `module/.../File.java` and FQN citations
  both resolve through the `SourceIndex`, and a moved anchor is tracked at its new location), so an
  abbreviated- or FQN-only document keeps feeding the freshness signal. Each entry carries
  `anchoredSourceCount` and `newestAnchoredCommit` (the reviewed-state date `--mark-reviewed` records);
  a zero count (anchors nothing) reads as `unknown`, and — for topics only — is surfaced in the coverage
  lane.
- `engine/` also carries `ScanStats` — what the run scanned and checked (entries, anchors, check
  groups, findings by lane, Tier-2 surfaces), rendered as the report's "Scan coverage" section so
  silence is auditable as checked-and-clean rather than never-scanned.
- `render/` — the per-lane renderers (report, quiet-log, auto-fix, suggestions, coverage, findings.json,
  worklist) plus `Md` (shared section/header helpers). The report adds a "Root causes (rollup)" section
  grouping findings that share one code move. `AutoFix` is the shared planner (structured `Edit`s) that
  both `AutoFixRenderer` (Markdown) and `apply/AutoFixApplier` (writes) consume, so the proposal a curator
  reads is exactly the edit `--fix` applies. `SuggestionsRenderer` emits the non-asserting "did you mean"
  hints for GONE targets (scoring in `findings/NearNameMatcher`), kept out of `findings.json` so that
  artifact stays reproducible. See `README.md` for the full suggestion and rollup semantics.
- `apply/` — `AutoFixApplier` (`--fix`): writes the certain auto-fix `Edit`s to the KB in place, guarded
  by an exact line match (idempotent); never applies fuzzy `suggestions.md` renames. `ReviewedMarker`
  (`--mark-reviewed <key>[=<date>]`): bumps an entry's *existing* `last_reviewed:` frontmatter line —
  the workflow closure after a semantic pass; it never invents the line, requires an unambiguous key
  and an ISO date; a bare spec derives the reviewed-state date from git — the document's newest
  anchored-source commit, or the checkout's `HEAD` commit for an unanchored doc — never wall-clock; see
  `README.md` — and is idempotent.
- `engine/` + `cli/` — orchestration and the picocli entry point.
- `.claude/skills/kb-freshness/` — the skill that runs the engine and performs the semantic pass.
- `baseline/kb-freshness-baseline.tsv` — the committed, human-owned baseline.

## Design invariants (do not regress)

- **Three-valued outcomes**: `present` / `absent` / `unverifiable`. Only certain-`absent` (and a
  package/path-move `present`) asserts into the report. When in doubt → `unverifiable` (quiet log).
  A package/path move that resolves at exactly one new location still asserts, but also carries
  `resolvedPath` (in `findings.json`) and a ready path-rewrite diff in `auto-fix.md`.
- **Never assert on line numbers; migrate them to symbols.** A `File.java:NN` whose line NN is exactly a
  declaration auto-migrates to `File.java#symbol` — a `SOURCE_SYMBOL` anchor checking the
  method/field/enum-constant/type exists (which *does* assert on a rename or removal). A `:NN` inside a
  body or past end-of-file is left untouched (a follow-up pass will suggest a git-tracked line). A moved
  *method-link* line → an `auto-fix` proposal, never an assert. A stale-hint note on a path rewrite is
  header text only — never a finding, never a blocked edit.
- **Package/FQN absence asserts only inside indexed namespaces.** A prose package or fully-qualified
  type whose two-segment namespace (`com.swirlds`, `org.hiero`, …) contains no indexed package is
  external — quiet log, never an assert. Package existence is prefix-based (a parent of an indexed
  package exists); package extraction requires a reverse-domain root and ≥ 3 segments so dotted
  non-packages (config prefixes, JPMS-ish values with other roots) are never extracted. Do not weaken
  these guards.
- **`--fix` applies only the certain fixes** (moved lines, declaration-line→`#symbol` migrations, unique
  path moves, on-line `Module:` label, and — for a config record located by its `@ConfigData` prefix —
  the renamed class in headings/link text) — the exact `auto-fix.md` diffs, guarded by a full-line
  before-match so it is idempotent (two refs on one line take a second run to converge). It must never
  apply fuzzy `suggestions.md` renames (topics-slug, near-name): those need a human decision.
- **Tunables checks assert only on literal facts.** A documented key missing from its resolved
  `@ConfigData` record asserts; a documented default differing from a plain-literal `defaultValue`
  asserts. A *type* difference is quiet-log only (the catalog documents semantic types, e.g. `Path`
  for a `String` key); a non-literal `defaultValue` (constant reference) is quiet — except the closed
  `WELL_KNOWN_DEFAULTS` whitelist (`Configuration.EMPTY_LIST` = `[]`), whose values are compile-time
  facts of the config API and compare as literals. Extend that whitelist only with constants whose
  values are equally fixed. Prefix-based resolution of a gone config class asserts a move only when
  exactly one indexed record declares the prefix **and** declares every documented key — do not weaken
  either guard. When it fires, the engine drops the co-located Tier-0 source-path GONE finding
  (`Engine.subsumeConfigClassMoves`) so one root cause is one finding.
- **Coverage stays scoped and quiet.** The undocumented-record check lists a config record only when
  its module is `consensus-*` or already documented by the catalog, and only records under the
  module's own `src/main/java` (see `ConfigRecords.isMainSource` — fixture copies never count). It is
  coverage-lane only; never assert from it.
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

## Documentation proportionality (do not regress)

Match documentation weight to a thing's importance, not to how recently it was added.

- The base pipeline (extract → resolve → findings → render) and the core model types earn the
  "explain the why + one-look evidence" register. Auxiliary and non-asserting lanes
  (suggestions/near-name, cosmetic auto-fix, coverage, mark-reviewed) get a one- or two-sentence class
  doc and **no** rationale essays on private constants or helpers.
- Design rationale lives **once** — the invariants above and the `README.md` — and other docs point to
  it rather than restating it. A new feature that repeats an existing rule in a third place is drift.
- Omit `@param`/`@return` and inline comments that only restate the signature or the class doc
  (Checkstyle allows missing tags; the `javadoc` task runs `Xdoclint:all,-missing`). Keep changelog
  history in git, not in Javadoc.
