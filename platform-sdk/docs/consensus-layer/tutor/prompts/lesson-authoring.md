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

For Pass 2 lessons: read the source topic file(s) under `architecture/topics/`, the concept file(s) the lesson anchors on under `concepts/`, `glossary.md` (for any terms the lesson surfaces), `invariants.md` (for any INV-NNN the lesson rests on), the matching `delta-map/<source_topic>.md`, any `decisions/ADR-NNN-*.md` referenced by the topic file, and the source code at the anchors the topic file names. Then read the neighboring topic files — every topic the source topic file cross-references in its "Cross-references" section or links to inline. The lesson's claims about how the source topic interacts with its neighbors must be consistent with what those neighbor topic files say. A lesson on the event creator cannot describe a self-event flowing directly to PCES if the intake topic file says intake sits between them; reading both topics is how the authoring run catches the contradiction before it is written. Verify that every cited code anchor still exists at the line range named — open the file in the repo and check. If an anchor has drifted, mark the citation `[TBD: anchor drifted from KB; verify line range]` rather than guessing. Record the current `main` HEAD SHA via `git rev-parse main` and store it as the lesson's `last_verified_against`.

For Pass 1 scenarios: read every topic file the scenario walks through (a Pass 1 scenario touches several components), plus the neighboring topic files that any of those topics cross-reference where the cross-reference is on the scenario's flow path. Read the relevant concept files for terms the trace introduces, and `glossary.md`. Pass 1 stays at role-level; code anchors are not required for orientation scenarios, though concept-file links and topic-file links should be threaded through. Record `last_verified_against` against `main`.

For Pass 3 entries: read all the inputs a Pass 2 lesson would read for each topic the scenario touches, plus their neighboring topic files. Read `invariants.md` for every invariant the scenario exercises, every relevant `delta-map/` entry, and `scenarios/SCN-NNN-*.md` for any scenario the entry references (Pass 3 edge cases often correspond to documented near-misses or historical incidents). Verify code anchors as above. Record `last_verified_against`.

Draft the lesson against the appropriate template embedded below. Pass 2 entries use `<lesson_template>`. Pass 1 and Pass 3 entries use `<scenario_template>` with `depth: orientation`, `depth: full`, or `depth: edge` respectively. Populate every section the template prescribes. Every mechanism claim in the lesson body — every sentence describing what a component does, how components are wired, what flows where, which invariant holds — carries a link to the topic file or code anchor that grounds it. Sentences that paraphrase a mechanism without an anchor are the path by which lesson sections drift from the KB and from each other; an anchor on every claim makes drift visible. Where the KB or code is silent on a question the section needs to answer, write a bracketed `[TBD: short description of what's missing]` marker in place rather than fabricating, and list every `[TBD]` at the end of the lesson under the Open questions callout. Lessons are committed despite open questions; reviewer review fills them.

Before writing the file, run a consistency pass on the draft. Three checks:

- KB consistency: for every mechanism claim in the draft, verify the claim against the topic file or code anchor it cites. Pay particular attention to claims about wiring between components — what flows where, in what order, with what intermediaries — because these are the claims most likely to drift from neighbor topic files. A claim that contradicts the cited source is a bug; rewrite the claim to match the source, or move the claim to a `[TBD]` if the source is genuinely ambiguous.
- Internal consistency: scan every section of the lesson for descriptions of the same mechanism — the Mechanism chunks, the Engagement moves entries and their canonical answers, the Consolidation, the Contrasting cases material, perturbation answers in scenarios. If two sections describe the same mechanism, the descriptions must agree. Stop 3 saying event-creator → PCES → fan-out and the Consolidation saying event-creator → intake → PCES → fan-out is the failure mode this check catches. Reconcile against the KB source, not against the draft's other section.
- Shape consistency: for every prediction-and-reveal move in the Engagement moves section, verify that the prompt shape matches the shape of the canonical answer and the shape of the underlying mechanism. A prompt that asks for a list when the mechanism is a fan-out graph, or a prompt that asks for a single answer when the mechanism has multiple correct shapes, produces a learner who answers correctly and gets scored against a mis-shaped canonical. Rewrite the prompt to match the mechanism's shape, or add structurally-correct alternative answers to the move's consolidation (see the lesson template's Engagement moves section for the shape descriptor and alternatives fields).

If any check finds an inconsistency Claude Code cannot reconcile against the KB without guessing, mark the relevant claim `[TBD]` and surface it in Open questions rather than committing the contradiction. The consistency pass is not optional even on tight runs — the bugs it catches are the ones the tutor will deliver to the learner as confident-but-wrong content, which is worse than a deferred lesson.

Write the file at `tutor/lessons/<lesson_id>.md`. Update the manifest entry's `status` to `drafted`. Stop. Report the lesson_id written, its curriculum index, the count of entries still without files, and a one-line summary of what the consistency pass flagged (including resolutions and any new `[TBD]` markers added during the pass). Do not move on to the next lesson — one invocation produces exactly one lesson.
</phase_two_author>

<lesson_template>

<!--
Template justification — section list traced to the pedagogy research and the tutor system prompt:

  - Prerequisites: tutor system prompt's entry behavior surfaces this list for
    trust-based self-assessment. Names the prereq IDs with one-line mental
    models so the learner can judge their own readiness.

  - Incoming retrieval probes: successive-relearning queue (Rawson & Dunlosky
    2022), free-recall opening from prior cluster (research lesson architecture).
    The tutor runs these as recall-with-feedback before new content is added.

  - Misconception watchlist: EMT-style representation (Chi et al.; tutor
    system prompt). Enumerates likely wrong models — adjacent-protocol imports
    and over-generalizations from familiar parts of this codebase — that the
    tutor listens for during delivery.

  - Mechanism: the lesson's spine. Segmented into chunks the tutor paces
    through, with load-bearing lines signaled explicitly (Mayer signaling).
    Pre-training of terms before integration. The default delivery move on a
    chunk is a direct walk with a cued check; richer move types live in the
    Engagement moves section and are invoked contingent on what the learner
    shows.

  - Engagement moves: an inventory of move types the tutor may invoke at named
    moments in the lesson. Five types: prediction-and-reveal (Kapur 2008/2014;
    Loibl et al. 2017; hypercorrection effect), worked example with
    self-explanation (Sweller & Cooper 1985; Renkl 2014; Bisra et al. 2018
    g = 0.55), direct walk with cued check (Kalyuga expertise-reversal;
    Adesope et al. testing-effect meta-analysis), contrasting cases (Gick &
    Holyoak 1983; Gentner structure-mapping), and free recall (Roediger &
    Karpicke testing effect). Each moment in the lesson lists which moves are
    available and what each one supplies. The tutor picks contingent on
    diagnosed prior knowledge for the specific point in front of it, and
    varies move type across moments so the session does not become monotonous.

  - Contrasting cases material: a separate section (not a separate move type)
    that supplies the two-or-three cases needed when a threshold concept is in
    scope (Meyer & Land 2005; Boustedt et al. 2007). The Engagement moves
    section references this material when listing the contrasting-cases move
    at a moment.

  - Completion problems with backward fading: Atkinson, Renkl & Merrill 2003 —
    fade scaffolding step by step as the learner demonstrates the pattern. Each
    problem ships with the hint ladder the tutor uses to escalate help.

  - Delta callout: codebase-canonical policy. Pointer to delta-map entry rather
    than restating the difference.

  - Transfer prompt: forward-pointing bridge (Perkins & Salomon 1992). Primes
    the next cluster while consolidating the present one.

  - Close-out retrieval: free-recall in learner's own words plus
    successive-relearning tags (day 1, day 3, ~2 weeks) for threshold concepts.

  - Open questions: KB-silence over fabrication. Surfaces every [TBD] from the
    body so the reviewer can fill gaps without re-reading the whole lesson.

Design constraint on Engagement moves: prediction-and-reveal is used only when
the learner can produce a coherent answer from prior lessons and existing
schemas. Prompts that would require concepts this lesson has not yet
introduced are excluded — they produce frustration rather than productive
failure (Loibl et al. 2017 fidelity conditions). For material the learner is
new to on this codebase, the appropriate engagement move is a worked example
with self-explanation prompts, not a prediction.

The earlier draft's section list (motivating problem, concept, how it works,
worked example, code anchor, delta callout, comprehension prompt, where we're
going next) is replaced because it conflates retrieval, impasse engineering,
and consolidation under a single "comprehension prompt" and omits the
prerequisite surface and the successive-relearning tagging the research and
system prompt both treat as load-bearing. The intermediate draft's rigid
"Productive impasse" section is also replaced, because hardcoding
prediction-first openings forces the tutor into a posture the runtime evidence
may not support and the engagement-moves inventory generalizes the technique
appropriately.
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

List each prerequisite lesson by ID and give a one-line description of the mental model that lesson establishes — enough for the learner to judge whether they feel solid on it. If this lesson has no prerequisites, state so explicitly:

> None — this lesson assumes only general distributed-systems background.

Do not omit this section even when prerequisites are empty; the tutor's entry behavior is uniform across lessons.

## Incoming retrieval probes

List concepts from prior lessons that should be retrieved before this lesson's new content is added on top of them. Each probe carries the concept name, a free-recall prompt for the tutor to run, and the canonical answer the tutor consolidates against. Threshold concepts from earlier lessons whose successive-relearning interval falls in this lesson's session belong here. If the lesson sits early enough in the curriculum that no probes apply, state so explicitly.

## Misconception watchlist

Enumerate the wrong models most likely to surface on this material. Each entry names the misconception, what it sounds like in learner utterances, and the correction the tutor applies in line. Two categories tend to dominate. The first is imports from other consensus protocols — Paxos, Raft, PBFT — that look right at the surface and break on closer reading of the hashgraph specifics. The second is over-generalization from a familiar part of this codebase: a learner who has spent months in one subsystem may carry assumptions from there into the topic this lesson covers, and those assumptions may not hold. List both kinds when they apply. If the topic surfaces neither, note that and list whatever else the KB or the literature flags as a likely confusion.

## Mechanism

The lesson's spine. Read by the tutor as the script for what to cover; the engagement-moves section below supplies the techniques the tutor may use at named moments along this spine.

Start with pre-training: name the key components and terms this lesson integrates, with one-sentence semantics each. Pull definitions from `glossary.md` and `concepts/` by link rather than restating them. The tutor uses this list to set vocabulary before the spine begins.

Then segment the spine into chunks the tutor paces through. For each chunk:

- A short prose description anchored on the actual code behavior, with a link to the relevant topic file under `architecture/topics/`.
- The code anchor as a GitHub URL against `main` (the `last_verified_against` SHA in frontmatter records the verification point). Use the format `https://github.com/<org>/<repo>/blob/main/path/to/file.java#L<start>-L<end>`. Discover the canonical org/repo via `git remote get-url origin`.
- A signaling note when the chunk includes safety-critical or invariant-bearing lines versus bookkeeping — mark the load-bearing lines explicitly so the tutor knows where to invest self-explanation effort if it uses that move.
- When the chunk's design hinges on a decision with documented alternatives, link the relevant ADR under `decisions/` rather than reconstructing the rationale in prose.
- A `moment_id` for any chunk the engagement-moves section attaches a move inventory to. Not every chunk needs a moment_id — only those rich enough to warrant a choice of teaching technique.

The tutor's default delivery on a chunk without an attached moment is a direct walk: state what the code does, cite the anchor, and move on. Chunks with an attached moment offer the tutor a choice of moves, described next.

## Engagement moves

A small inventory of teaching moves the tutor may invoke at named moments along the spine. The tutor picks contingent on what it sees from the learner: which moves fit depends on whether the learner is showing fluency, novelty, or a likely misconception on the specific point in front of it. The tutor also varies move type across moments to keep the session from becoming monotonous.

A lesson typically has two to four moments with engagement-move inventories — the load-bearing transitions, the threshold-concept introduction, the points where misconceptions are most likely. Not every chunk in the Mechanism section warrants a moment. The reviewer's eye on the lesson is partly an eye on whether the moments are well-chosen.

Each moment in this section names:

- The `moment_id` matching a chunk in the Mechanism section.
- A one-sentence description of why this moment is load-bearing.
- An inventory of available moves, each tagged with the diagnosis it fits. Supply at least two moves per moment so the tutor has a real choice.

The available move types:

**Prediction-and-reveal.** Supply only when the question can be answered from prior lessons and existing distributed-systems schemas. Include the framing scenario, the prediction prompt phrased as low-stakes thinking-aloud ("what's your gut prediction?", "before I show what happens, what does your model say?"), an optional confidence elicitation when high-confidence wrong beliefs are likely, the canonical answer with code anchor, and the consolidation move that names the gap between the prediction and the canonical mechanism. Diagnosis tag: the learner is showing strong grasp of the surrounding material or is likely to hold a confidently-wrong adjacent-protocol intuition. Do not supply this move type if the prediction would require concepts the lesson has not yet established — that is not productive failure, just frustration. Two additional fields are needed on every prediction-and-reveal entry. First, an `answer_shape` descriptor naming the shape the canonical answer takes — "sequential list of stages," "fan-out graph from component X," "state transition with K branches," "single invariant statement," or whatever fits. The shape of the prompt must match the shape of the mechanism: a prompt that asks for a list when the mechanism is a fan-out graph scores a correct learner as wrong. Second, an `alternative_correct_answers` field listing two or three plausible answer shapes that should be credited as correct — a learner who says "PCES fans out to gossip and hashgraph in parallel" gives a correct graph-shaped answer to a graph-shaped question, and the consolidation needs to recognize this rather than score against missing-from-the-list. The consolidation move uses these alternatives to map the learner's structure onto the lesson's structure when they differ in shape but agree in substance.

**Worked example with self-explanation.** Supply when the learner is likely new to this point on this codebase. Include the example, the load-bearing lines marked explicitly, and a principle-based self-explanation prompt at each load-bearing line — "which invariant justifies this step?", "what failure scenario does this rule prevent?", "what breaks if we remove this check?" Avoid "explain this," which produces restatement. Diagnosis tag: the learner is asking for an example, hesitating on terms specific to this code path, or showing they have not encountered this subsystem before.

**Direct walk with cued check.** Supply when the chunk is content the learner is likely fluent on but the tutor needs to verify before moving on. State the mechanism briefly, then run a short cued-recall or application check — "given this snippet, what is the precondition?" or "in this trace, which line would violate the invariant?" Diagnosis tag: the learner has shown fluency on the surrounding material; a fuller scaffolding would be redundant.

**Contrasting cases with comparison prompt.** Supply when the moment introduces a threshold concept and the lesson includes contrasting-cases material in the section below. Reference the cases by name, supply the comparison prompt ("what is the same across these, what is different, what is the invariant that survives?"), and name the deep invariant the cases surface. Diagnosis tag: threshold concept; transfer is the goal.

**Free recall.** Supply when the moment lands on something the lesson has already covered and the tutor needs the learner to retrieve it in their own words. Include the prompt and the canonical answer for consolidation. Diagnosis tag: mid-session check on something just covered, or a retrieval cycle the lesson wants to run before the next chunk.

Each moment should usually offer the worked-example move and at least one other, so the tutor has a fallback when the diagnosis points toward novelty. The contrasting-cases move is available only at moments tied to threshold concepts.

## Contrasting cases material

Include this section only when the lesson covers a threshold concept; omit otherwise. The Engagement moves section references this material when it lists the contrasting-cases move at a moment.

Provide two or three contrasting cases — e.g. this codebase's choice versus a textbook approach, or hashgraph's mechanism versus the analogous mechanism in a familiar BFT variant. For each case, give a short concrete description and the surface differences. Then name the deep invariant that survives the surface differences. This is the structural transfer the contrasting-cases technique exists to produce; without an explicit invariant, the cases sit side by side without doing the pedagogical work.

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

  - Scenario setup: the initial state and triggering event the trace starts
    from. Concrete enough that the learner can hold the state mentally before
    the first stop.

  - Trace: the scenario's spine. Pass 1 stays at role and component level
    (no code anchors); Pass 3 anchors each stop in code, marks load-bearing
    transitions, and surfaces cross-cluster stitch points. The trace is
    annotated with named moments where engagement-moves apply.

  - Engagement moves: an inventory of move types the tutor may invoke at named
    moments along the trace, mirroring the lesson template. Five types:
    prediction-and-reveal, worked example with self-explanation, direct walk
    with cued check, contrasting cases, and free recall. The tutor picks
    contingent on diagnosed prior knowledge for the specific transition in
    front of it, and varies move type across moments so the trace does not
    become a sequence of similar predictions. Orientation scenarios have
    fewer moments (often only one or two, at the start) and lighter moves
    (typically role-level prediction-and-reveal or direct walks); full and
    edge scenarios populate more moments with richer moves.

  - Perturbation prompts: Pass 3 only. The project brief's success criteria
    require learners to predict behavior under perturbations; the scenario
    is where this capability is exercised. Each perturbation is itself a
    moment with an engagement-moves inventory.

  - Delta callouts: Pass 3 only; orientation scenarios stay at present-code
    altitude and leave delta material to the Pass 2 lessons.

  - Consolidation: same Loibl et al. 2017 rationale — explicit contrast
    between learner predictions and canonical, named at the end of the trace.

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

Same design constraint as the lesson template applies to engagement moves
here: prediction-and-reveal is only supplied when the prediction is
answerable from prior lessons and existing schemas. A scenario whose
prediction prompt would require concepts the trace has not yet surfaced
should use a different move type at that moment.
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

## Trace

The scenario's spine, segmented into stops. Read by the tutor as the script for what to walk through; the engagement-moves section below supplies the techniques the tutor may use at named moments along the trace.

Each stop carries:

- The component or transition the stop sits at, linked to its topic file.
- A short prose description of what happens at the stop, at the appropriate altitude — role-level for orientation, mechanism-level for full and edge.
- A code anchor as a GitHub URL against `main` (full and edge only; orientation omits code anchors and stays at role level).
- A signaling note when the stop sits at a load-bearing transition versus a bookkeeping one — mark these explicitly so the tutor knows where to invest engagement-move effort.
- Cross-cluster stitch callouts (full and edge) at points where this scenario touches a mechanism from a different cluster than the one the current stop sits in. Name the stitch explicitly — these are the cross-cluster interactions the Pass 2 lessons could not teach in isolation.
- A `moment_id` for any stop the engagement-moves section attaches a move inventory to. Not every stop needs a moment_id — only those rich enough to warrant a choice of teaching technique.

The tutor's default delivery on a stop without an attached moment is a direct walk: state what happens at this stop, cite the anchor when one applies, and continue. Stops with an attached moment offer the tutor a choice of moves.

For orientation scenarios the trace is the whole point: walk the components and their roles, plant the complete-but-low-fidelity mental sketch, do not go deep on mechanism. Orientation scenarios typically have one or two moments — often at the start, to elicit a role-level prediction of which components will be involved — and the rest of the trace is direct walks. For full and edge scenarios the trace integrates everything the prerequisite Pass 2 lessons established, with more moments populated along the load-bearing transitions.

## Engagement moves

A small inventory of teaching moves the tutor may invoke at named moments along the trace. The tutor picks contingent on what it sees from the learner, and varies move type across moments to keep the session from becoming monotonous. The five available move types mirror the lesson template.

Each moment in this section names:

- The `moment_id` matching a stop in the Trace section (or a perturbation in the section below, if the perturbation itself is the moment).
- A one-sentence description of why this moment is load-bearing.
- An inventory of available moves, each tagged with the diagnosis it fits. Supply at least two moves per moment where possible.

The available move types:

**Prediction-and-reveal.** Supply only when the question can be answered from prior lessons and existing schemas — for full and edge scenarios, that includes everything the prerequisite Pass 2 lessons established. Include the framing scenario, the prediction prompt phrased as low-stakes thinking-aloud, optional confidence elicitation, the canonical answer with code anchor when applicable, and the consolidation move. For orientation scenarios this move is light and role-level ("which components do you think get involved here, and in what order?"). For full and edge scenarios it is rigorous and mechanism-level. Do not supply this move type at a moment where the prediction would require a mechanism the trace has not yet reached. Every prediction-and-reveal entry also carries an `answer_shape` descriptor and an `alternative_correct_answers` field with two or three plausible answer shapes that should be credited as correct. A scenario's trace is often graph-shaped — fan-outs, parallel branches, cross-cluster stitches — and a prompt that asks for a sequential list when the mechanism is a graph scores a correct learner as wrong. The shape descriptor and the alternatives let the consolidation map the learner's structure onto the scenario's structure when they differ in shape but agree in substance.

**Worked example with self-explanation.** Supply when the learner is likely new to the subsystem the current stop sits in. Include the example and a principle-based self-explanation prompt at each load-bearing line — "which invariant survives this transition?", "what would break if this component skipped this step?" Diagnosis tag: the learner is hesitating on the current component or asking for clarification on how it behaves.

**Direct walk with cued check.** Supply when the stop is content the learner is likely fluent on but the tutor needs to verify before moving on. State what happens, then run a short cued-recall or application check. Diagnosis tag: the learner has shown fluency on the surrounding components.

**Contrasting cases with comparison prompt.** Supply only at moments that introduce or revisit a threshold concept, and only if contrasting-cases material is available in the prerequisite lessons or in this scenario. Reference the cases, supply the comparison prompt, and name the deep invariant they surface.

**Free recall.** Supply at moments that land on something already covered earlier in the trace or in a prerequisite lesson, when the tutor needs the learner to articulate it before moving on. Include the prompt and the canonical answer for consolidation.

Each load-bearing moment should usually offer the worked-example move and at least one other, so the tutor has a fallback when the diagnosis points toward novelty.

## Perturbation prompts

Omit for orientation scenarios.

For full and edge scenarios: questions of the form "what changes if X happens at this point in the trace?" — the perturbation prediction the project brief's success criteria require. Each perturbation is itself a moment, with its own `moment_id` and entry in the Engagement moves section (typically the prediction-and-reveal move). Name the perturbation, the trace stop it applies to, and the canonical answer for consolidation. Edge cases often have the perturbation prompt as the spine of the scenario itself, since the edge case is the perturbation.

## Delta callouts

Omit for orientation scenarios — they stay at present-code altitude and leave deltas to the Pass 2 lessons.

For full and edge scenarios: brief callouts pointing to `delta-map/<topic>.md` for each touched component whose redesign would alter the trace. Summarize the delta's effect on the scenario in one line and link the file. Do not restate the delta.

## Consolidation

Explicit contrast between the learner's predictions and the canonical mechanism, named at the end of the trace. The tutor consolidates against any prediction-and-reveal moves it ran during the trace; this section gives it the canonical answer to consolidate against. For orientation scenarios this is a brief consolidation of the mental sketch — the learner should now be able to name each component and its role. For full and edge scenarios this is the deep consolidation: which invariants the trace exercised, where any prediction missed, what the codebase's choice resolves, and (for cross-cluster scenarios) which cluster interactions the trace surfaced.

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

The tutor surfaces the Prerequisites section at session entry and asks for confirmation rather than probing. Write each prerequisite description as a single line that the learner can read and respond to with a yes-or-no readiness judgement — not as a paragraph, not as a checklist of sub-topics. The section is uniform across all lessons including those with no prerequisites.

The tutor runs incoming retrieval probes early in the session as recall-with-feedback. Each probe needs three components present in the lesson: the concept being retrieved, the prompt the tutor reads to the learner, and the canonical answer the tutor consolidates against. The probe is run as a thinking-aloud move, not a quiz.

The tutor escalates a hint ladder when the learner is stuck — point to where to look, then a focused question, then a partial walkthrough, then the full answer. The Completion problems section ships each problem with its hint ladder so the tutor does not have to invent the rungs in the moment.

The tutor diagnoses prior knowledge point by point during delivery and picks a teaching move contingent on what it sees. The Engagement moves section pre-stages an inventory of moves at named load-bearing moments along the Mechanism spine (or the Trace, for scenarios). Each moment offers two or more moves so the tutor has a real choice. The tutor varies move type across moments to avoid making the session feel monotonous — three prediction prompts in a row reads as quizzing even when each prediction is well-pitched. Predictions are reserved for moments where the learner can answer from prior lessons and existing schemas; moments that introduce material the lesson has not yet built up are walked through as worked examples with self-explanation prompts instead.

The tutor consolidates explicitly after any prediction-and-reveal move. The move entry in the Engagement moves section supplies the consolidation prompt and the canonical answer; without these the predict-observe-explain effect collapses.

The tutor refuses restatement as self-explanation and presses for inference. Self-explanation prompts at load-bearing lines are principle-based ("which invariant justifies this step?") rather than open ("explain this"), so the prompt itself elicits inference rather than paraphrase.

The tutor uses the Misconception watchlist during delivery to detect both adjacent-protocol imports (Paxos, Raft, PBFT) and over-generalizations from familiar parts of this codebase. Each entry pre-stages the learner utterance to listen for and the correction to apply, so the tutor recognizes the misconception in line rather than discovering it from scratch.

The tutor declines to fabricate when the KB is silent. Open questions surface every gap so the tutor and the reviewer share a single canonical list rather than the tutor improvising answers and the reviewer never seeing them.

Across the curriculum, scaffolding withdraws as the learner progresses through a cluster. Earlier lessons in a cluster carry richer engagement-move inventories and fuller hint ladders; later lessons in the cluster — and especially the synthesis lesson at A.5 or each cluster's terminal entry — present the same content shape with fewer moves per moment and shorter hint ladders, so the tutor's contingent fading has material at the right altitude. When authoring a later-cluster lesson, supply fewer alternative moves per moment and fewer hint rungs on the completion problems than the cluster's opening lessons.
</coherence_with_tutor_system_prompt>

<authoring_principles>
Lessons are production quality, not stubs. Each section is populated with real content drawn from the KB and code, not placeholder text. The lesson is committed and run by the tutor as-is; subsequent reviewer review fills `[TBD]` markers and tightens phrasing.

Cite the KB by link rather than restating. `concepts/` files own the canonical mental models; `architecture/topics/` files own mechanism prose; `glossary.md` owns term definitions; `invariants.md` owns INV-NNN claims; `delta-map/` files own current-versus-proposed differences; `decisions/` owns ADR rationale. The lesson exists alongside these files and gains nothing by copying them — link instead, and the lesson stays evergreen as the KB updates.

Anchor every mechanism claim. Each sentence in the lesson body describing what a component does, how components are wired, what flows where, or which invariant holds carries a link to the topic file or code anchor that grounds it. Sentences that paraphrase a mechanism without an anchor are the path by which lesson sections drift from the KB and from each other. The consistency pass before writing leans on these anchors — without them, two sections describing the same mechanism in different ways look equally plausible and the pass cannot tell which is right.

Read neighboring topics. The lesson's claims about how the source topic interacts with its neighbors must be consistent with what the neighbor topic files say. The wiring a topic file describes from its own perspective often looks different from the same wiring described by the next topic over; the lesson has to agree with both. The authoring run reads the source topic and every topic it cross-references on the lesson's flow path before drafting.

Code anchors are GitHub URLs against `main`, formatted `https://github.com/<org>/<repo>/blob/main/path#L<start>-L<end>`. Discover the canonical org/repo via `git remote get-url origin` rather than hardcoding. The `last_verified_against` SHA in frontmatter records the verification point; the lesson body links against `main` so the reader sees the current line ranges, and the tutor can flag drift against the recorded SHA.

When the KB or the code does not speak to a question the lesson needs to answer, write `[TBD: short description]` rather than fabricating. Surface every marker at the end under Open questions. The audience trusts the tutor precisely because it does not pretend to know things it does not — this discipline starts at the lesson level.

The learner's prior knowledge is uneven and varies per learner and per point — not predictable from whether the material is conceptual or implementation-specific. Author the engagement-moves inventory so the tutor has a real choice at each load-bearing moment: a worked-example option for when the diagnosis is novelty, and a lighter option (direct walk, prediction-and-reveal, free recall) for when the diagnosis is fluency. Do not assume a fixed posture for the whole lesson.

Use prediction-and-reveal sparingly and only where the prediction is genuinely answerable from prior knowledge. A prompt that requires concepts the lesson has not yet introduced is not productive failure — it is plain failure, and it reads to the learner as a quiz they could not have prepared for. The fidelity conditions matter: the question must sit in the learner's zone of proximal failure, the learner must be able to generate something to contrast against, and consolidation must follow. When any of these is doubtful, choose a different move type for that moment.

Match the shape of the prediction prompt to the shape of the mechanism. When the mechanism is a fan-out graph, the prompt asks for a graph, the canonical answer is a graph, and the alternative-correct-answers field lists the graph shapes a correct learner is likely to give. When the mechanism is a sequential list, the prompt asks for a list. A prompt that asks for the wrong shape will score correct learners as wrong against a mis-shaped canonical. The consistency pass before writing checks for this, but the right time to get it right is at drafting — examine the mechanism in the topic file and choose the prompt shape from what the file shows.

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
- `architecture/topics/<neighbor_topic>.md` for each topic the source topic cross-references on the lesson's flow path — the neighbor's view of the wiring the lesson covers. The consistency pass relies on these.
- `concepts/<concept>.md` — canonical mental models for the lesson's anchor concepts.
- `glossary.md` — term definitions the lesson surfaces.
- `invariants.md` — INV-NNN claims the lesson rests on.
- `delta-map/<source_topic>.md` — current-versus-proposed differences for the delta callout.
- `decisions/ADR-NNN-*.md` — alternatives and consequences when the lesson hinges on a documented decision.
- The source code at the line ranges the topic file's anchors name.

For Pass 1 orientation scenarios, an authoring run reads:

- `architecture/topics/<topic>.md` — one file per component the scenario walks through.
- `architecture/topics/<neighbor_topic>.md` for any neighbor on the scenario's flow path that the scenario's topics cross-reference — the scenario's claims about inter-component wiring need to agree with both sides.
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