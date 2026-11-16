# PR size

A PR too large to review defeats the purpose of review, even with AI help. AI makes large PRs cheap to produce, so a
large PR is a smell. It isn't always a defect: some large PRs are unavoidable, and some are harder to follow when split,
because a part makes no sense without the next one.

This check runs after the design check and before the lenses. It doesn't stop the review: the PR still gets reviewed as
it is, and the other findings are useful whether it is split or not.

## Measure

Measure each PR on its own. For a stack, the size of the whole stack doesn't matter.

- **Author mode:** `git diff --numstat -M main...HEAD`
- **Reviewer mode:** `gh api repos/{owner}/{repo}/pulls/<n>/files --paginate`. Each file has a `status`, `additions`
  and `deletions`; a `renamed` file has a `previous_filename`.

Count:

| Measure | What counts |
|---|---|
| **Lines** | lines added plus lines deleted, in every file except pure moves |
| **Files** | files changed, except pure moves |
| **Moves** | files moved or renamed. A group of classes that must move together is one move |

A **pure move** is a moved or renamed file with no other change, apart from its package line. The edits other files
need because of a move, such as imports and references, are part of that move.

Count every file in the PR, including files outside the skill's scope: the reviewer has to read them too. Don't count
generated files, or data files that nobody reads line by line, such as recorded events; name them in the size line.

Report lines in test source sets (`src/test`, `src/testOtter`, `src/jmh`, ...) separately from the rest. Tests are
easier to review than the code they test, but they still take time. `src/testFixtures` counts with the rest, because
other modules use it as an API.

## When to look for a split

- more than **500 lines**, or
- more than **15 files**

Crossing a limit isn't a finding. It means the skill looks for a split.

## A sensible split

A split is a list of PRs, in merge order, with the files or changes that go in each. It is sensible only if every part:

- builds and passes its tests on its own
- passes step 2 on its own: the skill can write where that part fits and what goes wrong without it. A part fails this
  if all it can say is that the next part needs it, such as a class whose only purpose is visible in the next PR. A
  part that does something useful of its own for the larger piece of work passes, even if its users come later.
- can be reviewed without reading the other parts

Splits that are often sensible:

- a mechanical move or rename first, then the change
- a refactor with no behaviour change first, then the behaviour change
- independent features or fixes that were bundled together
- unrelated cleanup

## Findings

**Finding when:**

- the PR is over a limit and the skill can name a sensible split. The finding gives the parts, their order, and the
  purpose of each in one line.
- the PR makes more than one independent move, at any size. Suggest one move per PR.
- the PR moves a file and also changes it beyond the move, at any size. Git shows such a file as one large delete and
  one large add, so the reviewer can't see what changed. Suggest the move in its own PR first.

**Not a finding:** over a limit with no sensible split. Say so in one line, with the numbers, so the reviewer knows to
set aside time:

```
Large: 1,155 lines (480 in tests) in 9 files. No split keeps each part meaningful: the writer is tested
by parsing what it writes.
```

In reviewer mode the line goes in the orientation; in author mode, under the summary line.

## Example

The Flicker graph parser and writer, and the change to identify Flicker nodes by letter, were first one PR: about 1,450
lines added in about 19 files. It was split in two:

- **#28131**, merged first: Flicker code identifies nodes by letter instead of by index in the roster. 13 files, +297
  −136. It stands on its own: the code is simpler, and a graph no longer needs a declared roster.
- **#28124**: adds the parser and writer for `.graph` files. 9 files, +1,155 −10.

Each part passes step 2 on its own, and #28124 reads more easily without the renaming spread through it.

#28124 is still over the line limit, but has no sensible further split. Splitting the parser from the writer looks
possible, but the two define one file format, and `GraphWriterTest` checks the writer by parsing what it writes. A
writer PR without the parser would have no way to test it, and a reviewer would have to read both to judge the format.
