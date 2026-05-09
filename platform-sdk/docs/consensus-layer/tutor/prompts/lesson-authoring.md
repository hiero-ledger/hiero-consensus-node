# Lesson Authoring — Meta-prompt for Claude Code

This file is a meta-prompt for Claude Code that authors one Consensus Layer Tutor lesson per invocation, with no parameters supplied by the operator. From a clone of the consensus-node repo checked out on an authoring branch, configure Claude Code to use Opus 4.7 at `xhigh` effort and feed it this file as the prompt; the run decides what to do entirely by inspecting repo state. Opus 4.7 at xhigh is the recommended configuration because every run synthesizes the architecture topic file, multiple concept files, the glossary, invariants, the delta entry, and source code in a single session, verifies code anchors against the working tree, and self-directs from `tutor/curriculum.md` — work that benefits from the strongest reasoning model at the highest effort setting. Adaptive thinking on Opus 4.7 is the only mode available, so no separate thinking budget needs to be configured.

<role>
You are Claude Code, authoring lessons for the Consensus Layer Tutor curriculum on behalf of the Hedera consensus team. The repo is `consensus-node`; you are running on an authoring branch with full read/write access to the working tree.
</role>

<context>
The Consensus Layer Tutor is a curriculum for internal Hedera consensus-team engineers — strong distributed-systems backgrounds, high-level Hedera familiarity. The knowledge base lives at `platform-sdk/docs/consensus-layer/` and is structured per the layout document at the repo root or in the project's onboarding materials. Lessons live at `tutor/lessons/`. The curriculum manifest lives at `tutor/curriculum.md` and is the source of truth for what exists, what is drafted, and what comes next. Lessons are read alongside a tutor-Claude in a Claude project, so they leave Socratic openings rather than answering every question inline. Truth basis is current code; where the proposed redesign differs, that lives in delta callouts referencing `delta-map/<topic>.md`. The KB itself is still under review and the tutor system prompt will be tuned later, but lessons themselves are drafted at production quality — review will catch factual errors, code-anchor drift, and pedagogical issues, not structural placeholders.
</context>

<default_to_action>
Produce files, do not propose them. If state is ambiguous, infer the most useful next action from repo state and proceed; resolve missing details with tool calls rather than asking. The one place to surface uncertainty is inside the lesson body, as `[TBD]` markers gathered in an Open questions callout — see phase two.
</default_to_action>

<run_structure>
Every invocation executes two phases in order: bootstrap, then author one lesson. Both phases run on every invocation; the bootstrap phase is a no-op when `tutor/curriculum.md` already exists. After authoring exactly one lesson, stop. Multiple invocations across multiple sessions incrementally produce the entire curriculum.
</run_structure>

<phase_one_bootstrap>

Check whether `tutor/curriculum.md` exists.

If it exists, skip the rest of this phase.

If it does not exist, generate it. Read these inputs in order, accumulating context for the curriculum decision:

- `platform-sdk/docs/consensus-layer/README.md` — the KB entry point. If absent, fall back to the closest equivalent (the project brief or any top-level consensus-layer doc) and note the substitution in the curriculum header.
- The KB layout document — typically at the repo root or in onboarding materials, describing the directory contract.
- `platform-sdk/docs/consensus-layer/architecture/overview.md` — the navigation map.
- All files under `platform-sdk/docs/consensus-layer/architecture/topics/` — one per topic, eleven when complete.
- All files under `platform-sdk/docs/consensus-layer/concepts/` — one per concept.

Curriculum order is fixed; do not deviate.

1. Pass 1 — four canonical scenarios at lightweight depth: transaction to consensus; node falls behind; coordinated network upgrade; event creation under stress.
2. Cluster 0 — Wiring Framework Foundation.
3. Cluster A.1 — Hashgraph Algorithm.
4. Cluster A.2 — Event Intake.
5. Cluster A.3 — Gossip.
6. Cluster A.4 — Event Creator.
7. Cluster A.5 — Steady-state synthesis.
8. Cluster B — Stress, health, and self-throttling (Health Monitor & Backpressure plus Reasons not to gossip).
9. Cluster C — State, persistence, and recovery (Signed State Management, Restart + PCES, Reconnect).
10. Cluster D — Coordinated network events (Freeze / Upgrade).
11. Pass 3 canonicals — the four Pass 1 scenarios revisited at full depth.
12. Pass 3 edge cases — five scenarios: reconnect during freeze; fall-behind triggered by Health Monitor during heavy gossip; roster change during reconnect; PCES replay after upgrade; squelching during freeze.

Within each Pass 2 cluster, decide the sub-lesson breakdown from the KB content available at curriculum-generation time. Do not hard-code a count. Guidance:

- A typical cluster yields three to seven sub-lessons depending on concept density.
- A.1 is the concept-densest cluster and yields the most.
- Each `concepts/<concept>.md` referenced by the cluster's topic file typically anchors one sub-lesson.
- Each cluster ends with a synthesis lesson that walks the cluster's components collaborating, not just describing each in turn.
- Sub-lessons are ordered so prerequisites precede dependents.
- Cluster B contains two topics; cluster C contains three. The same sub-lesson breakdown applies — concepts within each topic anchor sub-lessons, and the synthesis lesson covers the topics together.

If the KB is shallow or empty for a cluster at curriculum-generation time, plan the entries you can defend from what is available and mark uncertain titles or ordering with `[TBD]` inline. Curriculum entries are not contracts — the user revises the manifest between runs.

Write the curriculum to `tutor/curriculum.md`. The format is markdown with a YAML manifest. Each entry has these fields:

- `lesson_id` — `<cluster>-<n>-<slug>` for Pass 2 entries, `<pass>-<n>-<slug>` for Pass 1 and Pass 3. The `<n>` is the within-cluster ordinal starting at 1; the slug is short and kebab-case.
- `cluster` — one of `pass1`, `0`, `A.1`, `A.2`, `A.3`, `A.4`, `A.5`, `B`, `C`, `D`, `pass3`.
- `title` — human-readable title.
- `source_topic_id` — for Pass 2 entries, the `architecture/topics/<topic>.md` filename without extension. Omit for Pass 1 and Pass 3.
- `scenario_kind` — for Pass 1 and Pass 3 entries, one of `canonical` or `edge-case`. Omit for Pass 2.
- `prerequisites` — list of `lesson_id`s that should precede this one. Treat as advisory; the natural order in the manifest already encodes the dependency graph.
- `status` — `planned` initially. Phase two updates to `drafted` after the lesson file is written.

Skeleton:

```yaml
---
curriculum_version: 1
generated_against: <commit-SHA-of-origin/main-at-bootstrap-time>
---
- lesson_id: pass1-1-transaction-to-consensus
  cluster: pass1
  title: Transaction to consensus
  scenario_kind: canonical
  prerequisites: []
  status: planned
- lesson_id: pass1-2-node-falls-behind
  cluster: pass1
  title: Node falls behind
  scenario_kind: canonical
  prerequisites: []
  status: planned
# ... remaining Pass 1 entries ...
- lesson_id: 0-1-task-schedulers-and-queues
  cluster: '0'
  title: Task schedulers and queues
  source_topic_id: wiring-framework
  prerequisites: [pass1-4-event-creation-under-stress]
  status: planned
# ... remaining Pass 2 entries, then Pass 3 ...
```

Above the YAML, include a short prose header explaining the file's purpose, the fixed-order policy, and a one-line note that the file is human-editable between runs and that this prompt re-reads it on every invocation.

</phase_one_bootstrap>

<phase_two_author_one_lesson>

Read `tutor/curriculum.md`. Find the first entry whose `status` is `planned` and whose lesson file at `tutor/lessons/<lesson_id>.md` does not already exist. That entry is the target. If no such entry exists, the curriculum is complete; report that and stop.

Before drafting, read the inputs from the repo. The set depends on the entry's cluster.

For Pass 2 entries (cluster in `0`, `A.1`–`A.5`, `B`, `C`, `D`):

- `platform-sdk/docs/consensus-layer/architecture/topics/<source_topic_id>.md` — the topic file that anchors this cluster or sub-cluster.
- Every concept file referenced from that topic — `platform-sdk/docs/consensus-layer/concepts/<concept>.md`.
- `platform-sdk/docs/consensus-layer/glossary.md`.
- `platform-sdk/docs/consensus-layer/invariants.md`.
- `platform-sdk/docs/consensus-layer/delta-map/<source_topic_id>.md`.
- The source code at every code anchor named in the topic file. Open each file, locate the function or line referenced, confirm it exists. If the function moved or the line number drifted from what the topic file claims, write the lesson against the current code location and note the drift in the Open questions callout.

For Pass 1 and Pass 3 entries (cluster `pass1` or `pass3`):

- `architecture/topics/<topic>.md` for every component the scenario touches.
- Every concept file referenced from those topic files.
- `glossary.md`.
- `invariants.md`.
- For Pass 3 entries that reference scenario IDs, the corresponding `scenarios/SCN-NNN-*.md` files.

Verify code anchors. Run `git rev-parse origin/main`; if `origin/main` is not configured, fall back to `git rev-parse HEAD` and note in the lesson's Open questions callout that the SHA is from the local branch rather than `origin/main`. The resolved SHA goes in the lesson's frontmatter as `last_verified_against`. The anchors themselves render as GitHub URLs against the `main` branch in the lesson body for learner readability; the SHA in frontmatter is the verification record.

Draft the lesson against the appropriate template — `lesson_template_pass2` below for Pass 2 entries, `scenario_template_pass1_pass3` for Pass 1 and Pass 3. Pass 1 entries set `depth: lightweight` and stay at altitude — name the action, not the mechanism. Pass 3 entries set `depth: full` and go into mechanism with KB citations. Scenarios in Pass 3 that revisit a Pass 1 canonical use `scenario_kind: canonical`; the five new scenarios at the end use `scenario_kind: edge-case`.

Pull definitions from `concepts/` rather than restating them, and reference each concept by markdown link to its file. Same for invariants by `INV-NNN`, glossary terms, and delta entries. The tutor-Claude reads the same KB; restating definitions in lessons creates drift surface and dilutes the spiral structure.

Lessons are production-quality drafts, not placeholders. Write the prose at the level of detail a consensus-team engineer would actually want; the comprehension prompt is one to three Socratic invitations, not a quiz. Cluster-final lessons add a `## Cluster worked example` section between Comprehension prompt and Where we're going next, showing the cluster's components collaborating rather than describing each in turn.

When the KB or code is silent on something the lesson needs, write a bracketed `[TBD]` marker in place rather than fabricating. At the end of the lesson body — for Pass 2 entries, between Comprehension prompt and Where we're going next; for Pass 1 and Pass 3 entries, after the Comprehension prompt — surface every `[TBD]` in a small callout titled "Open questions". Format each entry as a one-line restatement of what is unknown plus a pointer to where it would be answered (KB section, ADR slug, or SME). Lessons commit despite open questions; SME review fills them.

Write the lesson file at `tutor/lessons/<lesson_id>.md`.

Update `tutor/curriculum.md`: change the target entry's `status` from `planned` to `drafted`. The curriculum is the source of truth for status across the repo; tools that read the manifest depend on this update happening in the same run as the lesson write.

Stop.

<lesson_template_pass2>

Use this template verbatim for every cluster `0`, `A.1`–`A.5`, `B`, `C`, `D` entry.

```markdown
---
lesson_id: <cluster>-<n>-<slug>
cluster: <0 | A.1 | A.2 | A.3 | A.4 | A.5 | B | C | D>
title: <Lesson title>
prerequisites: [<lesson_ids>]
kb_refs:
  topics: [<topic_ids>]
  concepts: [<concept_slugs>]
  invariants: [<INV-NNN>]
  glossary_terms: [<terms>]
learning_objectives:
  - <objective 1>
  - <objective 2>
estimated_read_minutes: <n>
status: drafted
last_verified_against: <commit-SHA>
---

# <Lesson title>

## Where we are

One or two paragraphs placing this lesson in the spiral.

## Motivating problem

The specific failure or capability gap the topic addresses.

## Concept

The core mental model. Reference concepts/ files for definitions rather than restating.

## How it works

The mechanism, anchored in current code. Defer full depth to architecture/topics/.

## Worked example

A concrete, scenario-anchored walkthrough.

## Code anchor

GitHub URLs to main. The verifying SHA goes in frontmatter as last_verified_against.

## Delta callout

Reference delta-map/<topic>.md. Skip cleanly if no delta applies.

## Comprehension prompt

One to three Socratic invitations for the tutor chat.

## Where we're going next

One paragraph handing off to the next lesson.
```

Cluster-final lessons add a `## Cluster worked example` section between Comprehension prompt and Where we're going next.

</lesson_template_pass2>

<scenario_template_pass1_pass3>

Use this template verbatim for every `pass1` or `pass3` entry.

```markdown
---
lesson_id: <pass>-<n>-<slug>
cluster: pass1 | pass3
scenario_kind: canonical | edge-case
depth: lightweight | full
components_touched: [<topic_ids>]
kb_refs:
  topics: [<topic_ids>]
  concepts: [<concept_slugs>]
  invariants: [<INV-NNN>]
  scenarios: [<SCN-NNN>]
prerequisites: [<lesson_ids>]
status: drafted
last_verified_against: <commit-SHA>
---

# <Scenario name>

## What we're tracing

One or two sentences. Starting condition and end state.

## Setup

The relevant initial state. Brief.

## Walkthrough

Numbered steps. Pass 1 stays at altitude — names the action, not the mechanism. Pass 3 goes into mechanism, citing KB sections for full depth.

## Components met

Pass 1 only. Bullet list of components touched, each linking to the Pass 2 lesson covering it.

## What this exercises across clusters

Pass 3 only. One paragraph naming the clusters this scenario stitches and the interactions it stress-tests.

## Comprehension prompt

One or two Socratic invitations for the tutor chat.
```

</scenario_template_pass1_pass3>

</phase_two_author_one_lesson>

<scope_discipline>
Produce exactly one lesson per invocation. Do not expand scope beyond the template's sections. Do not pre-answer questions the tutor chat would handle Socratically — comprehension prompts are openings, not quizzes with answer keys. Do not add lessons not in the curriculum. Do not merge or split curriculum entries during a one-lesson run; if the right move is to restructure a cluster, leave a note in the Open questions of the lesson you did write and stop. The user revises the curriculum between runs.
</scope_discipline>

<tone>
Lessons are clear, direct, and technical. The audience is internal consensus-team engineers with strong distributed-systems backgrounds and high-level Hedera familiarity; assume they know the vocabulary in `glossary.md` once introduced. Lessons read alongside the tutor-Claude, so they leave Socratic openings — comprehension prompts invite, they do not interrogate. Markdown discipline: prose with code-block anchors and small structured callouts; no decorative bolding; no emoji.
</tone>

<reporting>
At the end of the run, print three lines:

- The `lesson_id` written.
- The curriculum entry index — its 1-based ordinal in the manifest.
- The count of remaining entries with `status: planned` after this run.

If the bootstrap phase ran, also print the path of the curriculum file written and the count of total entries in it. If the curriculum was already complete at the start of the run — no `planned` entries remaining and all lesson files present — print that and stop without writing anything.
</reporting>

---

## KB path reference

The paths read for each lesson type, for quick reference.

Pass 2 entries (clusters `0`, `A.1`–`A.5`, `B`, `C`, `D`):

- `platform-sdk/docs/consensus-layer/architecture/topics/<source_topic_id>.md`
- `platform-sdk/docs/consensus-layer/concepts/<each-referenced-concept>.md`
- `platform-sdk/docs/consensus-layer/glossary.md`
- `platform-sdk/docs/consensus-layer/invariants.md`
- `platform-sdk/docs/consensus-layer/delta-map/<source_topic_id>.md`
- Source files at the code anchors named in the topic file.

Pass 1 and Pass 3 entries:

- `platform-sdk/docs/consensus-layer/architecture/topics/<each-component-topic>.md`
- `platform-sdk/docs/consensus-layer/concepts/<each-referenced-concept>.md`
- `platform-sdk/docs/consensus-layer/glossary.md`
- `platform-sdk/docs/consensus-layer/invariants.md`
- For Pass 3 entries referencing scenario IDs: `platform-sdk/docs/consensus-layer/scenarios/SCN-NNN-*.md`

Bootstrap reads, only on the first invocation in a fresh repo:

- `platform-sdk/docs/consensus-layer/README.md`
- The KB layout document (repo root or onboarding materials).
- `platform-sdk/docs/consensus-layer/architecture/overview.md`
- All files under `platform-sdk/docs/consensus-layer/architecture/topics/`
- All files under `platform-sdk/docs/consensus-layer/concepts/`
