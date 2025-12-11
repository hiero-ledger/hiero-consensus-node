---
title: Gradle Conventions Lab
status: draft
diataxis: tutorial
last-validated: 2025-12-11
complexity: medium
duration: 30m
---

## Purpose

Deepen familiarity with `docs/ai/diataxis/reference/gradle-conventions.md` and capture the common build/test commands your AI workflows should use.

## Prerequisites

- [ ] Java 21 installed (per `CODEX.md`).
- [ ] Gradle wrapper executable (`./gradlew`).
- [ ] Source tree accessible.

## Steps

1. **Review Reference Doc**
   - Read `docs/ai/diataxis/reference/gradle-conventions.md`.
   - Highlight key sections (assemble vs. build, single-test invocation, formatting commands).
2. **List Core Commands**
   - Create a scratch list of commands you run most often (e.g., `./gradlew assemble`, `./gradlew :app:test`).
   - For each, note when it should be used (fast compile, targeted module tests, formatting, etc.).
3. **Validate Module Paths**
   - Run `./gradlew projects` or inspect `settings.gradle.kts` to confirm module names you care about.
   - Update your command list with the exact module paths.
4. **Capture Build Preferences**
   - Decide on flags or environment variables (e.g., `--parallel`, `-x test`) you want the AI to avoid or prefer.
   - Record any prerequisites (e.g., "never run full `build` unless I ask").
5. **Document Outcomes**
   - Append your curated command list to `docs/ai/diataxis/reference/gradle-conventions.md` under a "Common Commands" subsection, or create a how-to describing your workflow.
   - Alternatively, store personal notes in `docs/dev/<project>/build-notes.md` if they are project-specific.
6. **Feed Tutorials/Workflows**
   - If you want future workflows to reference these commands automatically, start a task sequence to update relevant workflow specs.

## Reflection

- Which commands should the AI default to when verifying changes?
- Are there slow commands you want the AI to avoid unless necessary?
- Did you identify new documentation or workflow updates to track via a task sequence?
