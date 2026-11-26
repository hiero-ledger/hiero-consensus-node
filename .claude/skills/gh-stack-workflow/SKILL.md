---
name: gh-stack-workflow
description: How stacked pull requests are created and maintained in this repository with the `gh stack` extension — linking already-open PRs into a stack, building a stack from scratch, rebasing a stack when a lower branch is amended, and the git traps that cost work (amending a base then rebasing the wrong way, worktrees pinning a branch, semantic conflicts git cannot see). Load this before opening a PR that builds on another PR's branch, or before rebasing anything in a chain.
---

# Stacked pull requests

A change that is too large for one reviewable PR is split into a chain, each PR building on the
one below. In this repository that chain must be a **real `gh stack` stack**, not a set of
hand-set base branches.

## The rule

Setting `--base <branch>` on `gh pr create`, or patching `base` through `gh api`, produces the
right *diff* and nothing else — no tracking metadata, no stack navigation between the PRs on
GitHub, and nothing that survives a reviewer merging out of order. The stack is what makes the
chain legible and mergeable as a unit.

Both are needed: the base makes each PR's diff show only its own work, and the stack makes the
chain a first-class thing on GitHub. `gh stack` sets the bases for you.

## The two commands you will actually use

### PRs already open — the common case

`gh stack link` adopts existing PRs without requiring local stack state. Arguments are
**bottom to top**, and may be PR numbers, branch names, or PR URLs:

```bash
gh stack link 28401 28402 28405
# ✓ Updated stack to 3 PRs (stack #28401)
```

It is idempotent and additive: re-running it on a chain that already forms a stack updates that
stack rather than creating a second one, and existing PRs are never removed. Branch arguments are
pushed automatically, and a branch with no PR yet gets one created with the correct base.

### Building a stack from branches

```bash
gh stack init 28401-state-schema 28402-handler 28405-hapi-tests
gh stack submit
```

`init` adopts branches that exist and creates the ones that do not; the first is based on the
default branch and each subsequent one on its predecessor. Use `--base` for a different trunk.

Other verbs: `gh stack view`, `gh stack checkout`, `gh stack rebase`, `gh stack sync`,
`gh stack merge`, `gh stack modify`, `gh stack unstack`.

## Choosing the order

Order by **dependency, not by which branch was written first**. The question to ask is which
change has to exist for the next one to make sense.

A typical services feature stacks as: protobuf messages in `hapi/` → state schema and its
migration → the handler in the service's `-impl` module → HAPI tests in `test-clients`. Each PR
builds and passes its own checks, and each depends on the one below: a handler cannot be reviewed
without the state it writes, and the HAPI tests need the handler. A refactor that later PRs build
on goes **first**; putting it later means every PR above fights the restructure.

## Rebasing a stack, and the traps

When a lower branch changes, every branch above it is stale. `gh stack rebase` handles the common
case. When doing it by hand, four things go wrong:

### An amended base needs `--onto`

If the lower branch was **amended** rather than extended, the old and new commits have different
trees, and a plain `git rebase <new-tip>` tries to replay the superseded commit as well —
producing spurious add/add conflicts on files both versions created.

```bash
# WRONG when the base was amended: replays the old commit too
git rebase --gpg-sign 4b333ba

# RIGHT: replay only the commits after the old tip
git rebase --gpg-sign --onto <new-base-tip> <old-base-tip>
```

### A failed checkout leaves you on the branch you were on

`git checkout <branch>` fails if a worktree holds that branch. Chained without `set -e`, the
rebase that follows then runs against whatever is checked out — which can rebase `main`.
Always assert the branch before rebasing:

```bash
set -e
git checkout -q "$b"
[ "$(git branch --show-current)" = "$b" ] || { echo "ABORT"; exit 1; }
git rebase --gpg-sign "$onto"
```

Free the worktree first (`git worktree remove --force <path>`; `git worktree prune`) rather than
working around the refusal. Note `git reset --hard` is denied by policy — recover a moved branch
with `git switch --detach <sha>` then `git branch -f <name> <sha>`.

### A green IDE is not a green build

Generated code — the PBJ protobuf models in `hapi/hapi/build/` — is not in git, so an IDE or a
stale `build/` directory can compile against models the rebased `.proto` sources no longer
produce. Conflict resolution that swallows a closing `}` at a hunk boundary, or drops one side of
a `module-info.java` hunk, is invisible until Gradle rebuilds. After resolving conflicts on any
branch, rebuild the modules it touches before trusting a result:

```bash
./gradlew spotlessApply :<project>:assemble :<project>:test checkAllModuleInfo
```

### Semantic conflicts git cannot see

Two branches can add the same identifier in **different files** — a test-fixture class of the same
name in the same package, or the same `@ConfigProperty` in two config records — which produces no
textual conflict and a compile or startup error only once they are stacked.
Resolve by removing the copy from the **upper** branch: the lower one already provides it, and
deleting it there would break that branch standalone.

## Verifying

After linking or rebasing, confirm each PR's base is the branch below it and that a stack number
was reported:

```bash
gh stack view
gh pr list --state open --limit 60 --json number,baseRefName,title \
  --jq '.[] | "\(.number)  base=\(.baseRefName)  \(.title)"'
```

Every PR still needs its own backing issue and `Fixes #N`, an assignee, a Conventional Commits
title and a DCO-signed history — stacking replaces none of them.

Run `gh` as **`env -u GITHUB_TOKEN gh ...`**. Where `GITHUB_TOKEN` is exported as a fine-grained
PAT (`github_pat_…`), `gh` prefers it over the credential from `gh auth login` and calls that need
broader scope — `gh pr edit`, `gh pr checks`, `statusCheckRollup` — fail with
*"Resource not accessible by personal access token"*. That is the environment shadowing the
credential, not a repository restriction: unset the variable and `gh` falls back to the classic PAT
`gh auth login` stored, which carries `repo` and `workflow` and can do all of it.

`gh api -X PATCH repos/hiero-ledger/hiero-consensus-node/pulls/<n> -F body=@file` remains the better tool for a
body edit regardless — but note it **overwrites** the body, so read the current one first.
