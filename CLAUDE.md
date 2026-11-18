# CLAUDE.md

Guidance for Claude Code working in this repository. This file covers project intent, conventions, and procedural guidance that isn't captured in the agent reference docs:

- [`.claude/instructions.md`](.claude/instructions.md) — tech stack, personality, requirements (also read by GitHub Copilot through `.github/copilot-instructions.md`)
- [`.claude/build-commands.md`](.claude/build-commands.md) — Gradle build, test, format and local-run commands, and the workflow tests
- [`.claude/directory-structure.md`](.claude/directory-structure.md) — directory-by-directory roles, from the top level down to each module group
- [`.claude/conventions.md`](.claude/conventions.md) — repository-wide conventions: headers, commits, branches, PRs, workflows
- [`.claude/git-hooks.md`](.claude/git-hooks.md) — required local git hooks (must be installed per clone)
- [`platform-sdk/CLAUDE.md`](platform-sdk/CLAUDE.md) — the consensus layer's module categories and modularization rules; most `platform-sdk/consensus-*` modules carry their own `CLAUDE.md`
- [`.github/workflows/docs/naming-standards.md`](.github/workflows/docs/naming-standards.md) — GitHub Actions file name and workflow `name:` standard
- [`.github/workflows/docs/contribution_flow.md`](.github/workflows/docs/contribution_flow.md) — how a commit moves from PR to MATS, XTS, build promotion and release

<!-- Auto-load the reference docs above so Claude has them in context from session start. The per-module CLAUDE.md files under platform-sdk/ are loaded by Claude Code when it reads files in that module; read the others only when a change touches them. -->
@.claude/instructions.md
@.claude/build-commands.md
@.claude/directory-structure.md
@.claude/conventions.md
@.claude/git-hooks-->

## What this repository is

**Hiero Consensus Node** is the Java implementation of a Hiero network node: the gRPC services that accept HAPI transactions and queries (`hedera-node/`), the hashgraph consensus platform under them (`platform-sdk/`), and the protobuf API they share (`hapi/`). It is an open-source project of [Hiero](https://hiero.org), a Linux Foundation Decentralized Trust project, licensed **Apache 2.0**, and it is the software the Hedera network runs.

It is a single multi-module **Gradle** build (`settings.gradle.kts`) of Java Platform Module System (JPMS) modules, built on the [Hiero Gradle Conventions](https://github.com/hiero-ledger/hiero-gradle-conventions). Every module has a `module-info.java`, and every external library version is declared once, in `hiero-dependency-versions/`.

The rules that shape every change:

- **Consensus and state are deterministic.** Every node must reach the same state from the same events. Code on the transaction-handling path must not depend on wall-clock time, iteration order of unordered collections, thread scheduling, floating point, locale or anything else that can differ between nodes. A non-deterministic change on this path causes an ISS (inconsistent state signature) on a live network.
- **State and protobuf changes are compatibility changes.** A change to a state schema, a protobuf message in `hapi/` or a stream format must stay readable by, and migrate from, the previous release. Add fields; never renumber or repurpose them. A state change needs a schema migration.
- **The consensus layer is being modularized.** Its module categories and dependency rules are in [`platform-sdk/CLAUDE.md`](platform-sdk/CLAUDE.md). `./gradlew checkAllModuleInfo` and the module-info checks enforce dependency direction.
- **Main is always releasable.** A commit merged to `main` runs MATS, is tagged `xts-candidate`, runs XTS, and the newest passing commit is promoted to a `build-XXXXX` candidate every day (`.github/workflows/docs/contribution_flow.md`).

## Where issues live

**Here, in [`hiero-ledger/hiero-consensus-node`](https://github.com/hiero-ledger/hiero-consensus-node/issues).** Every short-lived branch and every pull request is tied to an issue (`docs/branch-naming-conventions.md`). Security vulnerabilities are never filed as public issues: report them privately, as described in the [Hiero security policy](https://github.com/hiero-ledger/.github/blob/main/SECURITY.md).

## Keeping context small

This repository is large: about 125 JPMS modules, over 170 workflow files, thousands of HAPI test suites, and protobuf models generated during the build. Read only what a change needs.

- **Read the nearest `CLAUDE.md` or `README.md` first.** `platform-sdk/CLAUDE.md` and each `platform-sdk/consensus-*/CLAUDE.md` describe their module; `hedera-node/README.md`, `hedera-node/docs/` and `platform-sdk/docs/` hold the design documents.
- **Search; never read large or generated files whole.** `build/` directories are outputs (including the PBJ-generated protobuf models), and `hedera-node/test-clients` holds thousands of HAPI test suites. `grep -n` or `rg` for what you need, then read those lines.
- **Load the skill for the area you are in.** `.claude/skills/` holds `hcn-citr-context` (the numbered CITR workflows and the Chewie call path), `hcn-chewie-context` (the Chewie allocation API), `hcn-consensus-pr-review` (reviewing a `platform-sdk` change) and `hcn-benchmark-work-package` (JMH benchmark work packages). Load the one that covers a change before touching that area.
- **Build and test narrowly, then let CI run the rest.** A full `./gradlew build` takes a long time; compile and test only the modules you changed. Keep the exit status and the failures, and read the rest of the log only when they don't explain it:

  ```bash
  ./gradlew :<module>:test >"$TMPDIR/test.log" 2>&1; echo "exit $?"; grep -nE 'FAILED|BUILD FAILED|What went wrong' "$TMPDIR/test.log" | head -40
  ./gradlew spotlessApply >"$TMPDIR/spotless.log" 2>&1; echo "exit $?"; tail -20 "$TMPDIR/spotless.log"
  ```

## When making changes

- **Java code**: run `./gradlew spotlessApply` (or `qualityGate`) before committing; CI's `Spotless / Check` fails on any formatting drift. Follow `docs/hedera-java-style-guide.md`.
- **New module**: create it under a directory that `settings.gradle.kts`'s `javaModules { directory(...) }` block already scans, give it a `module-info.java` and a `build.gradle.kts` that applies the matching `org.hiero.gradle.module.*` plugin (copy a sibling module's), declare `module("<dir>") { artifact = "..." }` there only when the folder name differs from the artifact name, and add it to `.github/CODEOWNERS` **and** `.codeowners`. A new `platform-sdk` module also takes a category under `platform-sdk/CLAUDE.md`'s rules.
- **New or changed dependency**: declare its version in `hiero-dependency-versions/build.gradle.kts` and require it in the consuming module's `module-info.java`; never put a version in a module's build file. `./gradlew checkAllModuleInfo checkVersionConsistency` must pass.
- **New configuration property**: add it to the `@ConfigData` record that owns it (services: `hedera-node/hedera-config/src/main/java/com/hedera/node/config/data/`) with a `@ConfigProperty` default; never read a raw property string.
- **Protobuf change**: the `.proto` sources are in `hapi/hedera-protobuf-java-api/src/main/proto/` (`services/`, `block/`, `platform/`, `streams/`, …). Add fields, never renumber or repurpose one, and document every new message and field. `hapi/hapi` generates the Java model with PBJ during the build.
- **New HAPI test**: follow `.github/workflows/docs/adding-hapi-tests.md`; tag it so the CI tag expressions in `hedera-node/test-clients/build.gradle.kts` pick it up.
- **Workflow change**: follow `.github/workflows/docs/naming-standards.md` and the workflow conventions in `.claude/conventions.md`. Load `hcn-citr-context` before touching any numbered CITR workflow or the Chewie call chain, and update `.github/workflows/docs/workflow-manifest.md` when a workflow is added, renamed or removed.
- **Opening a PR that builds on another PR's branch**: create a real stack with `gh stack`; load the `gh-stack-workflow` skill.
- **New secret**: never commit a plaintext value. Workflow secrets are GitHub Actions secrets referenced as `${{ secrets.NAME }}`; never echo one, and never pass one to a workflow that runs on `pull_request_target`.

## CI/CD

Workflows live in `.github/workflows/` and follow `ddd-xxxx-<name>.yaml` (`.github/workflows/docs/naming-standards.md`): `000-user-*` and `100-user-*` are dispatched by hand, `2xx-*` are the CITR workflows, `300-flow-*` run on `main`, `600`/`601` and `7xx` flows on pull requests, `800-call-*` are reusable workflows, and `900-cron-*` are scheduled. The un-numbered `node-*`, `zxc-*`, `zxf-*` and `zxcron-*` files are deprecated stubs kept for the renumbering; don't add to them. Jobs run on `hl-cn-*` self-hosted runners (`hl-cn-default-lin-sm` unless a job needs more); only the determinism checks also use GitHub-hosted runners.

- **PR checks** (`600-flow-pull-request-checks.yaml` → `800-call-mats-tests.yaml`, the MATS suite): compile and Spotless (`802`), module-info and version checks (`809`), unit tests with coverage (`803`), integration tests (`804`), the MATS HAPI test groups (`810`), the fast Otter tests (`811`), the JMH benchmark smoke run (`812`), Gradle and Docker build determinism (`822`, `823`), EVM functional tests (`871`), and the workflow unit tests under `.github/workflows/support/tests/` (`857`). `.github/workflows/docs/required_checks.md` lists the required ones and which of them cannot run on a forked PR.
- **PR formatting** (`601-flow-pull-request-formatting.yaml`, on `pull_request_target`): the Title Check (Conventional Commits) and the Assignee Check. It must never check out or run the pull request's code, and its file path is pinned by a repository Actions policy, so don't rename it.
- **Other required checks**: DCO (a `Signed-off-by:` trailer on every commit), StepSecurity, Milestone Check, and the CODEOWNERS review. Every commit is also GPG-signed (`docs/maintainers-guide.md`).
- **After merge**: `300-flow-build-application.yaml` runs MATS on `main`, builds the commit artifact (`307`) and tags `xts-candidate` (`302`); XTS (`226`), the daily build promotion (`901`) and the CITR performance and longevity controllers (`221`–`227`) follow. Load `hcn-citr-context` for the details.
- **Release**: a `vX.Y.Z*` tag on `main` or `release/X.Y` runs `301-flow-deploy-release-artifact.yaml`.
- **Dependabot** (`.github/dependabot.yml`) keeps action SHAs and dependency versions fresh.

## CODEOWNERS

Ownership is in **two files that must change together**: `.codeowners` (team names as `%team`, used to request reviewers) and `.github/CODEOWNERS` (`@hiero-ledger/team`, used for PR file filtering). The `*` catch-all and `/.github/` are owned by `@hiero-ledger/github-maintainers`; `/.github/workflows/` adds `@hiero-ledger/hiero-consensus-node-ci-codeowners`; `hedera-node/` modules belong to the execution and smart-contract code owners, and `platform-sdk/` modules to the consensus and foundation code owners; every `*.gradle*` file also requires `@hiero-ledger/github-maintainers`. This file, `AGENTS.md` and `.claude/` fall under the catch-all.

## Things to leave alone unless asked

- **The Apache 2.0 license and the `SPDX-License-Identifier: Apache-2.0` headers.** Every source and build file starts with one; Spotless adds it to Java files.
- **The existing skills** under `.claude/skills/` and the per-module `CLAUDE.md` files under `platform-sdk/`: they are owned by the teams that wrote them. Update one only when the change it describes lands.
- **Deprecated workflow stubs** (`node-*`, `zxc-*`, `zxf-*`, `zxcron-*`): they are removed in their own pull requests, not as a side effect.
- **`601-flow-pull-request-formatting.yaml`'s path** and its `pull_request_target` safety rules (see its header).
- **Generated outputs**: `build/` directories and the PBJ-generated protobuf models.
