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

- `extract/` — KB scanner + minimal-YAML frontmatter parser + anchor extractor.
- `resolve/` — parse-only source index (`JavaParsing` via the JDK Compiler Tree API), the generated/
  external `Allowlist`, and `AnchorResolver` (Tier 0/1/2 per-anchor checks).
- `findings/` — collapse to stable-id findings, `InterfaceDiffAssembler` (Tier 2 method-set diff),
  baseline TSV + join.
- `worklist/` + `git/` — the semantic worklist (git freshness vs `last_reviewed`).
- `render/` — report / quiet-log / auto-fix / coverage / findings.json / worklist renderers.
- `engine/` + `cli/` — orchestration and the picocli entry point.
- `.claude/skills/kb-freshness/` — the skill that runs the engine and performs the semantic pass.
- `baseline/kb-freshness-baseline.tsv` — the committed, human-owned baseline.

## Design invariants (do not regress)

- **Three-valued outcomes**: `present` / `absent` / `unverifiable`. Only certain-`absent` (and a
  package/path-move `present`) asserts into the report. When in doubt → `unverifiable` (quiet log).
- **Never assert on line numbers.** A moved line for a *named* symbol → an `auto-fix` proposal, never
  an assert. Bare `File.java:NN` links carry no line (the KB uses them for members too).
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
  `methods:` (documented names). Loose interface prose is deliberately left to the semantic pass to
  avoid false positives — do not "improve" it into scraping prose.
- **`components:`/`verification:` paths are platform-sdk-relative** (first segment = module dir); the
  extractor prefixes `platform-sdk/`. Markdown links resolve relative to the doc's directory.
- **The skill is module-local**: it lives in this module's `.claude/skills/` and is discovered only
  when Claude Code starts within `platform-sdk/consensus-kb-freshness/` (or a subdirectory).
