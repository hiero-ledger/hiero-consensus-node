# Output

How the skill reports. The other reference files decide what is a finding or a question; this file decides how it is
shown.

The skill only reports. It never posts to GitHub. In author mode it changes files only when the author asks. Nothing
it drafts carries AI attribution.

## Every finding

- Numbered, so the user can ask for more detail on any one ("expand 3").
- Starts with `file:line`.
- At most two lines: the problem and its consequence.
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
| **Blocks merge** | Correctness, module dependency rules, and consensus findings except the KB group | Yes |
| **Clarity and naming** | Findings from [clarity-and-naming.md](clarity-and-naming.md) | No |
| **Structure** | Findings from [structure.md](structure.md) | No |
| **KB** | KB statements the PR makes false, and KB edits that break the KB's authoring rules ([consensus.md](consensus.md)) | No |
| **PR description** | Author mode only; see [clarity-and-naming.md](clarity-and-naming.md), topic 10 | No |

Findings that don't block merge can be fixed in this PR, or in a follow-up PR or ticket if another push isn't worth a
CI cycle. They are never dropped.

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

## Author mode

In this order:

1. **One summary line:** the count in each group.
2. **Blocks merge**
3. **Clarity and naming**
4. **Structure**
5. **KB**
6. **PR description**
7. **Questions**
8. **Optional suggestions:** structure suggestions with no named cost
   ([structure.md](structure.md), topic 11), and data classes that could be records (topic 9).
9. **Follow-up block**

Leave out empty groups.

## Reviewer mode

In this order:

1. **Orientation:**
   - what the PR is for: the three purpose sentences from step 2 of the skill (where it fits, what goes wrong
     without it, how it works)
   - the order in which to read the files, most important first
2. **Blocks merge**
3. **Clarity and naming**
4. **Structure**
5. **KB**
6. **Questions**
7. **Follow-up block**

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
belong in the review, not here. No findings, no orientation and no follow-up block.

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

Each decision names its files, its effect, and the choice the team should make. No findings, no orientation and no
follow-up block. If the author says the design was discussed, the review continues.

## Follow-up block

Every finding that doesn't block merge, as a Markdown task list the user can paste into a follow-up PR or a ticket.
PR description findings are left out: editing the description doesn't trigger CI, so they are fixed right away.

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

Follow-up
- [ ] `GraphDefaults.java`: replace the "D1" reference with what the default is for.
- [ ] `Step.java`: rename to say what it records, e.g. `ReleasedFromIntake`.
- [ ] `FalconSharedTipsetTracker`: construct it with `FalconSharedTipsets` passed in.
```
