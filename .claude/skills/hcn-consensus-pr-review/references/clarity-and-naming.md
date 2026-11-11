# Clarity and naming

Reviews are how engineers learn the code. Code that a human can't understand without the author's session isn't ready,
even if it is correct.

Clarity and naming findings are never dropped or downgraded. None of them blocks merge on its own: each one can be fixed
in this PR, or in a follow-up PR or ticket if another push isn't worth a CI cycle.

## The two readers

Two subagents read the PR without its context. Each has a different blind spot, so the skill uses both.

### API cold reader

**Reads:** only the names, signatures and javadoc of non-private types, methods and fields that are either:

- new in the PR, or
- existing, with a body the PR changed, even if the signature didn't change.

**Doesn't see:** the implementation, the PR description, the linked issue or the session.

**Writes:** for each element, what it thinks the element does and how a caller would use it.

**The skill then compares** that with the implementation:

- The reader can't say what an element does → unclear javadoc or name (topics 3, 9).
- The reader's description is wrong → misleading name or javadoc (topics 4, 9).
- The reader misses a responsibility, or describes a use the code doesn't support → an API that doesn't convey what it
  does ([structure.md](structure.md), topic 4).
- An existing javadoc no longer matches a changed body → inaccurate javadoc (topic 4). A behaviour change that the
  javadoc didn't follow is a common miss in AI-written code.

### Change reader

**Reads:** the current version of each changed file, with the changed regions marked.

**Doesn't see:** the PR description, the linked issue, the session, or the earlier version of the code.

**Answers** for each changed region: can someone maintaining this file tell what the code does and why?

This reader covers PRs that only modify existing code. It catches what the API cold reader can't see:

- inline comments that are unclear, narrate the change, or refer to things outside the repo (topics 1, 3, 7)
- private names and local variables (topic 9)

## Topics

### 1. Context from the dev session

**Rule:** javadoc and comments make sense to someone who has only the repository.

**Finding when** text refers to something the reader can't find in the repo:

- a design doc or a section of one ("D1")
- a ticket, a conversation or a decision made elsewhere
- an earlier version of the code
- a word like "it" or "the check" with nothing in the file it can refer to

**Examples:**

- #27951: "What is meant by `D1`?" It was a section of a private design doc the author used with Claude.
- #27951: a comment that "looks like it was taken out of context".
- #27941: "without it" and "the check after it", with no clear referent.
- #27866: the reason the class exists was only in the ticket.

### 2. Length and proportion

**A question, never a finding.** How long a javadoc should be is a judgment call, and authors have good reasons to
disagree.

**Ask when** a javadoc is much longer than what a caller or maintainer needs, for example when it describes how the
author got there, or lists measurements that could live in the PR description or a doc.

**Examples:** #27697 ("too verbose … reads as AI generated"), #27944 ("cut that doc to 2-3 paragraphs max"). In #27974
the author disagreed: the paragraphs explained, with measurements, why the code was written as it was.

### 3. Plain language

**The reader to write for:** an engineer on the consensus team who knows gossip, sync, the hashgraph and consensus,
but not this PR. The two readers know less than that. If a reader stumbles only because it doesn't know the domain, it
isn't a finding.

**Finding when** that engineer would have to reread a sentence, or would misread it.

**The finding:**

- quotes the sentence
- gives a rewrite that is clearly easier to read and makes sense on its own

If you can't write a clearly better rewrite, drop the finding. The finding's own wording must be as plain as it asks the
code to be: don't paraphrase the problem in more abstract words than the original.

**Examples:**

- #27994: "a very complicated way to say something very trivial".
- #27973: "I was following this sentence until the last part! I don't know what it's trying to say."
- #27945: "I don't understand this comment."

**False positive:** #28180: "a sync's decision which events to send" and "within one latency, so … every send list is
empty". Both readers stumbled, but the suggested rewrites were no clearer, and the second was obvious to anyone who knows
sync.

### 4. Accuracy

**Finding when** a javadoc or comment contradicts the code.

**Check:**

- every javadoc and comment the PR adds or changes
- existing javadoc on code whose body the PR changes

**Examples:**

- #27605: a javadoc describing the wrong delivery order.
- #27834: "repeated as longs" when the code copied four bytes every six.
- #28095: a comment about threading that was never true.
- #27571: comments still naming BouncyCastle after the PR removed it.

### 5. Missing javadoc

**Finding when** a new or changed public or protected type or method has no javadoc. The build doesn't check this: the
javadoc lint excludes missing comments.

**Guidance, not checked:** beyond what it does, a javadoc usually needs whichever of these apply:

- thread safety
- ownership or lifetime of what is passed in or returned
- null handling
- the reason for a choice that isn't obvious

**Not a finding:** malformed javadoc (broken `@param`, links or HTML). The build already fails on it.

### 6. Javadoc style

**Finding when** one class mixes Markdown javadoc (`///`) and classic javadoc (`/** */`).

**Not a finding:** using either style. Neither is required.

**Example:** #27536.

### 7. Inline comments

**Rule:** an inline comment explains why, or summarizes what complicated logic does. It doesn't repeat simple code.

**Finding when** a comment:

- narrates the change ("now uses X instead of Y", "changed to fix the race")
- refers to an earlier version of the code
- restates code that is already easy to read, such as `// increment the counter` above `counter++`

**Not a finding:** a comment that says what a complicated block does, so the reader doesn't have to work it out line by
line.

### 8. Terminology

The canonical terms are in the [glossary](/platform-sdk/docs/consensus-layer/glossary.md).

**Finding when:**

- a glossary term is used with a different meaning. Example: #27945 used "rounds" for something other than consensus
  rounds.
- an abbreviation means something else to most readers of this codebase. Example: #27536 used "TLS" for thread-local
  storage.

**Question when** a glossary concept appears under another name, such as "hashgraph window" for "non-ancient window".

### 9. Naming

**Finding when a name is:**

- **Generic or misleading:** the API cold reader guesses wrong from the name alone.
  - `Step` (#27945): too generic to say what it records.
  - `FalconRecorder` (#27945): reads as something passed to a test, but it runs a Falcon network.
  - `holds()` (#27973): reads as a containment check.
  - `idle` (#28081): describes the situation, not the value, which is a position in the backoff schedule.
- **Stale after a refactor:** it describes the old design. Example: `latestGenerations` after the move to sequence
  numbers (#27959).
- **Inconsistent:**
  - a parameter doesn't match the field it sets (#27744)
  - the same concept has a different name in a sibling class (#27973)

**Question when:**

- a literal with a specific meaning has no named constant or explanation (#27834, #27959)
- a single-letter name is used outside a short loop (#27789)

### 10. PR description

**Author mode:** all checks below are findings.

**Reviewer mode:** only the length check runs, as a question. If the description is too long or detailed, suggest
asking the author whether a shorter summary would help. The author decides. The other checks are the reviewer's call.

**Finding when:**

- the description doesn't say what changed, or doesn't say why
- it states a requirement or constraint the author hasn't confirmed
- it is too long or detailed for a reviewer to read. Reviewers skip long descriptions, so what matters gets missed.
  Signs:
  - the what and why aren't clear from the first few sentences
  - it lists changes file by file, which the diff already shows
  - it includes raw tool or test output, or reads like unedited AI output

For a description that's too long, suggest a short version: what changed, why, and anything the reviewer should look at
closely.

If there is no description yet, ask the author for a draft and check that.

**Examples:**

- #27605: Claude added a requirement to the description. The reviewer missed it and didn't agree with it.

### 11. Markdown files

**Human docs** get topics 1–4 and 8. This includes the consensus-layer KB (`platform-sdk/docs/consensus-layer/`).
Exception: the KB's `CLAUDE.md` and `FORMAT.md` files are agent instructions.

**AI docs** are exempt from this file. They are:

- `.claude/**`
- every `CLAUDE.md`
- `.github/ai/**`
- `.github/copilot-instructions.md`
