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

### 2. Establish the purpose

Before any lens runs, write one or two plain sentences that say:

- what the change is for: the problem it solves or what it enables
- why it is done this way, if the approach isn't the obvious one

Use the PR description, the linked issue and the commit messages. Check whether the PR is part of a stack: a base
branch other than `main`, or a description that mentions earlier or later PRs. The context may be in one of those.

If you can't write those sentences from what is there, stop. Don't run the lenses and don't guess. A human reviewer
would stop here and ask the author, and so does the skill. Report only that the purpose isn't clear and what is
missing, as in [output.md](references/output.md#purpose-unclear). Continue when the user gives the missing context. In
author mode, ask the author directly.

The two readers still don't see the purpose. Their guesses are checked against it in step 4.

**Example:** #28195, first review. The description says the PR adds a saturation check to `PipelineTimer`, but not
why the limit is a rate per second of the pass. The skill guessed what the check should measure and raised questions on
that guess, such as question 5. The reviewer passed them to the author. Both found them confusing, and the skill then
dropped them. One question to the author about how the limit was chosen would have been enough.

### 3. Run the lenses

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

### 4. Verify

Check every candidate against the code before keeping it. Drop it if it fails any test in
[not-findings.md](references/not-findings.md#unverified-claims). Merge duplicates that two lenses found.

Compare each reader's guess at the purpose with step 2, as in
[clarity-and-naming.md](references/clarity-and-naming.md#checking-the-readers-guess). Drop any candidate, from any
lens, that depends on a guess about why the change was made. If a candidate can't be judged without knowing why, the
purpose is unclear: go back to step 2.

### 5. Report

Classify and lay out the report as in [output.md](references/output.md). When the user asks to expand a finding, give
its evidence, reasoning and a suggested fix.

## Improving the skill

When a finding turns out wrong, or a reviewer catches something the skill missed, change the reference file that should
have decided it. Keep rules short and tie each one to a real example.
