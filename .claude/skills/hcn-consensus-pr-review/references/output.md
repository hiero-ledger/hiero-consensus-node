# Output

How the skill reports. The other reference files decide what is a finding or a question; this file decides how it is
shown.

The skill only reports. It never posts to GitHub. In author mode it changes files only when the author asks. Nothing
it drafts carries AI attribution.

## Every finding

- Numbered, so the user can ask for more detail on any one ("expand 3").
- Starts with `file:line`.
- At most two lines: the problem and its consequence. A correctness finding states the consequence with its bound,
  from [correctness.md](correctness.md#impact): "held copies arrive up to one reconnect delay late, once per heal",
  not "recorded arrival 400 ms too early".
- No fix unless the user asks to expand it. Exception: a plain-language finding quotes the sentence and gives its
  rewrite ([clarity-and-naming.md](clarity-and-naming.md), topic 3), because the rewrite shows the problem better than a
  description of it.

**Expanding a finding** gives the evidence (quoted code or javadoc), the reasoning, and a suggested fix. Start with the
plain explanation; the evidence comes after it.

**Plain enough to explain:** every finding, question and expansion must be explainable in two or three plain sentences
to someone who doesn't know the code. If it can't be, drop it. Example: #28195, question 5 claimed the saturation
check's limit depends on pass length. The expansion, with its table of overloads and its fix options, confused both the
reviewer and the PR author, and in plain words the question had no concrete cost.

There is no cap on findings in general; keep each one short. Clarity and naming has a cap of five, the most misleading
first. If more meet the bar ([clarity-and-naming.md](clarity-and-naming.md#the-bar)), one line says how many more there
are, and the user can ask for them.

## Severity

| Group | Contains | Blocks merge |
|---|---|---|
| **PR size** | Splits and moves from [pr-size.md](pr-size.md) | No |
| **Blocks merge** | Correctness findings that meet the blocking bar in [correctness.md](correctness.md#impact), module dependency rules, and consensus findings except the KB group | Yes |
| **Correctness** | Correctness findings below that bar | No |
| **Clarity and naming** | Findings from [clarity-and-naming.md](clarity-and-naming.md) | No |
| **Structure** | Findings from [structure.md](structure.md) | No |
| **KB** | KB statements the PR makes false, and KB edits that break the KB's authoring rules ([consensus.md](consensus.md)) | No |
| **PR description** | Author mode only; see [clarity-and-naming.md](clarity-and-naming.md), topic 10 | No |

Findings that don't block merge can be fixed in this PR, or in a follow-up PR or ticket if another push isn't worth a
CI cycle. They are never dropped. PR size findings are the exception: a split can't be done in a follow-up, so they are
reported first, and the author decides before review starts.

Every finding lands in exactly one of the two closing lists: the [approval block](#approval-block) or the
[follow-up block](#follow-up-block). The reader never has to sort the findings themselves.

## Questions

A question is a judgment call for the author or reviewer to settle. It isn't a defect. Each one names its location and
says what to decide.

| Question | Author mode | Reviewer mode |
|---|---|---|
| Should similar existing code be made more generic and reused? ([structure.md](structure.md), topic 3) | Yes | Yes |
| Could the API be simpler to use? ([structure.md](structure.md), topic 4) | Yes | Yes |
| Could a test build its own dependencies instead of using a hook? ([structure.md](structure.md), topic 7) | No | Yes |
| Is the javadoc longer than needed? ([clarity-and-naming.md](clarity-and-naming.md), topic 2) | Yes | Yes |
| Should a glossary concept use its glossary name? (topic 8) | Yes | Yes |
| Should a literal be named, or a single-letter name expanded? (topic 9) | Yes | Yes |
| Would a shorter PR description help? (topic 10) | No: a finding | Yes |
| Should a short name the team already uses be expanded? (topic 9) | Yes | Yes |
| Should a risky change go behind a flag? ([correctness.md](correctness.md), topic 9) | Yes | Yes |
| How was a runtime change verified beyond unit tests? (topic 9) | Yes | Yes |
| Who outside the code depends on a renamed or removed marker, metric, config key or file? (topic 9) | Yes | Yes |
| Does the fix treat the symptom rather than the cause? (topic 9) | Yes | Yes |
| Does a bug fix or new branch need a test? (topic 8) | Yes | Yes |
| Should the wiring diagram before and after be shown? (topic 6) | Yes | Yes |
| Is the cost of per-event or per-round code worth reducing? (topic 10) | Yes | Yes |

## Author mode

In this order:

1. **One summary line:** the count in each group. If the PR is large with no sensible split, the size line from
   [pr-size.md](pr-size.md) goes under it.
2. **PR size**
3. **Blocks merge**
4. **Correctness** (non-blocking)
5. **Clarity and naming**
6. **Structure**
7. **KB**
8. **PR description**
9. **Questions**
10. **Optional suggestions:** structure suggestions with no named cost
    ([structure.md](structure.md), topic 13), and data classes that could be records (topic 9).
11. **Approval block**
12. **Follow-up block**

Leave out empty groups.

## Reviewer mode

In this order:

1. **Orientation:**
   - what the PR is for: the three purpose sentences from step 2 of the skill (where it fits, what goes wrong
     without it, how it works)
   - the order in which to read the files, most important first
   - if the PR is large with no sensible split, the size line from [pr-size.md](pr-size.md)
2. **PR size**
3. **Blocks merge**
4. **Correctness** (non-blocking)
5. **Clarity and naming**
6. **Structure**
7. **KB**
8. **Questions**
9. **Approval block**
10. **Follow-up block**

Phrase each finding as something the reviewer could ask the author. The reviewer decides what to raise and writes
their own comments. No optional suggestions in reviewer mode.

## Purpose unclear

When the skill stops at step 2, the report is only this:

```
Review stopped: the description, the linked issue and the commits don't say where this change fits or
what goes wrong without it.

Questions for the author:
1. What is an example of a benchmark that will use this?
2. Has a benchmark produced a misleading result without this check, or is it a precaution?
```

These are the questions #28195 needed ([SKILL.md](../SKILL.md), step 2). Ask for an example of a hard use case, not
for the first or the full list of users.

One to three questions, each about where the change fits or what goes wrong without it. Questions about how it works
belong in the review, not here. No findings, no orientation, no approval block and no follow-up block.

## Design not agreed

When the skill stops at step 3, the report is only this:

```
Review stopped: this PR makes decisions the team would normally agree on before code is written, and
nothing shows that it did.

Decisions in this PR:
1. ConsensusLatencyRecorder.java, ConsensusLatencyResult.java: a new recorder of latencies into a
   LatencyHistogram. LatencyRecorder in consensus-benchmark-tools already does that for benchmarks.
   Write a new one, or extend LatencyRecorder?
2. FalconNetwork.java, FalconNode.java, FalconMiniNode.java: every Falcon node now feeds a latency
   recorder, although most Falcon tests don't use it. Should it be opt-in?

Question for the author: where were these discussed? The linked issue #28190 only asks to move
NetworkSimulationTest to Falcon.
```

Each decision names its files, its effect, and the choice the team should make. No findings, no orientation, no
approval block and no follow-up block. If the author says the design was discussed, the review continues.

## Approval block

What must be done before the PR can be approved, as a Markdown task list. It always appears, so the reader never has
to work out what is critical from the findings. It contains, in this order:

1. **PR size:** each split or move, as a decision for the author.
2. **Blocks merge:** every finding in that group.
3. **PR description:** author mode only. They are cheap to fix and don't trigger CI.

Each item says the file and what must change, and ends with its finding number, so the user can ask to expand it.
Nothing from the non-blocking groups goes here.

Questions are in neither list, because they are judgment calls. If any are open, the last line of the block names
them: settling one may add an item to either list.

If nothing blocks, the block is one line: `Nothing blocks approval.`

```
Before approval
- [ ] `HashLeafFlusher.cancel()`: must not wait for a running flush to finish. (1)
- [ ] `ReconnectStateLearner`: restore the socket timeout if `createLearnerAsync()` throws. (2)
- [ ] PR description: say why the event order in .graph files is reversed. (6)
Settle question 7 before approving.
```

## Follow-up block

Every finding that doesn't block merge, as a Markdown task list the user can paste into a follow-up PR or a ticket.
PR description findings are left out: editing the description doesn't trigger CI, so they are fixed right away. PR size
findings are left out too: a split has to happen before the PR merges.

Each item stands on its own: no finding numbers, no references to the review. It says the file and what to change.

```
- [ ] `OrderInvarianceTest`: make the class package-private; nothing outside the package uses it.
- [ ] `SimulatedGossip.java`: rename the constructor parameter to match the field it sets.
```

## Example: author mode

```
2 blocking · 2 clarity and naming · 1 structure · 1 PR description · 1 question

Blocks merge
1. HashLeafFlusher.java:258: cancel() is synchronized, so it waits for a running flush
   to finish. Aborting during a stall hangs until the flush ends.
2. ReconnectStateLearner.java:192: createLearnerAsync() runs outside the try, so if it
   throws, the socket timeout isn't restored.

Clarity and naming
3. GraphDefaults.java:9: javadoc cites "D1", which isn't in the repo.
4. Step.java:50: the name doesn't say what is recorded. The cold reader guessed a test
   step; it marks events released from intake.

Structure
5. FalconSharedTipsetTracker.java:94: the tipsets create the tracker and each holds the
   other, with no javadoc saying why.

PR description
6. Doesn't say why the event order in .graph files is reversed.

Questions
7. InFlightConnections.java:91: callers must pass node IDs sorted, which the signature
   doesn't show. Could the method sort them itself?

Before approval
- [ ] `HashLeafFlusher.cancel()`: must not wait for a running flush to finish. (1)
- [ ] `ReconnectStateLearner`: restore the socket timeout if `createLearnerAsync()` throws. (2)
- [ ] PR description: say why the event order in .graph files is reversed. (6)
Settle question 7 before approving.

Follow-up
- [ ] `GraphDefaults.java`: replace the "D1" reference with what the default is for.
- [ ] `Step.java`: rename to say what it records, e.g. `ReleasedFromIntake`.
- [ ] `FalconSharedTipsetTracker`: construct it with `FalconSharedTipsets` passed in.
```
