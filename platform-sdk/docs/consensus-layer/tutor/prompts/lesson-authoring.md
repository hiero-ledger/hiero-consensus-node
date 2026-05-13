# Consensus Layer Tutor — Lesson Authoring Meta-Prompt

This file is the autonomous lesson-authoring prompt for the Consensus Layer Tutor curriculum. Save at `tutor/prompts/lesson-authoring.md`. Run it from Claude Code at the root of a clone of the consensus-node repo on an authoring branch by feeding its contents as the user message (or as a slash command, if registered). It takes no parameters and idempotently advances the curriculum: on first invocation it bootstraps `tutor/curriculum.md`; on each subsequent invocation it authors exactly one lesson against the appropriate embedded template, commits the file, updates the manifest, and stops. Recommended configuration: Opus 4.7 at `xhigh` effort — the cross-source synthesis (KB topics, concept files, glossary, invariants, delta map, source code), the agentic state detection across runs, and the pedagogical shaping inside each lesson all benefit from the highest reasoning budget on the strongest agentic model.

<context>
The Consensus Layer Tutor is a curriculum-shaped Claude project that teaches the eleven consensus-layer topics to senior Hedera consensus-team engineers. The learner is expert on distributed-systems concepts (BFT, asynchrony, quorum reasoning, gossip propagation) and novice on this specific codebase (wiring framework, event format, signed-state serialization, scheduler invariants, migration deltas). Lessons are not consumed directly by the learner — the tutor system prompt drives a chat session and reads the lesson file as its working script. Lesson structure therefore supplies content in the shape the tutor needs to deliver it: chunked for the tutor's pacing model, examples shaped for the tutor's worked-example pattern, comprehension elements shaped for the tutor's retrieval and check practices.

The pedagogical foundation lives in the evidence-based pedagogy research document; the operational behavior lives in the tutor system prompt. Both are inputs to the templates embedded below. The truth basis for content is the current code as it actually runs; proposal-versus-current differences live in `delta-map/` and are referenced from lesson delta callouts rather than restated.
</context>

<task>
On every invocation, run two phases in order. Phase one bootstraps `tutor/curriculum.md` if missing. Phase two authors the next lesson in the manifest whose file does not yet exist on disk, then stops. Both phases consume only repo state — there are no parameters.
</task>

<phase_one_bootstrap>
If `tutor/curriculum.md` exists, skip this phase.

If it does not exist, generate it. Read the curriculum planning sources first: the project brief (the canonical planning document for this curriculum, typically at `tutor/brief.md` or wherever it lives in the repo — discover it), the KB layout document, `platform-sdk/docs/consensus-layer/architecture/topics/` (every topic file), `platform-sdk/docs/consensus-layer/concepts/` (every concept file), `platform-sdk/docs/consensus-layer/glossary.md`, and `platform-sdk/docs/consensus-layer/invariants.md`. These give the shape of what needs to be taught.

The manifest covers the full curriculum in pedagogical order:

- Pass 1 — four canonical orientation scenarios: transaction to consensus, node falls behind, coordinated network upgrade, event creation under stress.
- Pass 2 — Cluster 0 (Wiring Framework Foundation), then Cluster A.1 (Hashgraph Algorithm), A.2 (Event Intake), A.3 (Gossip), A.4 (Event Creator), A.5 (Steady-state synthesis), then Cluster B (Health Monitor and Backpressure, Reasons not to gossip), Cluster C (Signed State, Restart + PCES, Reconnect), Cluster D (Freeze and Upgrade).
- Pass 3 — the four canonical scenarios revisited at full depth, then five edge cases: reconnect during freeze, fall-behind triggered by Health Monitor during heavy gossip, roster change during reconnect, PCES replay after upgrade, squelching during freeze.

Within each Pass 2 cluster, derive the sub-lesson breakdown from KB content. Each cluster typically yields three to seven sub-lessons depending on concept density. Each concept file under `concepts/` that the cluster's topic touches typically anchors one sub-lesson. Each cluster ends with a synthesis lesson that exercises the cluster's components collaborating, not just describing each in turn. Order sub-lessons within a cluster so that prerequisites precede dependents — for A.1, for example, hashgraph-DAG before rounds-and-witnesses before strongly-seeing before judges before consensus-order before birth-round; ancient and stale come after birth-round.

Lesson IDs follow a stable, lexically-orderable convention. For Pass 1 scenarios: `pass1-NN-short-slug`. For Pass 2 cluster lessons: `cN-MM-short-slug` for Cluster 0, `aN-MM-short-slug` for A sub-clusters (so A.1's third lesson is `a1-03-strongly-seeing`), `b-MM-short-slug`, `c-MM-short-slug`, `d-MM-short-slug` for B/C/D. Synthesis lessons get `-syn` rather than a number: `a1-syn-hashgraph-synthesis`. For Pass 3 canonicals: `pass3-NN-short-slug-deep`. For Pass 3 edges: `pass3-edge-NN-short-slug`. Use these IDs for filenames at `tutor/lessons/<lesson_id>.md`.

Write the manifest as a markdown file with a YAML block at the top capturing the machine-readable entries, followed by a brief human-readable rendering with cluster headings. Each entry carries: `id`, `index` (global ordering), `pass` (1, 2, or 3), `cluster` (e.g. `pass1`, `c0`, `a1`, `a5-syn`, `b`, `c`, `d`, `pass3-canonical`, `pass3-edge`), `title`, `source_topics` (topic files the lesson draws from), and `status: not_started`. The file is human-editable between runs — a reviewer can rename, reorder, split, or merge entries before the next run picks up.

Stop after writing the manifest. Report the entry count and the planned cluster-by-cluster breakdown. Do not author any lesson in the same run that bootstraps the manifest.
</phase_one_bootstrap>

<phase_two_author>
Read `tutor/curriculum.md`. Scan entries in `index` order. The next lesson to author is the first entry whose target file `tutor/lessons/<lesson_id>.md` does not exist on disk. The file-existence check is the source of truth; the manifest's `status` field is metadata the reviewer can edit but is not relied on for state detection. If every entry has a file, the curriculum is complete — report that and stop.

For the chosen entry, gather the inputs the entry needs.

For Pass 2 lessons: read the source topic file(s) under `architecture/topics/`, the concept file(s) the lesson anchors on under `concepts/`, `glossary.md` (for any terms the lesson surfaces), `invariants.md` (for any INV-NNN the lesson rests on), the matching `delta-map/<source_topic>.md`, any `decisions/ADR-NNN-*.md` referenced by the topic file, and the source code at the anchors the topic file names. Verify that every cited code anchor still exists at the line range named — open the file in the repo and check. If an anchor has drifted, mark the citation `[TBD: anchor drifted from KB; verify line range]` rather than guessing. Record the current `main` HEAD SHA via `git rev-parse main` and store it as the lesson's `last_verified_against`.

For Pass 1 scenarios: read every topic file the scenario walks through (a Pass 1 scenario touches several components), the relevant concept files for terms the trace introduces, and `glossary.md`. Pass 1 stays at role-level; code anchors are not required for orientation scenarios, though concept-file links and topic-file links should be threaded through. Record `last_verified_against` against `main`.

For Pass 3 entries: read all the inputs a Pass 2 lesson would read for each topic the scenario touches, plus `invariants.md` for every invariant the scenario exercises, every relevant `delta-map/` entry, and `scenarios/SCN-NNN-*.md` for any scenario the entry references (Pass 3 edge cases often correspond to documented near-misses or historical incidents). Verify code anchors as above. Record `last_verified_against`.

Draft the lesson against the appropriate template embedded below. Pass 2 entries use `<lesson_template>`. Pass 1 and Pass 3 entries use `<scenario_template>` with `depth: orientation`, `depth: full`, or `depth: edge` respectively. Populate every section the template prescribes. Where the KB or code is silent on a question the section needs to answer, write a bracketed `[TBD: short description of what's missing]` marker in place rather than fabricating, and list every `[TBD]` at the end of the lesson under the Open questions callout. Lessons are committed despite open questions; reviewer review fills them.

Write the file at `tutor/lessons/<lesson_id>.md`. Update the manifest entry's `status` to `drafted`. Stop. Report the lesson_id written, its curriculum index, and the count of entries still without files. Do not move on to the next lesson — one invocation produces exactly one lesson.
</phase_two_author>

<lesson_template>

<!--
Template justification — section list traced to the pedagogy research and the tutor system prompt:

  - Prerequisites: tutor system prompt's entry behavior surfaces this list for
    trust-based self-assessment. Names the prereq IDs with one-line mental
    models so a senior learner can judge their own readiness.

  - Incoming retrieval probes: successive-relearning queue (Rawson & Dunlosky
    2022), free-recall opening from prior cluster (research lesson architecture).
    The tutor runs these as recall-with-feedback before new content lands.

  - Misconception watchlist: EMT-style representation (Chi et al.; tutor
    system prompt). Enumerates Paxos/Raft/PBFT imports and other senior-engineer
    wrong models the tutor listens for during delivery.

  - Productive impasse: predict-observe-explain or productive-failure (Kapur
    2008/2014/2016; Loibl et al. 2017). Sets up the scenario, the prediction
    prompt with low-stakes thinking-aloud framing, and the consolidation move
    that explicitly contrasts learner prediction with canonical mechanism.

  - Mechanism walkthrough: worked examples paired with self-explanation prompts
    at load-bearing lines, for the codebase-novice half of the split (Sweller &
    Cooper 1985; Renkl 2014; Bisra et al. 2018 g = 0.55). Segmenting and
    signaling per Mayer; pre-training of terms before integration.

  - Contrasting cases: included only when the lesson covers a threshold concept
    (Meyer & Land 2005; Boustedt et al. 2007). Gick & Holyoak 1983 — two or
    three contrasting cases with explicit comparison prompts surface the
    invariant that survives surface differences.

  - Completion problems with backward fading: Atkinson, Renkl & Merrill 2003 —
    fade scaffolding step by step as learner demonstrates the pattern. Each
    problem ships with the hint ladder the tutor uses to escalate help.

  - Delta callout: codebase-canonical policy. Pointer to delta-map entry rather
    than restating the difference.

  - Transfer prompt: forward-pointing bridge (Perkins & Salomon 1992). Primes
    the next cluster while consolidating the present one.

  - Close-out retrieval: free-recall in learner's own words plus
    successive-relearning tags (day 1, day 3, ~2 weeks) for threshold concepts.

  - Open questions: KB-silence over fabrication. Surfaces every [TBD] from the
    body so the reviewer can fill gaps without re-reading the whole lesson.

The earlier draft's section list (motivating problem, concept, how it works,
worked example, code anchor, delta callout, comprehension prompt, where we're
going next) is replaced because it conflates retrieval, impasse engineering,
and consolidation under a single "comprehension prompt" and omits the
prerequisite surface and the successive-relearning tagging the research and
system prompt both treat as load-bearing.
-->

```yaml
---
id: <lesson_id>                    # e.g. a1-03-strongly-seeing
cluster: <cluster_id>              # e.g. a1
title: <lesson title>
pass: 2
prerequisites:                     # lesson IDs whose mental models this lesson assumes
  - <prereq_lesson_id>
kb_topics:                         # paths under platform-sdk/docs/consensus-layer/
  - architecture/topics/<topic>.md
kb_concepts:
  - concepts/<concept>.md
kb_glossary_terms:                 # term keys from glossary.md
  - <term-key>
kb_invariants:                     # INV-NNN identifiers
  - INV-NNN
kb_deltas:
  - delta-map/<topic>.md
kb_decisions:                      # ADR-NNN identifiers (optional)
  - ADR-NNN
learning_objectives:               # what the learner can do after this lesson
  - <objective>
threshold_concepts:                # concepts this lesson establishes or revisits; empty if none
  - <concept name>
estimated_session_minutes: <int>
status: drafted
last_verified_against: <git-sha-of-main-at-authoring-time>
---
```

# {Lesson title}

## Prerequisites

List each prerequisite lesson by ID and give a one-line description of the mental model that lesson establishes — enough for a senior engineer to judge whether they feel solid on it. If this lesson has no prerequisites, state so explicitly:

> None — this lesson assumes only general distributed-systems background.

Do not omit this section even when prerequisites are empty; the tutor's entry behavior is uniform across lessons.

## Incoming retrieval probes

List concepts from prior lessons that should be retrieved before this lesson's new content lands on top of them. Each probe carries the concept name, a free-recall prompt for the tutor to run, and the canonical answer the tutor consolidates against. Threshold concepts from earlier lessons whose successive-relearning interval lands in this lesson's session belong here. If the lesson sits early enough in the curriculum that no probes apply, state so explicitly.

## Misconception watchlist

Enumerate the senior-engineer wrong models most likely to surface on this material. Each entry names the misconception, what it sounds like in learner utterances, and the correction the tutor applies in line. Pay particular attention to imports from Paxos, Raft, and PBFT that look right on the surface and break on closer reading of the hashgraph specifics — these are the dominant failure mode for this audience. If the topic does not surface a familiar adjacent-protocol analog, note that and list whatever else the literature or KB flags as a likely confusion.

## Productive impasse

Pose the prediction problem before delivering canonical content. The section contains:

- The framing scenario — concrete enough that the learner has something to predict against. Anchor in real test runs or KB scenarios where possible.
- The prediction prompt the tutor reads to the learner, phrased as low-stakes thinking-aloud ("what's your gut prediction here?" or "before I show what happens, what does your model say?"). Avoid quiz framing.
- Confidence elicitation when the prediction targets a concept where high-confidence wrong beliefs are likely — the hypercorrection effect is strongest there.
- The reveal — what the code or specification actually does, with a direct pointer (file path link, or GitHub URL when a code anchor is involved) rather than a restatement.
- The consolidation move — explicit contrast between the predicted behavior and the canonical mechanism. Name what the learner's model was missing and why the codebase's choice resolves it. This is the step Loibl et al. 2017 flag as essential and most often skipped.

For threshold concepts, the impasse takes a predict-observe-explain or productive-failure shape. For codebase-procedural material where the learner is a novice on the specifics, the impasse can take a worked-example-with-blanks shape — present the surrounding mechanism, leave the load-bearing step for the learner to predict.

## Mechanism

Pre-training: name the key components and terms this lesson integrates, with one-sentence semantics each. Pull definitions from `glossary.md` and `concepts/` by link rather than restating.

Then segment the mechanism into small chunks the tutor paces through. For each chunk:

- A short prose description anchored on the actual code behavior, with a link to the relevant topic file under `architecture/topics/`.
- The code anchor as a GitHub URL against `main` (the `last_verified_against` SHA in frontmatter records the verification point). Use the format `https://github.com/<org>/<repo>/blob/main/path/to/file.java#L<start>-L<end>`. Discover the canonical org/repo via `git remote get-url origin`.
- A signaling note when the chunk includes safety-critical or invariant-bearing lines versus bookkeeping — mark the load-bearing lines explicitly so the tutor knows where to invest self-explanation effort.
- A self-explanation prompt at each load-bearing line, principle-based and inference-demanding rather than open-ended. "Which invariant justifies this step?" "What failure scenario does this rule prevent?" "What breaks in the next round if we remove this check?" Avoid "explain this" — it produces restatement.
- When the chunk's design hinges on a decision with documented alternatives, link the relevant ADR under `decisions/` rather than reconstructing the rationale in prose.

## Contrasting cases

Include this section only when the lesson covers a threshold concept; omit otherwise.

Provide two or three contrasting cases — e.g. this codebase's choice versus a textbook approach, or hashgraph's mechanism versus the analogous mechanism in a familiar BFT variant. For each case, give a short concrete description, the surface differences, and the comparison prompt the tutor reads to the learner. Name the deep invariant that survives the surface differences — this is the structural transfer that the contrasting-cases technique exists to produce.

## Completion problems

Progressive problems that fade scaffolding step by step. The first problem leaves a small step blank in an otherwise complete worked example; later problems leave more blank, until the learner is producing the full step from a posed scenario. Each problem ships with:

- The problem statement.
- The hint ladder the tutor escalates through: where in the codebase or KB to look first, then a focused question, then a partial walkthrough, then the full answer. Full answers stay at the bottom rung, gated on demonstrated effort.
- The canonical answer and the invariant or mechanism it exercises.

## Delta callout

Brief callout pointing to `delta-map/<topic>.md` for the difference between current code and the proposed redesign on the material this lesson covers. Summarize the delta's status (`done`, `partial`, `not started`, `divergent`) in one line and link the file. Do not restate the delta in detail — the delta-map is the canonical source and the lesson exists alongside it.

## Transfer prompt

Forward-pointing bridge that asks the learner to predict the system's behavior under a new failure mode, or to articulate how the invariant just learned will be challenged in a future cluster. Keep it open-ended enough that the answer requires applying the lesson's mechanism to a novel situation rather than retrieving it. This primes the next cluster while consolidating the present one.

## Close-out retrieval

Two retrieval acts the tutor runs before ending the session.

- Free-recall summary: the tutor asks the learner to articulate the load-bearing invariant or mechanism in their own words. Provide the prompt and the canonical answer for the tutor to consolidate against.
- Successive-relearning tags: for each threshold concept this lesson establishes, name when it should be probed in subsequent sessions — roughly day 1, day 3, and two weeks. The tutor adds these to the learner's relearning queue. If the lesson establishes no threshold concept, state so explicitly.

## Open questions

Surface every `[TBD]` marker from the body here, each as a short line naming what the lesson needs the reviewer to fill in. If the lesson has no open questions, state so explicitly. Lessons commit despite open questions; reviewer review closes them.

</lesson_template>

<scenario_template>

<!--
Template justification — section list traced to research and system prompt:

  - Prerequisites and Incoming probes: same rationale as the lesson template;
    Pass 3 entries lean heavily on this because scenarios stitch multiple
    Pass 2 clusters and the tutor needs to retrieve their threshold concepts
    before the trace re-engages them.

  - Misconception watchlist: same EMT rationale; Pass 3 entries surface
    cross-cluster misconceptions (e.g. assuming reconnect and freeze can
    progress independently) that no single Pass 2 lesson covers.

  - Components in scope: Mayer's pre-training principle. Scenarios touch
    several components; naming each with one-sentence semantics up front
    relieves working memory before the trace integrates them.

  - Scenario setup and Productive impasse: research mandates a prediction
    before the canonical content, scaled to the depth of the scenario.
    Orientation scenarios use light prediction (role-level), full and edge
    scenarios use rigorous prediction (mechanism-level, with confidence
    elicitation for high-leverage transitions).

  - Trace: the scenario's spine. Pass 1 stays at role and component level
    (no code anchors); Pass 3 anchors each stop in code, marks load-bearing
    transitions, and surfaces cross-cluster stitch points.

  - Perturbation prompts: Pass 3 only. The project brief's success criteria
    require learners to predict behavior under perturbations; the scenario
    is where this capability is exercised.

  - Delta callouts: Pass 3 only; orientation scenarios stay at present-code
    altitude and leave delta material to the Pass 2 lessons.

  - Consolidation: same Loibl et al. 2017 rationale — explicit contrast
    between predictions and canonical, named at the end of the trace.

  - Close-out: orientation scenarios consolidate the mental sketch; full and
    edge scenarios consolidate cross-cluster invariants with
    successive-relearning tags.

  - Forward pointers: orientation scenarios point to the Pass 2 lessons that
    cover each component's mechanism in depth — completing the spiral
    pedagogy. Pass 3 entries point to related Pass 3 entries when relevant.

  - Open questions: same KB-silence-over-fabrication principle.

The depth flag (`orientation`, `full`, `edge`) tunes which sections are
fleshed out: orientation skips misconception watchlist, code anchors,
perturbation prompts, and delta callouts; full and edge populate all of them.
-->

```yaml
---
id: <scenario_id>                  # e.g. pass1-01-tx-to-consensus, pass3-edge-01-reconnect-during-freeze
cluster: <cluster_id>              # pass1, pass3-canonical, or pass3-edge
title: <scenario title>
pass: <1 or 3>
depth: <orientation | full | edge>
prerequisites:
  - <prereq_lesson_id>
kb_topics_touched:                 # every component the scenario walks through
  - architecture/topics/<topic>.md
kb_concepts:
  - concepts/<concept>.md
kb_glossary_terms:
  - <term-key>
kb_invariants:                     # populate for full and edge; empty for orientation
  - INV-NNN
kb_deltas:                         # populate for full and edge; empty for orientation
  - delta-map/<topic>.md
kb_scenarios:                      # SCN-NNN entries the scenario references; typical for edge cases
  - SCN-NNN
learning_objectives:
  - <objective>
threshold_concepts:                # populate for full and edge; empty for orientation
  - <concept name>
estimated_session_minutes: <int>
status: drafted
last_verified_against: <git-sha-of-main-at-authoring-time>
---
```

# {Scenario title}

## Prerequisites

List each prerequisite lesson by ID with a one-line description of the mental model it establishes. For Pass 1 orientation scenarios this section typically reads:

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in.

For Pass 3 canonicals and edges this section typically lists the Pass 2 lessons whose mechanisms the scenario exercises. The section is present in every scenario regardless so the tutor's entry behavior stays uniform.

## Incoming retrieval probes

For orientation scenarios: omit or note "None — orientation scenario, no incoming probes."

For full and edge scenarios: list threshold concepts from prior Pass 2 or Pass 3 entries that should be retrieved before the trace re-engages them. Each probe carries the concept, the recall prompt, and the canonical answer for the tutor to consolidate against. Cross-cluster scenarios depend on this — without retrieving the relevant Pass 2 mechanisms, the learner cannot follow the cross-cluster interaction.

## Misconception watchlist

For orientation scenarios: omit. Orientation work is too shallow to surface deep misconceptions.

For full and edge scenarios: enumerate likely wrong models the learner may hold, especially cross-cluster ones that no single Pass 2 lesson would address — e.g. assuming a reconnect can interleave freely with a freeze, or assuming PCES replay restores state to exactly the pre-shutdown moment. Each entry names the misconception, the learner utterance to listen for, and the correction.

## Components in scope

Pre-training section. Name each component the scenario touches and give a one-sentence semantic for each, linked to its topic file under `architecture/topics/`. Orientation scenarios stop here for component vocabulary; full and edge scenarios use this as a reminder before the deeper trace begins.

## Scenario setup

The initial state of the system and the triggering event or events that start the trace. Concrete enough that the learner can hold the state mentally — name the relevant nodes, the relevant queues or buffers, the relevant rounds. Anchor in a real test run or a documented `scenarios/SCN-NNN` entry where one applies.

## Productive impasse

For orientation scenarios: a light prediction at role level. "Before I walk through what happens, which components do you think get involved here, and in what order?" Frame as thinking-aloud.

For full and edge scenarios: a rigorous prediction. Pose a high-leverage question whose answer hinges on a load-bearing mechanism in the trace. Elicit confidence when high-confidence wrong beliefs are likely. The reveal comes through the trace itself; the consolidation comes at the end of the trace.

## Trace

The scenario's spine, segmented into stops. Each stop carries:

- The component or transition the stop sits at, linked to its topic file.
- A short prose description of what happens at the stop, at the appropriate altitude — role-level for orientation, mechanism-level for full and edge.
- A code anchor as a GitHub URL against `main` (full and edge only; orientation omits code anchors and stays at role level).
- Predict-then-reveal moves at high-leverage transitions, especially in full and edge scenarios. Frame as thinking-aloud, not quiz.
- Self-explanation prompts at invariant-bearing transitions (full and edge), principle-based — "which invariant survives this transition?" "what would break if this component skipped this step?"
- Cross-cluster stitch callouts (full and edge) at points where this scenario touches a mechanism from a different cluster than the one the current stop sits in. Name the stitch explicitly — these are the cross-cluster interactions the Pass 2 lessons could not teach in isolation.

For orientation scenarios the trace is the whole point: walk the components and their roles, plant the complete-but-low-fidelity mental sketch, do not go deep on mechanism. For full and edge scenarios the trace integrates everything the prerequisite Pass 2 lessons established.

## Perturbation prompts

Omit for orientation scenarios.

For full and edge scenarios: questions of the form "what changes if X happens at this point in the trace?" — the perturbation prediction the project brief's success criteria require. Each prompt names the perturbation, the trace stop it applies to, and the canonical answer for consolidation. Edge cases often have the perturbation prompt as the spine of the scenario itself, since the edge case is the perturbation.

## Delta callouts

Omit for orientation scenarios — they stay at present-code altitude and leave deltas to the Pass 2 lessons.

For full and edge scenarios: brief callouts pointing to `delta-map/<topic>.md` for each touched component whose redesign would alter the trace. Summarize the delta's effect on the scenario in one line and link the file. Do not restate the delta.

## Consolidation

Explicit contrast between the learner's predictions and the canonical mechanism, named at the end of the trace. For orientation scenarios this is a brief consolidation of the mental sketch — "the learner should now be able to name each component and its role." For full and edge scenarios this is the deep consolidation: which invariants the trace exercised, where the prediction missed, what the codebase's choice resolves, and (for cross-cluster scenarios) which cluster interactions the trace surfaced.

## Close-out

For orientation scenarios: a brief mental-sketch consolidation, plus a pointer to the Pass 2 lessons that deepen each component (these belong in Forward pointers below as well, but a sentence in the close-out reinforces the spiral).

For full and edge scenarios: two retrieval acts as in the lesson template — a free-recall summary of the load-bearing cross-cluster invariant in the learner's own words, and successive-relearning tags for any threshold concept the scenario revisited or established.

## Forward pointers

For orientation scenarios: the Pass 2 lesson IDs that cover each touched component's mechanism in depth. Completing the spiral — orientation plants the sketch, Pass 2 fills the mechanism, Pass 3 stitches them.

For full and edge scenarios: links to related Pass 3 entries that share components or invariants, so the curriculum's last-stretch cross-references stay visible.

## Open questions

Surface every `[TBD]` marker from the body. Same KB-silence-over-fabrication principle as the lesson template.

</scenario_template>

<coherence_with_tutor_system_prompt>
Each authored lesson supplies content in the shape the tutor's delivery model needs. The coherence requirements below are the load-bearing ones — write each lesson so the tutor can deliver it without improvising the scaffolding the system prompt expects to find pre-shaped.

The tutor surfaces the Prerequisites section at session entry and asks for confirmation rather than probing. Write each prerequisite description as a single line that a senior engineer can read and respond to with a yes-or-no readiness judgement — not as a paragraph, not as a checklist of sub-topics. The section is uniform across all lessons including those with no prerequisites.

The tutor runs incoming retrieval probes early in the session as recall-with-feedback. Each probe needs three components present in the lesson: the concept being retrieved, the prompt the tutor reads to the learner, and the canonical answer the tutor consolidates against. The probe is run as a thinking-aloud move, not a quiz.

The tutor escalates a hint ladder when the learner is stuck — point to where to look, then a focused question, then a partial walkthrough, then the full answer. The Completion problems section ships each problem with its hint ladder so the tutor does not have to invent the rungs in the moment.

The tutor elicits predictions before revealing mechanisms at high-leverage moments. The Productive impasse section pre-stages the prediction prompt, the framing scenario, and the consolidation move so the predict-observe-explain sequence is structurally complete when the tutor reaches it. Without the consolidation move pre-staged the effect collapses.

The tutor uses different postures for concept-level versus codebase-procedural material within the same lesson. The Mechanism section marks chunks that are concept-level (where the tutor defaults to less scaffolding) versus codebase-procedural (where the tutor defaults to worked examples with self-explanation prompts at the load-bearing lines). Signaling load-bearing versus bookkeeping is how the tutor knows where to invest self-explanation effort.

The tutor refuses restatement as self-explanation and pushes for inference. Self-explanation prompts at load-bearing lines are principle-based ("which invariant justifies this step?") rather than open ("explain this"), so the prompt itself elicits inference rather than paraphrase.

The tutor uses the Misconception watchlist during delivery to detect Paxos/Raft/PBFT imports and other senior-engineer wrong models. Each entry pre-stages the learner utterance to listen for and the correction to apply, so the tutor recognizes the misconception in line rather than discovering it from scratch.

The tutor declines to fabricate when the KB is silent. Open questions surface every gap so the tutor and the reviewer share a single canonical list rather than the tutor improvising answers and the reviewer never seeing them.

Across the curriculum, scaffolding withdraws as the learner progresses through a cluster. Earlier lessons in a cluster carry fuller worked examples and richer hint ladders; later lessons in the cluster — and especially the synthesis lesson at A.5 or each cluster's terminal entry — present the same content shape with less scaffolding, so the tutor's contingent fading has material at the right altitude. When authoring a later-cluster lesson, write the completion problems with fewer hint rungs and the mechanism walkthrough with fewer signaling markers than the cluster's opening lessons.
</coherence_with_tutor_system_prompt>

<authoring_principles>
Lessons are production quality, not stubs. Each section is populated with real content drawn from the KB and code, not placeholder text. The lesson is committed and run by the tutor as-is; subsequent reviewer review fills `[TBD]` markers and tightens phrasing.

Cite the KB by link rather than restating. `concepts/` files own the canonical mental models; `architecture/topics/` files own mechanism prose; `glossary.md` owns term definitions; `invariants.md` owns INV-NNN claims; `delta-map/` files own current-versus-proposed differences; `decisions/` owns ADR rationale. The lesson exists alongside these files and gains nothing by copying them — link instead, and the lesson stays evergreen as the KB updates.

Code anchors are GitHub URLs against `main`, formatted `https://github.com/<org>/<repo>/blob/main/path#L<start>-L<end>`. Discover the canonical org/repo via `git remote get-url origin` rather than hardcoding. The `last_verified_against` SHA in frontmatter records the verification point; the lesson body links against `main` so the reader sees the current line ranges, and the tutor can flag drift against the recorded SHA.

When the KB or the code does not speak to a question the lesson needs to answer, write `[TBD: short description]` rather than fabricating. Surface every marker at the end under Open questions. The audience trusts the tutor precisely because it does not pretend to know things it does not — this discipline starts at the lesson level.

Avoid restating concept-level mechanisms the audience already understands. Senior engineers have strong distributed-systems schemas; front-loading textbook BFT explanations is expertise-reversal harm and reads as condescension. For concept-level material, anchor the lesson on prediction and contrast against the canonical mechanism; reserve heavy worked-example scaffolding for codebase-procedural material where the learner is a novice on the specifics.

Do not pre-answer the questions the tutor's dialogue would handle. The lesson is the tutor's script, not a transcript. A completion problem provides the problem and the hint ladder and the canonical answer — it does not provide the conversation that gets the learner from one to the other. That conversation is the tutor's job, conducted live.

Stay within the template's section list. Do not add sections the template does not name; do not split or merge sections; do not introduce new frontmatter fields. The template encodes the tutor's expected delivery surface, and adding sections silently breaks that surface.

Author exactly one lesson per invocation. The next invocation picks up the next entry. Do not author lessons not in the manifest; do not merge or split manifest entries during a one-lesson run. If the manifest entry feels wrong — too narrow, too broad, mis-ordered — report this in the run summary rather than acting on it; the reviewer edits the manifest between runs.
</authoring_principles>

<output_protocol>
On a bootstrap run: write `tutor/curriculum.md`, report the total entry count and the cluster-by-cluster breakdown, and stop without authoring any lesson.

On an authoring run: write `tutor/lessons/<lesson_id>.md`, update the manifest entry's `status` to `drafted`, and report three things — the `lesson_id` written, its `index` in the manifest, and the count of entries that still lack files. Stop.

Implement changes directly rather than proposing them. If a detail is ambiguous (which concept file anchors a sub-lesson, how to slug a Pass 3 edge entry's filename), infer the most useful action and proceed; record any inference worth a reviewer's attention in the run summary.
</output_protocol>

<kb_paths_reference>

For Pass 2 lessons, an authoring run reads:

- `architecture/topics/<source_topic>.md` — mechanism prose, code anchors, cross-references.
- `concepts/<concept>.md` — canonical mental models for the lesson's anchor concepts.
- `glossary.md` — term definitions the lesson surfaces.
- `invariants.md` — INV-NNN claims the lesson rests on.
- `delta-map/<source_topic>.md` — current-versus-proposed differences for the delta callout.
- `decisions/ADR-NNN-*.md` — alternatives and consequences when the lesson hinges on a documented decision.
- The source code at the line ranges the topic file's anchors name.

For Pass 1 orientation scenarios, an authoring run reads:

- `architecture/topics/<topic>.md` — one file per component the scenario walks through.
- `concepts/<concept>.md` — for any term the trace introduces.
- `glossary.md`.

For Pass 3 canonicals and edges, an authoring run reads everything a Pass 2 lesson would read for each touched topic, plus:

- `invariants.md` — every invariant the scenario exercises.
- `delta-map/<topic>.md` — every touched component whose redesign would alter the trace.
- `scenarios/SCN-NNN-*.md` — for any scenario the entry references (typical for edge cases that correspond to documented near-misses).

For the bootstrap run, additional reads:

- The project brief (the curriculum planning document, discoverable in the repo).
- The KB layout document.
- The full listings of `architecture/topics/`, `concepts/`, and any other directory under `platform-sdk/docs/consensus-layer/` whose contents shape the cluster-by-cluster sub-lesson breakdown.

</kb_paths_reference>