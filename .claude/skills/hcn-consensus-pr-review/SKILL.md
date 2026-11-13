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
| [design-first.md](references/design-first.md) | changes the team should agree on before code is written |
| [pr-size.md](references/pr-size.md) | how large a PR is, and whether it should be split |
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

Before any lens runs, write three plain sentences:

1. **Where it fits:** the larger piece of work the change is part of, or an example of what will use it.
2. **What goes wrong without it.**
3. **How it works,** with its concrete limits or numbers, and why it is done this way if the approach isn't the
   obvious one.

Use the PR description, the linked issue and the commit messages. Check whether the PR is part of a stack: a base
branch other than `main`, or a description that mentions earlier or later PRs. The context may be in one of those.
Read the class javadoc of the main classes the PR adds or changes; the reasoning is sometimes there rather than in the
description.

If you can't write the first two sentences from what is there, stop. Don't run the lenses and don't guess. A human
reviewer would stop here and ask the author, and so does the skill. Report only what is missing, as in
[output.md](references/output.md#purpose-unclear). Continue when the user gives the missing context. In author mode,
ask the author directly.

If only the third sentence is incomplete, continue, and raise what is missing as a question.

The two readers still don't see the purpose. Their guesses are checked against it in step 6.

**Example:** #28195. The description said what the saturation check does, but not where it fits or what goes wrong
without it. The first review guessed, and the reviewer and the author found its questions confusing. A second attempt
asked about the experiment behind the check and the source of the 1% limit. The author found those interesting, but
they didn't help a reviewer understand the PR: they were about the third sentence. Two questions about the first two
led to this summary, which the reviewer called "vastly better" than the description:

> This is one of several steps to build tooling for benchmarks that don't exist yet. It makes such a benchmark fail
> when the pipeline can't keep up with its inputs. In that case tasks pile up in queues, and the measured latency
> reflects how long the run lasted rather than how fast the pipeline is. The check compares the median latency at the
> start of a run with the median at the end, and fails the run if latency grew by more than 1% of the elapsed time.

### 3. Check for an agreed design

Decide whether the change makes decisions the team should have agreed on before the code was written, and whether
there is evidence they did, as in [design-first.md](references/design-first.md). If it does and there is none, stop.
Report only what is in [output.md](references/output.md#design-not-agreed). Continue when the user says the design was
agreed.

### 4. Check the size

Measure the PR and, if it is over a limit, look for a sensible split, as in [pr-size.md](references/pr-size.md). This
doesn't stop the review.

### 5. Run the lenses

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

### 6. Verify

Check every candidate against the code before keeping it. Drop it if it fails any test in
[not-findings.md](references/not-findings.md#unverified-claims). Merge duplicates that two lenses found.

Compare each reader's guess at the purpose with step 2, as in
[clarity-and-naming.md](references/clarity-and-naming.md#checking-the-readers-guess). Drop any candidate, from any
lens, that depends on a guess about why the change was made. If a candidate can't be judged without knowing why, the
purpose is unclear: go back to step 2.

### 7. Report

Classify and lay out the report as in [output.md](references/output.md). When the user asks to expand a finding, give
its evidence, reasoning and a suggested fix.

## Improving the skill

When a finding turns out wrong, or a reviewer catches something the skill missed, change the reference file that should
have decided it. Keep rules short and tie each one to a real example.
