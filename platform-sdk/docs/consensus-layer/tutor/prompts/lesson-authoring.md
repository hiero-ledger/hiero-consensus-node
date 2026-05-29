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

Before writing the file, run a consistency pass on the draft. Five checks:

- KB consistency: for every mechanism claim in the draft, verify the claim against the source it cites. A topic file is a paraphrase of the code, not the code itself; the code defines the structural facts the lesson's correctness under perturbation depends on. The check therefore distinguishes two kinds of claim. Behavioral claims — what a component does, what it produces, what its role in the system is — are verified against the topic file. Structural claims — when, how, on what thread, in what order, with what threshold, with what failure path — are verified against the code at the cited anchor (or against a code search if the topic file is silent on the structural detail). Verifying a structural claim against the topic file alone is the failure mode behind the "periodically" bug: both the lesson and the topic file agreed on a paraphrase that did not match the code's actual scheduling mechanism. Seven categories of structural claim require code grounding even when the topic file appears to answer them. **Execution model**: what triggers a thing to run (scheduled task, event callback, method call, tick), with what cadence and timing source, on which thread or executor, queued or inline, serial or parallel, push or pull. **State and lifecycle**: when state is created, when it is persisted, when it is destroyed, what survives restart versus reconnect versus freeze, what is in-memory versus checkpointed. **Ordering and atomicity**: what ordering discipline applies (arrival, topological, consensus, birth-round, custom comparator), what completes as a unit, what holds locks, what can interleave with what. **Error and saturation paths**: what happens on validation failure, exception, timeout, buffer-full, peer misbehavior — drop, log, propagate, quarantine, ban, kill — and how backpressure flows upstream when capacity is exceeded. **Thresholds and parameters**: the exact conditions and quantitative values for "when X is complete," "when enough Y has arrived," sizes, timeouts, retry counts, buffer depths, batch sizes. **Identity and equality**: what "the same event" or "this transaction" means — hash, reference, content, ID — and how deduplication and replay use this. **Versioning**: what specifically changes between versions in migration deltas, grounded in the code diff or the delta-map's structural claims rather than the topic file's prose summary. Pay particular attention to wiring claims between components — what flows where, in what order, with what intermediaries — because these straddle behavioral and structural and are the claims most likely to drift from neighbor topic files. A claim that contradicts the cited source, or a structural claim grounded only in a topic-file paraphrase, is a bug; rewrite the claim to match the code, or move the claim to a `[TBD]` if the code is genuinely ambiguous.
- Internal consistency: scan every section of the lesson for descriptions of the same mechanism — the Mechanism chunks, the Engagement moves entries and their canonical answers, the Consolidation, the Contrasting cases material, perturbation answers in scenarios. If two sections describe the same mechanism, the descriptions must agree. Stop 3 saying event-creator → PCES → fan-out and the Consolidation saying event-creator → intake → PCES → fan-out is the failure mode this check catches. Reconcile against the KB source, not against the draft's other section.
- Shape consistency: for every prediction-and-reveal move in the Engagement moves section, verify that the prompt shape matches the shape of the canonical answer and the shape of the underlying mechanism. A prompt that asks for a list when the mechanism is a fan-out graph, or a prompt that asks for a single answer when the mechanism has multiple correct shapes, produces a learner who answers correctly and gets scored against a mis-shaped canonical. Rewrite the prompt to match the mechanism's shape, or add structurally-correct alternative answers to the move's consolidation (see the lesson template's Engagement moves section for the shape descriptor and alternatives fields).
- Answerability: for every learner-facing prompt in the draft — prediction-and-reveal prompts, completion problems, free-recall and cued-recall checks, the transfer prompt — read the lesson top to bottom and stop at the point where that prompt sits. Ask: could a reader who has seen only the text above this point produce the canonical answer using only terms already defined above it? If the canonical answer needs a mechanism the lesson introduces later, a term not yet defined, or a value or example the learner has not yet worked, the prompt fails the check. Rewrite the prompt so its answer is derivable from text above it, or relocate the prompt below the material it depends on. The transfer prompt gets a sharper version of this check, because it is the prompt most prone to the failure: it points forward in topic by design, which tempts it into asking the learner to predict the design or API of the next subsystem. The check on a transfer prompt is by question type, not by derivability — asking "could this be derived with enough ingenuity" is too weak, because almost anything can be rationalized as derivable. Instead ask: is the prompt asking the learner to apply, predict a consequence of, or locate stress in a mechanism already taught (legitimate), or to invent the design, API, or mechanism of a subsystem the curriculum has not covered (mis-authored)? A prompt whose canonical answer is "the design the next lesson introduces" fails regardless of how it is phrased, including when the design prediction is dressed as "what would be needed." Forward references to the next lesson are allowed only as motivational framing around a question that is itself answerable now. The sole genuine exception across all prompt types is the incoming retrieval probes, which reference prerequisite lessons by design; they may not reference later-in-lesson or later-cluster material either. Whole-lesson scope phrases in prompts — "the rest of the wiring lifecycle," "everything downstream," "later mechanisms" — fail this check on sight; the canonical answer must name specific referents the learner has already seen, not gesture at unscoped material.

- Verbatim-readiness: every learner-facing prompt in the draft is delivered to the learner word for word by a tutor that will not paraphrase, improve, or repair it. Read each prompt as the learner will hear it. A prompt written as an instruction to the tutor — "ask the learner to predict the fan-out," "have them explain why the check exists" — fails the check, because the tutor delivers text, not intent; rewrite it as the exact second-person words the learner reads. Confirm each answer-eliciting move carries a `canonical_answer`, an `alternative_correct_answers` list, and, where the answer is reachable by restatement or shaky reasoning, an authored `followup`. Confirm exposition and prompts are not blurred: learning objectives, threshold-concept statements, load-bearing-line notes, and misconception entries are declarative and never phrased as questions, because the system prompt forbids the tutor from converting them into quizzes and a question-shaped objective invites exactly that. Confirm the Incoming retrieval probes section carries no learner-facing free-recall prompt — it is an authorial signal, and a scripted opening quiz there contradicts the trust-based entry. A prompt that is not verbatim-ready is as much a defect as a factually wrong one, because there is no delivery-time repair behind this gate.

If any check finds an inconsistency Claude Code cannot reconcile against the KB without guessing, mark the relevant claim `[TBD]` and surface it in Open questions rather than committing the contradiction. The consistency pass is not optional even on tight runs — the bugs it catches are the ones the tutor will deliver to the learner as confident-but-wrong or undeliverable content, which is worse than a deferred lesson.

Write the file at `tutor/lessons/<lesson_id>.md`. Update the manifest entry's `status` to `drafted`. Stop. Report the lesson_id written, its curriculum index, the count of entries still without files, and a one-line summary of what the consistency pass flagged (including resolutions and any new `[TBD]` markers added during the pass). Do not move on to the next lesson — one invocation produces exactly one lesson.
</phase_two_author>

<lesson_template>

<!--
Template justification — section list traced to the pedagogy research and the tutor system prompt:

  - Prerequisites: tutor system prompt's entry behavior surfaces this list for
    trust-based self-assessment. Names the prereq IDs with one-line mental
    models so the learner can judge their own readiness.

  - Incoming retrieval probes: successive-relearning queue (Rawson & Dunlosky
    2022). An authorial signal, not an opening quiz script — the system
    prompt is explicit that running these as session-open retrieval quizzes
    contradicts the trust-based entry. The section tells the tutor which
    prior-lesson concepts to watch for at the entry self-assessment and to
    consolidate when they resurface; active retrieval, when wanted, is a
    free-recall engagement move at the point of resurfacing, where the
    research's post-teaching consolidation gain actually applies.

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

  - Engagement moves: a labelled inventory of move types at named moments.
    The tutor selects exactly one move per moment by diagnosis tag and
    delivers its authored prompt verbatim — it does not generate, paraphrase,
    or repair questions (system prompt). Five types: prediction-and-reveal
    (Kapur 2008/2014; Loibl et al. 2017; hypercorrection effect), worked
    example with self-explanation (Sweller & Cooper 1985; Renkl 2014; Bisra
    et al. 2018 g = 0.55), direct walk with cued check (Kalyuga
    expertise-reversal; Adesope et al. testing-effect meta-analysis),
    contrasting cases (Gick & Holyoak 1983; Gentner structure-mapping), and
    free recall (Roediger & Karpicke testing effect). Each answer-eliciting
    move supplies a verbatim prompt, a canonical answer, alternative correct
    answers, and an authored follow-up where restatement or shaky reasoning
    is the expected failure, because the tutor will not compose any of these
    at delivery time. The tutor varies move type across moments so the
    session does not become monotonous.

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
    the next cluster while consolidating the present one. Asks the learner to
    apply, predict a consequence of, or locate stress in a mechanism already
    taught — never to predict the design of an uncovered subsystem; forward
    references are motivational framing only, not the question.

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
kb_topics_touched:                 # paths under platform-sdk/docs/consensus-layer/; the tutor reads these at entry as a cross-check against the lesson
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

List each prerequisite lesson by ID and give a one-line description of the mental model that lesson establishes — enough for the learner to judge whether they feel solid on it. The one-line description does double duty: it is what the learner reads at the trust-based self-assessment, and it is what the tutor consolidates against if the learner hedges on that specific prerequisite and the tutor runs the bounded recall probe the system prompt allows. Write it as a declarative statement of the mental model, not as a question — the tutor poses the recall in its own framing and checks the learner's articulation against this statement. If this lesson has no prerequisites, state so explicitly:

> None — this lesson assumes only general distributed-systems background.

Do not omit this section even when prerequisites are empty; the tutor's entry behavior is uniform across lessons.

## Incoming retrieval probes

This section is an authorial signal to the tutor, not a script of opening quiz prompts. The system prompt is explicit that the tutor does not run these as session-open retrieval quizzes — doing so contradicts the trust-based entry — and that their operational use is to tell the tutor which concepts from prior lessons to be especially watchful for during the entry self-assessment and which to consolidate explicitly when they resurface during delivery. The strongest retrieval gains come from consolidation after new material is taught, which the free-recall engagement move already supplies.

So each entry here lists the concept from a prior lesson the current lesson builds on, the prior lesson ID it came from, and a one-line canonical statement of what the learner should be able to say about it — the thing the tutor consolidates against when the concept comes up. Do not author a learner-facing free-recall prompt in this section; if the lesson wants the concept actively retrieved, that belongs in a free-recall engagement move at the point in the spine where the concept resurfaces, not at session open. Threshold concepts from earlier lessons whose successive-relearning interval falls in this lesson's session belong here as watch-for signals. If the lesson sits early enough in the curriculum that no probes apply, state so explicitly.

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

An inventory of teaching moves at named moments along the spine. The tutor selects exactly one move per moment by matching the move's diagnosis tag to what the learner has shown, and delivers that move's authored prompt verbatim. The tutor does not blend two moves, run a moment's moves in sequence, or reword the selected one — so each move must stand alone as a complete, deliverable unit, and the prompt text in it must be the exact words the learner reads.

A lesson typically has two to four moments with engagement-move inventories — the load-bearing transitions, the threshold-concept introduction, the points where misconceptions are most likely. Not every chunk in the Mechanism section warrants a moment, and a moment is allowed to resolve to no question: if a chunk is best delivered as exposition, do not manufacture a moment for it. The system prompt is explicit that asking nothing is correct when no authored prompt fits; the lesson should not pad the spine with prompts to fill silence.

Each moment in this section names:

- The `moment_id` matching a chunk in the Mechanism section.
- A one-sentence description of why this moment is load-bearing (exposition — the tutor uses this to choose, it is not read to the learner).
- An inventory of labelled moves — `A`, `B`, `C` — each carrying its diagnosis tag and its complete authored content. Supply at least two moves per moment so the tutor has a real choice; the contrasting-cases move counts toward this only at threshold-concept moments.

Every move that elicits an answer from the learner carries, in addition to its prompt: a `canonical_answer`, an `alternative_correct_answers` list of two or three answers that are also correct and must be credited rather than called wrong, and — where the canonical answer can be reached with shaky reasoning or satisfied by restating the mechanism — an authored `followup` question delivered verbatim when the tutor judges the learner restated rather than explained or reached the outcome without the reasoning. The tutor scores only against the canonical and the alternatives and will not invent a follow-up; both fields are load-bearing on every answer-eliciting move, not just prediction-and-reveal.

The available move types:

**Prediction-and-reveal.** Supply only when the question can be answered from prior lessons and existing distributed-systems schemas. Include the framing scenario, the prediction prompt phrased as low-stakes thinking-aloud ("what's your gut prediction?", "before I show what happens, what does your model say?") and written as the exact words the learner reads, an optional confidence elicitation when high-confidence wrong beliefs are likely, the `canonical_answer` with code anchor, the `alternative_correct_answers`, and the authored `followup` for a correct-outcome-but-shaky-reasoning answer ("you got the behavior — which invariant forced it?"). Diagnosis tag: the learner is showing strong grasp of the surrounding material or is likely to hold a confidently-wrong adjacent-protocol intuition. Do not supply this move type if the prediction would require concepts the lesson has not yet established — that is not productive failure, just frustration. Also supply an `answer_shape` descriptor naming the shape the canonical answer takes — "sequential list of stages," "fan-out graph from component X," "state transition with K branches," "single invariant statement," or whatever fits. The shape of the prompt must match the shape of the mechanism: a prompt that asks for a list when the mechanism is a fan-out graph scores a correct learner as wrong. The `alternative_correct_answers` carry the shape work too — a learner who says "PCES fans out to gossip and hashgraph in parallel" gives a correct graph-shaped answer to a graph-shaped question and is credited, not scored against missing-from-the-list.

**Worked example with self-explanation.** Supply when the learner is likely new to this point on this codebase. Include the example, the load-bearing lines marked explicitly (exposition — what the tutor signals, not a question), and at each load-bearing line a principle-based self-explanation prompt written verbatim as the learner reads it — "which invariant justifies this step?", "what failure scenario does this rule prevent?", "what breaks if we remove this check?" Avoid "explain this," which produces restatement. Each self-explanation prompt carries its `canonical_answer`, `alternative_correct_answers`, and an authored `followup` that pushes a restatement toward inference ("that is what the code does — I am asking why; what does the system lose if this line is gone?"), since restatement is the expected failure here and the tutor will not compose the push itself. Diagnosis tag: the learner is asking for an example, hesitating on terms specific to this code path, or showing they have not encountered this subsystem before.

**Direct walk with cued check.** Supply when the chunk is content the learner is likely fluent on but the tutor needs to verify before moving on. State the mechanism briefly (exposition), then a short cued-recall or application check written verbatim — "given this snippet, what is the precondition?" or "in this trace, which line would violate the invariant?" — with its `canonical_answer` and `alternative_correct_answers`. Diagnosis tag: the learner has shown fluency on the surrounding material; a fuller scaffolding would be redundant.

**Contrasting cases with comparison prompt.** Supply when the moment introduces a threshold concept and the lesson includes contrasting-cases material in the section below. Reference the cases by name and supply the comparison prompt verbatim ("what is the same across these, what is different, what is the invariant that survives?") with its `canonical_answer` and `alternative_correct_answers`. The deep invariant the cases surface is exposition for the consolidation, not part of the prompt. Diagnosis tag: threshold concept; transfer is the goal. The system prompt notes the cases do no pedagogical work unless the comparison prompt is actually put to the learner, so the prompt is mandatory in this move, not optional.

**Free recall.** Supply when the moment lands on something the lesson has already covered and the tutor needs the learner to retrieve it in their own words. Include the prompt verbatim, the `canonical_answer` for consolidation, and `alternative_correct_answers`. Diagnosis tag: the learner signaled uncertainty on this concept, a mid-session check on something just covered, or a retrieval cycle before the next chunk.

Each moment should usually offer the worked-example move and at least one other, so the tutor has a fallback when the diagnosis points toward novelty. The contrasting-cases move is available only at moments tied to threshold concepts.

Every prompt supplied in this section, and every canonical answer, must be answerable from lesson text that appears above the moment it is attached to. A prompt whose canonical answer needs a mechanism from a later chunk, a later cluster, or a term not yet defined is mis-placed: either move the moment lower in the Mechanism spine or rewrite the prompt to depend only on material already covered. The answerability check in the consistency pass enforces this; author the moments so they pass it, because the tutor will not repair an unanswerable prompt at delivery — it will deliver it as written.

## Contrasting cases material

Include this section only when the lesson covers a threshold concept; omit otherwise. The Engagement moves section references this material when it lists the contrasting-cases move at a moment.

Provide two or three contrasting cases — e.g. this codebase's choice versus a textbook approach, or hashgraph's mechanism versus the analogous mechanism in a familiar BFT variant. For each case, give a short concrete description and the surface differences. Then name the deep invariant that survives the surface differences. This is the structural transfer the contrasting-cases technique exists to produce; without an explicit invariant, the cases sit side by side without doing the pedagogical work.

## Completion problems

Progressive problems that fade scaffolding step by step. The first problem leaves a small step blank in an otherwise complete worked example; later problems leave more blank, until the learner is producing the full step from a posed scenario. The problem statement and every hint-ladder rung are learner-facing text the tutor delivers verbatim — write them as the exact words, not as a description of what to ask. Each problem ships with:

- The problem statement, written verbatim as the learner reads it.
- The hint ladder as authored rungs the tutor delivers one at a time, escalating only on continued effort: rung 1 names where in the codebase or KB to look (verbatim, with the file and what to look for stated explicitly, since the system prompt requires the tutor to name the file rather than gesture at it); rung 2 is a focused narrowing question (verbatim); rung 3 is a partial worked example; rung 4 is the full answer, gated on demonstrated effort.
- The `canonical_answer`, the `alternative_correct_answers`, the invariant or mechanism the problem exercises (exposition), and an authored `followup` where the answer is reachable by restatement.

## Delta callout

Brief callout pointing to `delta-map/<topic>.md` for the difference between current code and the proposed redesign on the material this lesson covers. Summarize the delta's status (`done`, `partial`, `not started`, `divergent`) in one line and link the file. Do not restate the delta in detail — the delta-map is the canonical source and the lesson exists alongside it. This section is exposition; it carries no learner-facing prompt.

## Transfer prompt

A transfer prompt asks the learner to do one of three things with material the lesson has already taught: apply the invariant or mechanism just learned to a new situation, predict a behavior or consequence under a new condition, or identify where the current invariant would be stressed. The answer is reasoned out from what is already on the page plus general distributed-systems knowledge. The prompt is open-ended enough that the answer requires applying the lesson's mechanism rather than retrieving it. It is a close-of-session move the tutor delivers verbatim; write it as the exact words, and supply its `canonical_answer` and `alternative_correct_answers` so the tutor scores against them rather than improvising.

A transfer prompt does not ask the learner to predict the design, API, or mechanism of a subsystem the curriculum has not yet covered. "Given two components with their own schedulers, what would the framework need to provide to let data flow between them — name two operations and a validity constraint" is mis-authored, because the canonical answer is the wiring API the next lesson introduces, and there is no chain of reasoning from a scheduler-and-queue lesson to that API. Phrasing the design prediction as "what would be needed" does not rescue it — the failure is the question type, not the wording. The test is not whether the answer could be derived with enough ingenuity; it is whether the prompt asks the learner to reason about something they have been taught or to invent something they have not seen. If the canonical answer is "the design the next lesson introduces," the prompt is mis-authored regardless of phrasing.

Forward-pointing is allowed, but only as motivational framing, never as the question. A transfer prompt may name the next lesson and say the current material is what it builds on — "the next lesson covers the wire layer; here is a question about what you just learned that it will build on" — but the question itself is answerable now, from material already on the page. The legitimate shape for the scheduler example: "You have seen that a scheduler owns its queue and applies a thread-execution policy. Suppose two schedulers with different policies hand work to each other. Using only how a scheduler consumes its own queue, what ordering or backpressure hazard would you expect at that boundary?" That applies the lesson's own mechanism, motivates the wiring lesson, and does not require inventing the wiring API.

This primes the next cluster while consolidating the present one.

## Close-out retrieval

Close-of-session content. The system prompt requires the tutor to run the close-of-session moves and then signal the end of delivery plainly, so this section supplies what it runs.

- Free-recall summary: the prompt asking the learner to articulate the load-bearing invariant or mechanism in their own words, written verbatim, with its `canonical_answer` and `alternative_correct_answers` for the tutor to consolidate against.
- Successive-relearning tags: exposition, not a prompt. For each threshold concept this lesson establishes, name when it should be probed in subsequent sessions — roughly day 1, day 3, and two weeks. The tutor adds these to the learner's relearning queue. If the lesson establishes no threshold concept, state so explicitly.

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

  - Engagement moves: a labelled inventory at named moments along the trace,
    mirroring the lesson template. The tutor selects exactly one move per
    moment by diagnosis tag and delivers its authored prompt verbatim; each
    answer-eliciting move supplies a verbatim prompt, canonical answer,
    alternative correct answers, and an authored follow-up where needed,
    because the tutor composes none of these at delivery. Five types:
    prediction-and-reveal, worked example with self-explanation, direct walk
    with cued check, contrasting cases, and free recall. The tutor varies
    move type across moments so the trace does not become a sequence of
    similar predictions. Orientation scenarios have fewer moments (often only
    one or two, at the start) and lighter moves (typically role-level
    prediction-and-reveal or direct walks); full and edge scenarios populate
    more moments with richer moves.

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

For full and edge scenarios: this is an authorial signal, not a script of opening quiz prompts, exactly as in the lesson template. List the concepts from prior Pass 2 or Pass 3 entries the scenario builds on, the entry each came from, and a one-line canonical statement of what the learner should be able to say about it for the tutor to consolidate against when it resurfaces. The tutor does not run these as session-open quizzes; if a concept needs active retrieval, that belongs in a free-recall engagement move at the trace stop where the concept resurfaces. Cross-cluster scenarios depend on these mechanisms being available — flag them here as watch-for signals so the tutor consolidates them in line rather than discovering the gap mid-trace.

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

An inventory of teaching moves at named moments along the trace. The tutor selects exactly one move per moment by matching its diagnosis tag to what the learner has shown, and delivers that move's authored prompt verbatim — it does not blend, sequence, or reword. The five move types mirror the lesson template, and the same verbatim-delivery rules apply: every prompt is the exact words the learner reads, not an instruction to the tutor; every answer-eliciting move carries a `canonical_answer`, an `alternative_correct_answers` list, and an authored `followup` where the answer can be reached with shaky reasoning or by restatement.

Each moment in this section names:

- The `moment_id` matching a stop in the Trace section (or a perturbation in the section below, if the perturbation itself is the moment).
- A one-sentence description of why this moment is load-bearing (exposition; not read to the learner).
- An inventory of labelled moves — `A`, `B`, `C` — each carrying its diagnosis tag and its complete authored content. Supply at least two moves per moment where possible. A moment may also resolve to no question; do not manufacture a prompt where exposition is the right delivery.

The available move types:

**Prediction-and-reveal.** Supply only when the question can be answered from prior lessons and existing schemas — for full and edge scenarios, that includes everything the prerequisite Pass 2 lessons established. Include the framing scenario, the prediction prompt written verbatim as low-stakes thinking-aloud, optional confidence elicitation, the `canonical_answer` with code anchor when applicable, the `alternative_correct_answers`, and the authored `followup` for a correct-outcome-but-shaky-reasoning answer. For orientation scenarios this move is light and role-level ("which components do you think get involved here, and in what order?"). For full and edge scenarios it is rigorous and mechanism-level. Do not supply this move type at a moment where the prediction would require a mechanism the trace has not yet reached. Also carry an `answer_shape` descriptor. A scenario's trace is often graph-shaped — fan-outs, parallel branches, cross-cluster stitches — and a prompt that asks for a sequential list when the mechanism is a graph scores a correct learner as wrong; the shape descriptor and the alternatives let the tutor credit a correct differently-shaped answer rather than score it against the canonical's shape.

**Worked example with self-explanation.** Supply when the learner is likely new to the subsystem the current stop sits in. Include the example and, at each load-bearing line, a principle-based self-explanation prompt written verbatim — "which invariant survives this transition?", "what would break if this component skipped this step?" — each with its `canonical_answer`, `alternative_correct_answers`, and an authored `followup` pushing restatement toward inference. Diagnosis tag: the learner is hesitating on the current component or asking for clarification on how it behaves.

**Direct walk with cued check.** Supply when the stop is content the learner is likely fluent on but the tutor needs to verify before moving on. State what happens (exposition), then a short cued-recall or application check written verbatim, with its `canonical_answer` and `alternative_correct_answers`. Diagnosis tag: the learner has shown fluency on the surrounding components.

**Contrasting cases with comparison prompt.** Supply only at moments that introduce or revisit a threshold concept, and only if contrasting-cases material is available in the prerequisite lessons or in this scenario. Reference the cases and supply the comparison prompt verbatim with its `canonical_answer` and `alternative_correct_answers`; the deep invariant is exposition for the consolidation, not part of the prompt. The comparison prompt is mandatory in this move — the cases do no work unless it is put to the learner.

**Free recall.** Supply at moments that land on something already covered earlier in the trace or in a prerequisite lesson, when the tutor needs the learner to articulate it before moving on. Include the prompt verbatim, the `canonical_answer`, and `alternative_correct_answers`.

Each load-bearing moment should usually offer the worked-example move and at least one other, so the tutor has a fallback when the diagnosis points toward novelty.

Every prompt supplied in this section, and every canonical answer, must be answerable from trace text above the stop it is attached to. A scenario prompt whose canonical answer needs a stop further along the trace, a later-cluster mechanism the trace has not yet reached, or a term the scenario has not yet introduced is mis-placed: move the moment to a later stop or rewrite the prompt to depend only on what the learner has walked through so far. The incoming retrieval probes are an authorial signal, not a forward reference; nothing in the scenario may reference forward material. The answerability check in the consistency pass enforces this, because the tutor will deliver the prompt as written rather than repair it.

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

The tutor delivers authored prompts verbatim and writes none of its own. The system prompt is explicit that the tutor will not generate, paraphrase, reformulate, or repair a question at delivery time — question quality is the lesson author's responsibility, enforced here at authoring time, and the tutor's job is selection and faithful delivery. This changes what a prompt in the lesson is. It is not an instruction to the tutor about what to ask ("ask the learner to predict the fan-out"); it is the exact words the learner will read ("Before I show the wiring: when this self-event leaves the creator, where do you expect it to go first?"). Write every learner-facing prompt — every probe, prediction, comprehension check, completion problem, self-explanation prompt, contrasting-cases comparison prompt, follow-up, and close-out question — as final verbatim text in the learner's second person, not as a description of the question's intent. A prompt written as an instruction to the tutor is a defect, because the tutor will not convert it; it will either deliver the instruction text to the learner as-is or have nothing to deliver. The authoring-time consistency pass is the only quality gate this content passes through; there is no delivery-time repair behind it.

Mark exposition distinct from authored prompts. Learning objectives, threshold-concept statements, load-bearing-line notes, misconception entries, the canonical answers, and the consolidation's substance are exposition — material the tutor states or uses, never converts into a question. The system prompt forbids the tutor from turning a load-bearing note into "predict what this line does." A lesson that blurs the line — phrasing a learning objective as a question, or writing a threshold-concept statement that reads like a prompt — invites exactly the conversion the system prompt prohibits. Keep exposition in declarative form and keep authored prompts in interrogative form, so the tutor's selection logic can tell them apart on sight.

Consolidation exposition is the tutor's to phrase; the lesson supplies the canonical answer and any follow-up questions. After a prediction-and-reveal the tutor names the gap between the learner's prediction and the canonical mechanism in its own words — the lesson does not script that prose. But any follow-up question the consolidation calls for is delivered verbatim from the lesson, not invented: the press on a correct-outcome-but-shaky-reasoning answer ("you got the behavior — which invariant forced it?") and the push on a restatement toward inference ("that is what the code does — I am asking why; what does the system lose if this line is gone?") are authored in the move, because the tutor will not compose them. Supply these follow-ups on every move whose canonical answer a learner can reach with shaky reasoning or satisfy by restating the mechanism.

Cite the KB by link rather than restating. `concepts/` files own the canonical mental models; `architecture/topics/` files own mechanism prose; `glossary.md` owns term definitions; `invariants.md` owns INV-NNN claims; `delta-map/` files own current-versus-proposed differences; `decisions/` owns ADR rationale. The lesson exists alongside these files and gains nothing by copying them — link instead, and the lesson stays evergreen as the KB updates.

Anchor every mechanism claim. Each sentence in the lesson body describing what a component does, how components are wired, what flows where, or which invariant holds carries a link to the topic file or code anchor that grounds it. Sentences that paraphrase a mechanism without an anchor are the path by which lesson sections drift from the KB and from each other. The consistency pass before writing leans on these anchors — without them, two sections describing the same mechanism in different ways look equally plausible and the pass cannot tell which is right.

Read neighboring topics. The lesson's claims about how the source topic interacts with its neighbors must be consistent with what the neighbor topic files say. The wiring a topic file describes from its own perspective often looks different from the same wiring described by the next topic over; the lesson has to agree with both. The authoring run reads the source topic and every topic it cross-references on the lesson's flow path before drafting.

Ground structural claims in code. Topic files are paraphrases of the code, and for structural claims — when something runs, how it is scheduled, what triggers it, what thread it lives on, what ordering it follows, where its atomicity boundaries are, what its failure paths look like, what its thresholds and parameters actually are, how identity is determined, what survives which lifecycle transition — the topic file is a starting point and the code is the source of truth. Verifying a structural claim against the topic file alone is the failure mode behind the "periodically" bug: the topic file said "periodically," the lesson said "periodically," and neither described the actual scheduling mechanism in the code. When the topic file is ambiguous, silent, or imprecise on a structural detail, follow the cited code anchor and read what the code actually does. A watch list of phrases that signal a paraphrased structural claim and warrant code grounding: "periodically," "regularly," "on demand," "as needed," "when ready," "asynchronously," "in the background," "after a delay," "in order," "atomically," "eventually," "a small buffer," "a large queue," "when enough X has arrived," "once the condition holds." Each of these paraphrases a code-level mechanism; the lesson either replaces the phrase with a precise one ("scheduled at 100ms intervals via X," "on every gossip-in event," "when N witnesses are received") or grounds the phrase with a code anchor showing the actual mechanism. The categories the consistency pass uses — execution model, state and lifecycle, ordering and atomicity, error and saturation paths, thresholds and parameters, identity and equality, versioning — are the categories where this discipline matters most.

Code anchors are GitHub URLs against `main`, formatted `https://github.com/<org>/<repo>/blob/main/path#L<start>-L<end>`. Discover the canonical org/repo via `git remote get-url origin` rather than hardcoding. The `last_verified_against` SHA in frontmatter records the verification point; the lesson body links against `main` so the reader sees the current line ranges, and the tutor can flag drift against the recorded SHA.

When the KB or the code does not speak to a question the lesson needs to answer, write `[TBD: short description]` rather than fabricating. Surface every marker at the end under Open questions. The audience trusts the tutor precisely because it does not pretend to know things it does not — this discipline starts at the lesson level.

The learner's prior knowledge is uneven and varies per learner and per point — not predictable from whether the material is conceptual or implementation-specific. Author the engagement-moves inventory so the tutor has a real choice at each load-bearing moment: a worked-example option for when the diagnosis is novelty, and a lighter option (direct walk, prediction-and-reveal, free recall) for when the diagnosis is fluency. Do not assume a fixed posture for the whole lesson.

Use prediction-and-reveal sparingly and only where the prediction is genuinely answerable from prior knowledge. A prompt that requires concepts the lesson has not yet introduced is not productive failure — it is plain failure, and it reads to the learner as a quiz they could not have prepared for. The fidelity conditions matter: the question must sit in the learner's zone of proximal failure, the learner must be able to generate something to contrast against, and consolidation must follow. When any of these is doubtful, choose a different move type for that moment.

Match the shape of the prediction prompt to the shape of the mechanism. When the mechanism is a fan-out graph, the prompt asks for a graph, the canonical answer is a graph, and the alternative-correct-answers field lists the graph shapes a correct learner is likely to give. When the mechanism is a sequential list, the prompt asks for a list. A prompt that asks for the wrong shape will score correct learners as wrong against a mis-shaped canonical. The consistency pass before writing checks for this, but the right time to get it right is at drafting — examine the mechanism in the topic file and choose the prompt shape from what the file shows.

Every learner-facing prompt is answerable from text above it. A prompt's canonical answer must be derivable strictly from lesson text appearing before that prompt — no forward mechanisms, no later-cluster references, no term not already defined above it. The drafting self-check, applied to each prompt as it is written: could a reader who has seen only the text above this point produce the canonical answer using only terms already defined there? If not, rewrite the prompt or relocate it below the material it depends on. Anchor every prompt to something already on the page — a named code path, a term defined above, or a value or example the learner has already worked. Ban whole-lesson scope phrases from prompts and their canonical answers: "the rest of the wiring lifecycle," "everything downstream," "later mechanisms" each name unscoped material the learner has not seen; replace each with the specific referents, named explicitly. Two clarifications. The transfer prompt is the prompt most prone to violating this, because it points forward in topic by design and is easily tempted into asking the learner to predict the design or API of the next subsystem — a question with no answer the learner could reason to. A transfer prompt asks the learner to apply, predict a consequence of, or locate stress in a mechanism already taught; it never asks them to invent a subsystem the curriculum has not covered, and dressing a design prediction as "what would be needed" does not make it legitimate. Forward references to the next lesson are allowed as motivational framing only, never as the question; the question itself is answerable now. The incoming retrieval probes are the sole genuine exception: they reference prerequisite lessons by design, but never later-in-lesson or later-cluster material.

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
