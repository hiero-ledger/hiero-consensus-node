---
name: hcn-consensus-pr-review
description: Reviews a consensus-layer pull request (platform-sdk, except swirlds-merkledb, swirlds-state-* and swirlds-virtualmap) for correctness, consensus invariants and rules, structure, and clarity of javadoc, names and the PR description. Two modes. Author mode checks the author's own branch before review is requested and returns a fix list. Reviewer mode takes a PR number and returns an orientation plus findings phrased as questions, for the reviewer to raise in their own words. Reports only; never posts to GitHub. Load this when asked to review, self-review or pre-review a platform-sdk change or PR, or to check whether a branch is ready for review.
---

# Consensus PR review

Helps a human review a consensus-layer PR. Reviews are how engineers learn the code, so clarity matters as much as
correctness: code a human can't understand without the author's session isn't ready.

The skill reports. It never posts comments or reviews to GitHub, never commits, and changes files only when the author
asks in author mode. Nothing it drafts carries AI attribution.

## Mode

| Mode | Input | When |
|---|---|---|
| **Author** | the current branch vs `main` | no PR yet, or the PR's author is the `gh` user |
| **Reviewer** | a PR number or link | someone else's PR |

The user can name the mode to override this.

## References

Read these before reviewing. Each decides what is a finding, a question, or neither, in its area.

| File | Covers |
|---|---|
| [clarity-and-naming.md](references/clarity-and-naming.md) | the two readers, javadoc, comments, names, terminology, PR description, Markdown docs |
| [structure.md](references/structure.md) | ownership, config, reuse, API surface, generality, layering, leftovers, records |
| [correctness.md](references/correctness.md) | error paths, resources, concurrency, behaviour changes, tests, release risk |
| [consensus.md](references/consensus.md) | KB invariants, rules, ADRs, scenarios, determinism, KB drift |
| [not-findings.md](references/not-findings.md) | what the build catches, scope, unverified claims, preference |
| [output.md](references/output.md) | severity, questions, report layout, follow-up block |

## Steps

### 1. Collect

- **Author mode:** `git diff main...HEAD` and the commit messages. Ask for a draft PR description if there is none.
- **Reviewer mode:** `gh pr view <n>` (description, linked issues) and `gh pr diff <n>`.
- List the changed files. Set aside out-of-scope files ([not-findings.md](references/not-findings.md)).

### 2. Run the lenses

Each lens reads its reference file and returns candidate findings and questions, with `file:line`.

| Lens | Reference | Sees |
|---|---|---|
| API cold reader | clarity-and-naming.md | names, signatures and javadoc only |
| Change reader | clarity-and-naming.md | current changed files, no PR description |
| Structure | structure.md | diff and codebase |
| Correctness | correctness.md | diff and codebase |
| Consensus | consensus.md | diff, codebase and KB |
| PR description | clarity-and-naming.md, topic 10 | description and diff |

The two readers must not see the PR description, the linked issue or this conversation. Their value comes from not
knowing the context.

**Cost:** for a large diff, run the lenses as parallel subagents. For a small one, a few files and no new API, run them
inline. The two readers always run as subagents, so they stay without context.

### 3. Verify

Check every candidate against the code before keeping it. Drop it if it fails any test in
[not-findings.md](references/not-findings.md#unverified-claims). Merge duplicates that two lenses found.

### 4. Report

Classify and lay out the report as in [output.md](references/output.md). When the user asks to expand a finding, give
its evidence, reasoning and a suggested fix.

## Improving the skill

When a finding turns out wrong, or a reviewer catches something the skill missed, change the reference file that should
have decided it. Keep rules short and tie each one to a real example.
