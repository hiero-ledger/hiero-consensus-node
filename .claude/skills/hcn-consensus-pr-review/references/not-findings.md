# Not findings

What the skill doesn't report. A false finding costs the author time and the skill trust, so when in doubt, leave it
out or ask a question.

## What the build already catches

`./gradlew qualityGate` and the build run these checks. Don't report anything they catch.

| Check | Catches |
|---|---|
| Spotless | formatting (Palantir Java format), license headers, `requires` order in `module-info.java` |
| `javac -Xlint:all -Werror` | compiler warnings, except the categories the build turns off |
| Javadoc lint (`-Xdoclint:all,-missing -Xwerror`) | malformed javadoc: broken `@param`, links, HTML. Not missing javadoc |
| JPMS | use of a package another module doesn't export |
| Module directives scope check | `requires` directives that are unused or have the wrong scope |

There is no PMD, Checkstyle, Error Prone or architecture test. Visibility, naming, magic numbers and the module
category rules are not checked by the build. The other reference files cover them.

## Out of scope

- Files outside `platform-sdk/`.
- `platform-sdk/swirlds-merkledb`, `platform-sdk/swirlds-state-*` and `platform-sdk/swirlds-virtualmap`.
- Clarity rules for Markdown written for AI: `.claude/**`, every `CLAUDE.md`, `.github/ai/**`,
  `.github/copilot-instructions.md`.

List the out-of-scope files the PR changes in one line, so the reader knows they weren't reviewed.

## Unchanged code

Problems in code the PR doesn't change are not findings. Exceptions, where the skill searches the whole codebase:

- leftovers from what the PR replaced ([structure.md](structure.md), topic 8)
- existing code the new code duplicates ([structure.md](structure.md), topic 3)
- existing javadoc on code whose body the PR changed ([clarity-and-naming.md](clarity-and-naming.md), topic 4)
- KB statements and references the PR makes false ([consensus.md](consensus.md))

## Unverified claims

Drop a finding when:

- it has no `file:line`
- it has no concrete consequence or failure scenario
- the skill hasn't read the code it describes
- it depends on how the code is called, and the skill hasn't checked the callers
- it depends on a guess about why the change was made ([SKILL.md](../SKILL.md), step 2)

If the concern is real but can't be verified, raise it as a question that says what would need to be true.

## Preference

"I would have done it differently" isn't a finding unless it names a concrete cost. See [structure.md](structure.md),
topic 11. Author mode shows these as optional suggestions; reviewer mode drops them.
