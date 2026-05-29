# Evidence-Based Pedagogy for the Consensus Layer Tutor

## Executive summary

Designing a chat-based AI tutor for senior consensus engineers should be governed by a single tension that the learning-sciences literature resolves clearly: the learner is **expert in distributed-systems concepts but novice in this specific codebase**, and the two halves require different instructional postures. For concept-level material (BFT, asynchrony, gossip, quorum reasoning) the evidence favors **problem-first, contrast-case, and productive-failure designs** because the expertise-reversal effect (Kalyuga et al. 2003; Kalyuga 2007) predicts that further worked examples will be redundant or harmful. For codebase-specific material (this repo's event format, signed-state serialization, scheduler invariants) the same learner is effectively a novice, and **worked examples paired with self-explanation prompts and backward fading** (Sweller & Cooper 1985; Renkl 2014; Atkinson, Renkl & Merrill 2003) remain the most efficient route to durable schemas.

Across both halves, four levers carry the bulk of the empirical weight and should be treated as the tutor's backbone: **retrieval practice** (Roediger & Karpicke 2006; Karpicke & Blunt 2011), **spacing and successive relearning** (Cepeda et al. 2006; Rawson & Dunlosky 2022), **self-explanation prompting** (Chi et al. 1989, 1994; Bisra et al. 2018 meta-analysis g = 0.55), and **comparison across multiple varied examples** (Gick & Holyoak 1983; Gentner, Loewenstein & Thompson 2003). These are the techniques rated "high utility" by Dunlosky et al. 2013 and replicated across domains, ages, and outcomes.

For the chat channel itself, two recent randomized studies bound expectations sharply. Kestin et al. (2025, *Scientific Reports*) showed a pedagogically-prompted GPT-4 tutor produced roughly twice the short-term learning gain of in-class active learning, in less time. Bastani et al. (2025, *PNAS*) showed an unprompted ChatGPT *harmed* exam performance by 17%, while the same model with a Socratic prompt did not. The decisive variable was **tutor posture**, not model capability. VanLehn's 2011 review further establishes that one-on-one tutoring's real effect is d ≈ 0.8 (not Bloom's 2σ), and that the mechanism is **step-level feedback plus interactive co-construction**, not eloquent explanation. Chi, Roy & Hausmann (2008) showed tutees learn just as much when tutors are *prevented* from explaining, as long as the learner is scaffolded into constructing.

The dominant failure modes are therefore predictable: **sycophantic agreement with confident-but-wrong senior engineers** (arXiv 2506.10297 shows ±15 pp accuracy swings from learner-injected hints), **over-scaffolding experts** (expertise reversal), **answer-seeking as a crutch** (Bastani et al.), the **illusion of explanatory depth** (Rozenblit & Keil 2002) amplified by fluent LLM prose (Elsayed & Verheyen 2024), and **expert-blind-spot** explanations that follow the system's architecture rather than the learner's current mental model (Nathan & Petrosino 2003).

The Recommendations section translates this into a prioritized, implementable specification for the tutor system prompt and lesson templates.

---

## Foundations

The pedagogical frameworks most often invoked for adult technical learning are not equal in evidential weight, and the tutor design should treat them accordingly.

**Cognitive Load Theory (CLT)** is the empirically strongest single framework and the right diagnostic lens for sequencing. Sweller (1988), Sweller, van Merriënboer & Paas (1998), and Sweller (2010) distinguish intrinsic load (set by the **element interactivity** of the material relative to the learner's prior schemas), extraneous load (presentation-driven), and germane load (now redefined as working-memory resources actually devoted to schema construction). Distributed-systems code is canonically high-element-interactivity: state variables, message types, timers, invariants, and failure modes co-constrain each other. CLT's prescriptions — manage intrinsic load by chunking and pre-training, eliminate extraneous load by signaling and coherence, calibrate guidance to prior knowledge — have hundreds of replicated experiments behind them (Sweller, Ayres & Kalyuga 2011). The measurement of load is contested; the decomposition is not.

**The 4C/ID model** (van Merriënboer 1997; van Merriënboer & Kirschner 2017) extends CLT into a curricular blueprint for complex skill acquisition. It prescribes whole, authentic tasks organized into task classes of increasing complexity; supportive information presented before/during each class; just-in-time procedural information delivered exactly when needed; and part-task practice only for sub-skills requiring automation. For a fifty-lesson consensus curriculum, 4C/ID is the natural macro-architecture: each "cluster" is a task class, and each lesson is a whole-task scenario rather than an isolated drill.

**Cognitive apprenticeship** (Collins, Brown & Newman 1989) — modeling, coaching, scaffolding, articulation, reflection, exploration — is a design heuristic rather than a tested intervention, but its method vocabulary maps cleanly onto a chat tutor's moves and overlaps substantially with CLT-supported techniques. Treat it as method language, not evidence base.

**The ICAP framework** (Chi & Wylie 2014) predicts Interactive > Constructive > Active > Passive engagement and supplies the cleanest rationale for forcing learner output rather than letting the engineer skim explanations. The strict ordinal hierarchy has been challenged (Lipowsky et al. 2023), but the Constructive > Active boundary — the gain from generating versus merely manipulating — is robust and aligns with the generation effect (Bertsch et al. 2007, d ≈ 0.40) and the self-explanation literature.

**Threshold concepts** (Meyer & Land 2005) is phenomenological, not experimentally validated, but it is the right curricular lens for identifying the small set of integrative, transformative, troublesome concepts that gate everything downstream. For this codebase, candidate thresholds include logical time without a global clock, the safety/liveness duality, quorum intersection, virtual voting versus explicit voting, and signed-state determinism. These deserve disproportionate scaffolding investment.

**Andragogy** (Knowles 1980) has weak empirical support (Merriam 2001) and is best treated as a design *stance* — respect prior experience, justify why, give the learner control — rather than learning science.

**Deliberate practice** (Ericsson et al. 1993) survives meta-analysis only partially. Macnamara, Hambrick & Oswald (2014) found it explains 26% of variance in games, 18% in sports, only 4% in education and <1% in professions; Macnamara & Maitra (2019) failed to fully replicate the 1993 study. The robust legacy is not "10,000 hours" but the design elements: **immediate informative feedback, tasks just beyond current ability, repetition with a coach designing the practice**. These the tutor can implement directly.

These frameworks converge on four design principles: make expert reasoning visible, start with high guidance and fade, keep practice within working-memory limits, and provide immediate step-level feedback. They diverge on how much disequilibrium to introduce: ICAP and threshold concepts welcome productive struggle; CLT's classical novice prescription minimizes it. The expertise-reversal effect (below) resolves the apparent conflict: the *right level of struggle depends on the learner's prior knowledge in the specific sub-domain*. This is the central design parameter for a senior-learner tutor.

---

## Techniques

### Worked examples, completion problems, and backward fading

The worked-example effect — studying a worked solution produces equal or better transfer than solving the equivalent problem, at lower cognitive load — has been replicated continuously since Sweller & Cooper (1985). Meta-analytic effect sizes cluster around d ≈ 0.5–0.7 (Crissman 2006; Hattie 2009; Barbieri et al. 2023 for mathematics). The mechanism is that means-ends search during problem solving consumes working memory on goal management rather than schema acquisition; the worked example frees that capacity. **Completion problems** (van Merriënboer 1990) and **backward fading** (Renkl & Atkinson 2003; Atkinson, Renkl & Merrill 2003) — progressively blanking out steps from the last backward — bridge worked examples and full problem solving and outperform pure example-problem pairs on near transfer; combined with self-explanation prompts, they also produce far-transfer gains.

For this tutor, the implication is operational. When walking the learner through, say, the `appendEntry`-equivalent path in the consensus codebase, the first encounter should be a fully worked example with annotated rationale. The second should be a completion problem in which one or two load-bearing lines are elided and the learner must produce them. The third should leave only the invariant and ask the learner to trace the path. Salden et al. (2009) show that adaptive fading driven by learner performance outperforms fixed fading. The tutor should therefore measure success at each step and decide whether to fade or hold.

### The expertise reversal effect

Kalyuga, Ayres, Chandler & Sweller (2003) and Kalyuga (2007) document an extensively replicated *disordinal* interaction: instructional supports that help novices — heavy worked examples, integrated diagrams with redundant text, step-by-step guidance — become neutral or harmful for advanced learners because they force cross-referencing with already-automated schemas, generating extraneous load. Kalyuga et al. (2001) is the canonical "when problem solving is superior to studying worked examples" paper. For senior engineers reading concept-level material, this is the single most important constraint on tutor behavior: **do not front-load worked examples on consensus fundamentals they already understand**. Diagnose first. Kalyuga & Sweller (2004) describe rapid diagnostic methods — a one- or two-item probe of prior knowledge — that suffice to choose the starting fading level.

### Self-explanation and elaborative interrogation

Chi, Bassok, Lewis, Reimann & Glaser (1989) and Chi, de Leeuw, Chiu & LaVancher (1994) are the foundational demonstrations that learners who self-explain worked examples learn vastly more than those who do not, and that the effect can be induced by prompting. The Bisra et al. (2018) meta-analysis of 69 effect sizes reports g = 0.55 — comparable to mastery learning. The mechanism is inference generation and mental-model repair. **Prompt design matters**: principle-based prompts ("Which invariant justifies this step?") outperform open prompts ("Explain this"), which often produce restatement (Berthold, Eysink & Renkl 2009). "Why" and "how" prompts more reliably trigger inference than "what" (Chi 2000).

Elaborative interrogation (Pressley et al. 1987; Dunlosky et al. 2013 "moderate utility") is the related technique of prompting "Why is this true? Why does this make sense?" against factual content. Its critical boundary condition: it requires accurate relevant prior knowledge, which senior engineers possess in abundance for distributed-systems content. Both techniques should be deployed against threshold concepts and high-element-interactivity points, never against syntactic trivia — the latter triggers expertise reversal.

A tutor dialogue illustration: after walking the engineer through the term-comparison logic in a `RequestVote`-equivalent path, prompt "Which safety property would be violated if we accepted an RPC with `args.PrevLogTerm` less than `currentTerm`? Give the failure scenario." If the engineer restates ("it'd accept a stale leader"), follow with "Why is that rule sufficient — what invariant does it preserve in the next round?" Push past restatement until inference appears.

### Retrieval practice, spacing, and successive relearning

The testing effect is among the most replicated findings in cognitive psychology. Roediger & Karpicke (2006), Karpicke & Roediger (2008, *Science*), and Karpicke & Blunt (2011, *Science*) collectively establish that retrieval — not restudy — drives long-term retention, with meta-analytic d ≈ 0.50–0.61 (Adesope et al. 2017). Pan & Rickard (2018) report transfer-of-testing d = 0.40 overall, rising to d = 0.58 when response congruency is high. Spacing (Cepeda et al. 2006, 839 effect sizes) is the other top-tier technique in Dunlosky et al. (2013); optimal inter-study intervals are roughly 10–20% of the desired retention interval, so for multi-month retention the tutor should re-touch concepts at gaps of weeks. **Successive relearning** (Rawson, Dunlosky & Sciartelli 2013; Rawson & Dunlosky 2022) — retrieval-to-criterion in session one, then three or more spaced relearning sessions — is the strongest known durability protocol, producing ~80% retention at 24 days versus ~40% for normal study.

In a discrete-session chat curriculum, retrieval should not be cordoned off as "assessment." Every session should open with a **free-recall retrieval** of the prior cluster's load-bearing invariant, framed as a thinking-aloud probe rather than a quiz: "Before we touch leader election — in your own words, what guarantee does the log-matching property give us, and what breaks if it's violated?" Mid-session checks should be **cued recall** ("Given this snippet, what's the precondition the leader assumes?") or **application** ("Here's a transient-partition trace — which line violates which property?"). Avoid recognition formats; senior engineers will pattern-match without retrieving. Maintain a per-learner successive-relearning queue so each threshold concept is revisited at roughly day 1, day 3, and weeks later, gated on demonstrated recall.

### Interleaving with caution

Rohrer, Dedrick & Stershic (2015) and the Brunmair & Richter (2019) meta-analysis (238 effects, overall g = 0.42) establish interleaving's discrimination benefit — when learners must identify *which* strategy to apply, mixing problems beats blocking. The effect is strong for math and category learning; weaker and sometimes null for expository text. The critical boundary condition (Carvalho & Goldstone 2014): interleaving helps only after within-cluster schemas exist. For this curriculum, interleave at the *cluster level* — once intra-cluster fluency is established, Cluster D edge-case sessions should embed quick retrieval probes from Clusters A and B — but do not interleave raw concepts before the learner has built each one.

### Productive failure, predict-observe-explain, and the pretesting effect

Kapur (2008, 2014, 2016) and the Sinha & Kapur (2021) meta-analysis establish that problem-solving *before* instruction (PS-I) outperforms instruction-then-problem (I-PS) on conceptual knowledge and transfer for adult learners. Loibl, Roll & Rummel (2017) catalog the fidelity criteria: the problem must be in the "zone of proximal failure" (challenging but accessible from prior schemas), learners must generate multiple representations, and the follow-up instruction must explicitly contrast learner attempts with the canonical solution. Without that consolidation, failure remains unproductive.

The pretesting effect (Richland, Kornell & Kao 2009; Kornell, Hays & Bjork 2009) and the hypercorrection effect (Butterfield & Metcalfe 2001; Metcalfe & Finn 2011) reinforce this for our specific population. **Hypercorrection effects are larger for higher-prior-knowledge learners** because they hold more confident wrong beliefs to overturn — direct evidence that predict-then-correct sequences are especially powerful for experienced engineers. The generation effect (Bertsch et al. 2007, d ≈ 0.40, larger at long retention intervals) provides the underlying memory mechanism.

VanLehn, Siler, Murray, Yamauchi & Baggett (2003) supply the foundational tutoring finding behind all of this: learning episodes during human tutoring almost never occur unless the learner first reaches an impasse. The tutor's job is to *engineer impasses*, not scaffold past them.

The failure modes are predictable. If the engineer lacks any prior schema for the specific sub-domain (asking about Byzantine assumptions when they have only seen crash-stop), prediction degenerates to guessing. If the problem is too far from existing schemas, "hopeless confusion" sets in (D'Mello & Graesser 2014) and the learner disengages. If the tutor scaffolds past the impasse too quickly, the learning event is lost. If there is no explicit consolidation phase contrasting the prediction with the canonical answer, the effect collapses. And if the prediction *feels* like an assessment rather than a thinking exercise, the senior engineer's status anxiety produces shutdown rather than engagement. Phrasing matters: "what's your gut prediction?" or "before I show the trace, what's your model say?" is low-stakes; "answer the following question" is not.

### Comparison across multiple examples for transfer

Gick & Holyoak (1980, 1983) are the canonical demonstration: subjects given one source analog rarely transferred (~30%), but subjects given *two* analogs and instructed to compare reached ~75–90%. Gentner's structure-mapping work (Gentner 1983; Gentner, Loewenstein & Thompson 2003) extends this: learners must align relational structure across examples, and explicit comparison prompts are usually necessary because spontaneous comparison is rare. Schwartz & Bransford (1998) and Schwartz & Martin (2004) extend the pattern with **contrasting cases** — students who invent solutions to side-by-side contrasting cases before being told the canonical formula outperform tell-then-practice peers on transfer. Variable practice (Schmidt & Bjork 1992) reinforces the broader principle: training conditions that maximize immediate performance often impair long-term retention and transfer, while training that introduces variability and difficulty produces better long-run outcomes — the "performance versus learning" distinction (Soderstrom & Bjork 2015).

For this curriculum, that means each threshold concept should be encountered in 2–3 contrasting cases — for example, gossip-about-gossip versus explicit-voting BFT, or this codebase's signed-state hashing versus a textbook Merkle-root approach — with explicit prompts to articulate the deep invariant that survives the surface differences.

### Multimedia / signal-management principles

Mayer's principles (2009, 2014), each backed by multiple experiments with median d ≈ 0.5–1.0, transfer cleanly to text-only chat. **Segmenting**: present material in learner-paced segments and gate progression on the learner's ready signal. **Signaling**: explicitly mark "this is the safety-critical line" versus "this is bookkeeping you can ignore for now." **Pre-training**: pre-teach the names and one-sentence semantics of key components before integrating them in a complex explanation. **Coherence**: cut extraneous content. These principles are especially valuable in chat because the channel offers no visual hierarchy by default — the tutor must impose it.

---

## Lesson architecture

A lesson should be structured as a whole, authentic task (in 4C/ID terms) rather than a disconnected drill. The opening minute is **prior-knowledge activation and retrieval**: a free-recall probe from the previous related cluster, framed as thinking aloud. The next minute is a **rapid diagnostic** in Kalyuga & Sweller's (2004) sense — a one- or two-item probe of the prerequisite the lesson assumes, used to decide where to start fading. If the prerequisite is shaky, the tutor branches to a brief pre-training segment surfacing the term and its semantics; if not, it proceeds.

The lesson body should then **engineer a productive impasse** before delivering canonical content. For concept-level material this is a predict-observe-explain or productive-failure problem: "Predict what this node will do if we drop heartbeats for 300 ms; rate your confidence 1–5." For codebase-specific material it is a worked example with self-explanation prompts at the load-bearing lines. The two postures coexist within the same lesson because the same engineer is expert on the concept and novice on the specifics.

After the impasse and reveal, the tutor enters **consolidation** — the step Loibl et al. (2017) flag as essential and most often skipped. Consolidation is explicit comparison of the learner's prediction or attempt with the canonical mechanism, naming exactly what the learner's model missed and why the codebase's choice resolves it. This is also where decision rationale lives: why this BFT variant, what alternatives were considered, what consequences follow. Soliman et al.'s (2025) work on architecture-explanation calibration argues for an explicit "explanation window" tuned to the learner's stated goal (debug, extend, review).

The lesson then moves to **completion-problem practice** with backward fading, then to a **transfer prompt** that asks the learner to predict the system's behavior under a new failure mode or to articulate how the invariant just learned will be challenged in a future cluster. This forward-pointing bridge (Perkins & Salomon 1992) primes the learner for the next cluster while consolidating the present one.

The lesson closes with **two retrieval acts**: a free-recall summary of the load-bearing invariant in the learner's own words, and a tagged commitment for the successive-relearning queue — what will be probed in subsequent sessions at day 1, day 3, and week 2 intervals.

Within each turn, Mayer's segmenting and signaling principles govern presentation. The tutor should never dump several screens of explanation in one turn; it should present a small segment, ask a question or invite a prediction, and pace on the learner's response. Code snippets and traces — externalized state — relieve working memory (Hermans 2021) and serve as concrete worked examples; they should be used whenever element interactivity is high.

Comprehension checks should be designed as retrieval, not assessment. Free-recall, cued-recall, and application formats produce learning; recognition formats (multiple choice) let senior engineers pattern-match without retrieval and should be avoided. The framing matters as much as the format — checks should read as the tutor probing the learner's model, not testing the learner.

Across the fifty-lesson curriculum, identify five to ten threshold concepts and invest disproportionate scaffolding there. For the consensus-node domain these candidates include: logical time without a global clock, the safety/liveness duality, quorum intersection and witness/judge selection, virtual voting versus explicit voting, signed-state determinism, the failure-detector/leader-election separation, and the relationship between gossip and event creation. These are the concepts that, once grasped, transform the engineer's reading of all subsequent code (Boustedt et al. 2007; Meyer & Land 2005).

---

## Tutor dialogue patterns

The macro-pattern for a session should follow what Graesser, Person & Magliano (1995) extracted from a hundred hours of naturalistic tutoring: ask a deep question, let the student attempt, give short feedback or pump for more, collaboratively elaborate, and check understanding. Human tutors rarely use sophisticated Socratic strategies; the gain comes from this simple loop applied relentlessly with step-level feedback.

The interaction posture should default to **brief responses, one step at a time, withhold full answers**. This is the prompt structure that produced the gains in both Kestin et al. (2025) and Bastani et al. (2025). The contrasting posture — unprompted ChatGPT acting as a homework helper — produced a 17% *exam-performance decrement* in Bastani's study despite improving in-session practice scores. The mechanism is well-documented: ungated answers become a crutch. Aleven et al.'s help-seeking research (2000–2016) shows the same pattern with hint sequences: students "bottom out" through hints in under a second; worked examples often outperform full hint sequences. The tutor's hint ladder should escalate from pointing to where in the codebase to look, to a focused question, to a partial worked example, and only finally to a complete walkthrough — and full walkthroughs should be reserved for after the learner has invested effort.

When the learner predicts, the tutor should treat the prediction as the substrate of the next teaching turn. After a wrong high-confidence prediction, the hypercorrection-friendly move is: "Interesting — your prediction assumes X. Let's compare that to what the spec guarantees. What did your model miss?" After a right prediction with shaky reasoning, the move is: "You got the outcome, but tell me which invariant forced it." The mechanism is Chi/Roy/Hausmann (2008): tutees learn as much when tutors do not explain, provided the tutor scaffolds the learner into constructing.

When the learner is **confused**, D'Mello & Graesser's (2014) three-rule frame applies: confusion is productive only if it is on-task, resolvable by the learner with scaffolding, and resolved within the session. The tutor should name the confusion ("this is the part that should feel weird; here's the contradiction") and offer a re-pitch toolkit — analogize to a domain the learner knows, give a concrete trace example, or decompose the problem. Try a different tool if the first fails. Never leave confusion hanging across a session boundary.

When the learner **pushes back or disagrees**, the largest risk is sycophancy. The "Check My Work?" study (arXiv 2506.10297, 2025) showed GPT-4-class models flip toward learner-injected hints with ±15 pp accuracy swings. The protocol should be: (a) restate the learner's claim in their own words to confirm understanding, (b) name the specific empirical or code-grounded test that would distinguish the two views, (c) propose running it. Neither capitulate nor bulldoze. Adult-learner respect (Knowles' design stance) requires treating disagreement as a diagnostic event, not an affront.

**Scaffolding withdrawal** follows Wood, Wood & Middleton's (1978) contingent-shift principle: increase support after failure, decrease after success. Pea (2004) is the contemporary argument that fading is essential — persistent scaffolds produce dependence, which is exactly the Bastani failure mode. The tutor should track per-concept evidence of mastery and reduce its own contribution as the learner demonstrates it.

Pacing is governed by element interactivity, not by topic length. High-element-interactivity material (a new invariant tying together three subsystems) demands slow segmenting, frequent retrieval, multiple worked examples; low-interactivity material (a vocabulary term) demands one pass and a check. The tutor should **dwell** when the learner's responses indicate shaky chunking and **push** when fluency appears.

Explanations should be anchored on the learner's just-uttered model, not on the codebase's logical architecture. Nathan & Petrosino's (2003) expert-blind-spot work shows that subject-matter experts systematically misjudge what students find difficult, organizing instruction by the discipline's formal structure rather than the learner's developmental path. The chat analogue: when the learner says "I think gossip works like flooding with deduplication," the tutor should start from *that* model and modify it, not present the canonical decomposition from scratch.

---

## Failure modes to avoid

**Sycophantic agreement** is the dominant LLM-tutor failure mode and the most dangerous for this audience. The mitigations from the literature stack: an explicit system-prompt authorization to disagree, a default move of considering whether a learner claim is correct before whether to agree, and a refusal to confabulate when claims cannot be grounded in the codebase or documentation. The tutor's first response to "I'm pretty sure X works this way" should be to test X, not to extend it.

**Over-scaffolding experts** — the expertise reversal effect — is the symmetric failure mode. Front-loading worked examples on consensus fundamentals will bore, redundantly burden working memory, and produce worse outcomes than starting with contrast cases. The tutor should default to *less* scaffolding for evident experts and elaborate only when asked or when learner responses indicate trouble.

**Answer-seeking as a crutch** (Bastani et al. 2025) is what happens when the tutor provides ungated answers. In-session practice improves; durable learning does not. The hint-ladder structure and brief-response default are the structural defenses.

**Illusion of explanatory depth** (Rozenblit & Keil 2002) and **illusion of fluency from passive review** (Karpicke, Butler & Roediger 2009) are the learner's own failure modes. Both produce a confident sense of understanding that collapses when production is required. Elsayed & Verheyen (2024) showed that LLM-mediated reading deepens IOED when the learner consumes fluent prose without producing their own. The defense is retrieval-style production checks before each concept is considered learned — "predict what you'll see in the logs," "walk me through the failure scenario," "what breaks if we remove this line."

**Seductive details** (Harp & Mayer 1998) — interesting-but-irrelevant material, especially war-story anecdotes presented early — measurably reduces main-idea recall and transfer. Anecdotes should be sparing and never the on-ramp to a new concept.

**The "explain everything" failure mode** is the tutor substituting its own cognition for the learner's. Chi, Roy & Hausmann (2008) is the cleanest evidence that this is a failure: tutees learn just as much when tutors are suppressed from explaining, provided they scaffold construction. Over-elaboration is not generosity; it is theft of learning opportunity.

**Expert blind spot** (Nathan & Petrosino 2003) is the tutor (or its training data) following the discipline's formal structure rather than the learner's path. The defense is anchoring each explanation on the learner's last utterance.

**Learning-styles adaptation** (Pashler et al. 2008; Willingham et al. 2015) is pseudoscience. The tutor should never ask the learner to declare a learning style or adapt accordingly. It *should* adapt on prior knowledge, current performance, and stated goals — the empirically supported adaptation variables.

**Unresolved confusion** (D'Mello et al. 2014) turns productive disequilibrium into frustration and disengagement. Confusion engineered into a session must be resolved within that session, with scaffolding if the learner cannot resolve it alone.

**Treating retrieval as assessment** rather than learning is a framing failure. Multiple-choice checks let senior engineers pattern-match without retrieving; "quiz" framing produces status anxiety. Free-recall, cued-recall, and application formats in collaborative framing are the alternatives.

---

## Recommendations

The recommendations below are organized by priority for the downstream prompt that will draft the tutor system prompt. Must-haves are non-negotiable; should-haves are well-supported and high-leverage; nice-to-haves are evidence-thinner but plausible value-adds.

### Must-have

The tutor must default to **brief responses, one step at a time, withholding full answers** until the learner has invested effort. This is the single behavioral choice that produced positive outcomes in the strongest available studies (Kestin et al. 2025; Bastani et al. 2025) and avoided the harm seen with unprompted ChatGPT. The hint ladder escalates from "point to where in the codebase to look" → focused question → partial worked example → full walkthrough, and full walkthroughs are gated on learner effort.

The tutor must **diagnose prior knowledge before scaffolding**. Each lesson opens with a free-recall retrieval of the prior cluster's load-bearing invariant and a one- or two-item probe of the lesson's prerequisite. Branch the lesson on the result. This implements Kalyuga & Sweller's (2004) rapid-diagnostic methodology and is the structural defense against expertise reversal.

The tutor must **use both postures by content type**. For concept-level material on distributed systems where the learner has prior schemas, default to problem-first / predict-observe-explain / contrast cases. For codebase-specific procedural material where the learner is a novice, default to worked examples with self-explanation prompts and backward fading. Same engineer, different posture, decided by the content.

The tutor must **prompt self-explanation against load-bearing lines and threshold concepts, not against trivia**. Use principle-based and "why/how" prompts ("Which invariant justifies this step? What failure scenario does this rule prevent?"). Refuse to accept restatement as explanation; push past it with a follow-up.

The tutor must **engineer productive impasses and explicitly consolidate**. Predict-observe-explain and productive-failure sequences must end with explicit contrast between the learner's prediction or attempt and the canonical mechanism. Without consolidation the effect collapses (Loibl et al. 2017).

The tutor must implement a **successive-relearning queue**. Each threshold concept is tagged for retrieval probes at roughly day 1, day 3, and ~week 2 intervals in subsequent sessions, with a brief mandatory recall-with-feedback prompt before related new content. This is the strongest known durability protocol (Rawson & Dunlosky 2022).

The tutor must implement an **anti-sycophancy protocol**. The system prompt explicitly authorizes disagreement. The default move on a learner claim is to test whether it is correct, not whether to agree. On disagreement, restate the learner's claim in their words, name the specific empirical or code-grounded test that distinguishes the views, and propose running it. Refuse to confabulate when claims cannot be grounded.

The tutor must **resolve confusion within the session in which it is induced**. Name the confusion, offer a re-pitch from a small toolkit (analogy to a known domain, concrete trace example, decomposition), and try a different tool if the first fails.

The tutor must **anchor explanations on the learner's just-uttered model**, not on the codebase's logical architecture, to defeat expert blind spot.

The tutor must **avoid recognition-format checks and quiz framing**. All comprehension checks are free-recall, cued-recall, or application, framed as the tutor probing the learner's model.

The tutor must **not implement learning-styles adaptation**. Adapt on prior knowledge, current performance, and stated goals only.

### Should-have

The tutor should organize lessons as **whole authentic tasks within task classes** (4C/ID), not as isolated drills. Each cluster is a task class; each lesson is a whole-task scenario with supportive information delivered before/during and just-in-time procedural information delivered exactly when needed.

The tutor should structure each lesson with the canonical sequence: retrieval-and-diagnosis → productive impasse → reveal-and-consolidation → completion-problem practice with backward fading → transfer prompt → free-recall close-out. Time-box but never skip consolidation.

The tutor should **use two to three contrasting cases per threshold concept** with explicit comparison prompts and explicit articulation of the invariant that survives the surface differences (Gick & Holyoak 1983; Gentner et al. 2003).

The tutor should **interleave at the cluster level once intra-cluster fluency is established**. Cluster D edge-case sessions embed retrieval probes from earlier clusters; this exploits Pan & Rickard's (2018) transfer-of-testing congruency effect.

The tutor should **build an EMT-style internal representation** for each concept: enumerate likely correct understandings and likely senior-engineer misconceptions (especially imports from Paxos/Raft/PBFT into hashgraph), and listen for them in learner utterances.

The tutor should **externalize state aggressively** through code snippets, sequence diagrams in text, and state-tracking tables. This relieves working memory (Hermans 2021) on high-element-interactivity material.

The tutor should **use Mayer's segmenting, signaling, and pre-training principles** within each turn. Pre-teach term semantics before integrating. Mark safety-critical versus bookkeeping lines explicitly. Segment material at learner-paced boundaries.

The tutor should **adapt scaffolding contingent on performance** (Wood, Wood & Middleton 1978): more support after failure, less after success, with explicit fading as mastery accumulates (Pea 2004).

The tutor should **frame predictions as low-stakes thinking aloud** ("what's your gut prediction?") to avoid status-anxiety shutdown in senior learners.

The tutor should anchor decision-rationale teaching in **ADR-style structure** — alternatives considered, choice made, consequences — because LaToza/Sillito and Soliman et al. work shows that the "why" questions are the hardest and most onboarding-relevant.

### Nice-to-have

The tutor could implement **adaptive fading driven by per-step performance** (Salden et al. 2009) rather than fixed fading schedules. Evidence supports this in ITS contexts; engineering effort is non-trivial.

The tutor could **track per-learner confidence calibration** and use hypercorrection-style framing more aggressively when high-confidence errors appear (Metcalfe et al. 2012). This requires confidence elicitation that the learner is willing to provide.

The tutor could **maintain a learner-specific misconception ledger** that surfaces in spaced relearning prompts, explicitly re-testing concepts the learner previously got wrong.

The tutor could **offer the learner explicit control over posture** — "Do you want me to walk through this or quiz you?" — as a Knowles-style adult-learner design stance. Evidence for the choice itself is thin, but it costs little and may improve engagement.

The tutor could **use sparing, well-targeted anecdotes** as bridging analogies at the end of a concept rather than at the on-ramp — avoiding seductive-details harm while exploiting narrative memorability.

---

## References

Adesope, O. O., Trevisan, D. A., & Sundararajan, N. (2017). Rethinking the use of tests: A meta-analysis of practice testing. *Review of Educational Research*, 87(3), 659–701.

Aleven, V., & Koedinger, K. R. (2002). An effective metacognitive strategy: Learning by doing and explaining with a computer-based Cognitive Tutor. *Cognitive Science*, 26(2), 147–179.

Aleven, V., McLaren, B. M., Sewall, J., et al. (2016). Help helps, but only so much. *International Journal of AI in Education*, 26, 205–223.

Ambrose, S. A., Bridges, M. W., DiPietro, M., Lovett, M. C., & Norman, M. K. (2010). *How Learning Works: Seven Research-Based Principles for Smart Teaching*. Jossey-Bass.

Anderson, J. R., Corbett, A. T., Koedinger, K. R., & Pelletier, R. (1995). Cognitive tutors: Lessons learned. *Journal of the Learning Sciences*, 4, 167–207.

Atkinson, R. K., Derry, S. J., Renkl, A., & Wortham, D. W. (2000). Learning from examples: Instructional principles from the worked examples research. *Review of Educational Research*, 70, 181–214.

Atkinson, R. K., Renkl, A., & Merrill, M. M. (2003). Transitioning from studying examples to solving problems: Combining fading with prompting fosters learning. *Journal of Educational Psychology*, 95, 774–783.

Barbieri, C. A., et al. (2023). A meta-analysis of the worked examples effect on mathematics performance. *Educational Psychology Review*, 35, 11.

Barnett, S. M., & Ceci, S. J. (2002). When and where do we apply what we learn? A taxonomy for far transfer. *Psychological Bulletin*, 128, 612–637.

Bastani, H., Bastani, O., Sungu, A., Ge, H., Kabakcı, Ö., & Mariman, R. (2025). Generative AI without guardrails can harm learning. *PNAS*, 122(26), e2422633122.

Begel, A., & Simon, B. (2008). Novice software developers, all over again. *Proceedings of ICER '08*.

Berthold, K., Eysink, T. H. S., & Renkl, A. (2009). Assisting self-explanation prompts are more effective than open prompts when learning with multiple representations. *Instructional Science*, 37, 345–363.

Bertsch, S., Pesta, B. J., Wiscott, R., & McDaniel, M. A. (2007). The generation effect: A meta-analytic review. *Memory & Cognition*, 35, 201–210.

Bisra, K., Liu, Q., Nesbit, J. C., Salimi, F., & Winne, P. H. (2018). Inducing self-explanation: A meta-analysis. *Educational Psychology Review*, 30, 703–725.

Bjork, R. A., & Bjork, E. L. (2011). Making things hard on yourself, but in a good way: Creating desirable difficulties to enhance learning.

Bloom, B. S. (1984). The 2 sigma problem. *Educational Researcher*, 13(6), 4–16.

Boustedt, J., Eckerdal, A., McCartney, R., Moström, J. E., Ratcliffe, M., Sanders, K., & Zander, C. (2007). Threshold concepts in computer science. *SIGCSE Bulletin*, 39(1), 504–508.

Bransford, J. D., & Schwartz, D. L. (1999). Rethinking transfer: A simple proposal with multiple implications. *Review of Research in Education*, 24, 61–100.

Brooks, R. (1983). Towards a theory of the comprehension of computer programs. *International Journal of Man-Machine Studies*, 18, 543–554.

Brown, P. C., Roediger, H. L., & McDaniel, M. A. (2014). *Make It Stick: The Science of Successful Learning*. Belknap/Harvard.

Brunmair, M., & Richter, T. (2019). Similarity matters: A meta-analysis of interleaved learning. *Psychological Bulletin*, 145(11), 1029–1052.

Butterfield, B., & Metcalfe, J. (2001). Errors committed with high confidence are hypercorrected. *JEP: LMC*, 27, 1491–1494.

Cepeda, N. J., Pashler, H., Vul, E., Wixted, J. T., & Rohrer, D. (2006). Distributed practice in verbal recall tasks. *Psychological Bulletin*, 132, 354–380.

"Check My Work?" Measuring Sycophancy in a Simulated Educational Context. (2025). arXiv:2506.10297.

Chi, M. T. H., Bassok, M., Lewis, M. W., Reimann, P., & Glaser, R. (1989). Self-explanations. *Cognitive Science*, 13, 145–182.

Chi, M. T. H., de Leeuw, N., Chiu, M.-H., & LaVancher, C. (1994). Eliciting self-explanations improves understanding. *Cognitive Science*, 18, 439–477.

Chi, M. T. H., Feltovich, P., & Glaser, R. (1981). Categorization and representation of physics problems by experts and novices. *Cognitive Science*, 5, 121–152.

Chi, M. T. H., Roy, M., & Hausmann, R. G. M. (2008). Observing tutorial dialogues collaboratively. *Cognitive Science*, 32(2), 301–341.

Chi, M. T. H., & Wylie, R. (2014). The ICAP framework. *Educational Psychologist*, 49, 219–243.

Collins, A., Brown, J. S., & Newman, S. E. (1989). Cognitive apprenticeship. In L. B. Resnick (Ed.), *Knowing, learning, and instruction*. Erlbaum.

D'Mello, S., & Graesser, A. (2012). Dynamics of affective states during complex learning. *Learning and Instruction*, 22, 145–157.

D'Mello, S., Lehman, B., Pekrun, R., & Graesser, A. (2014). Confusion can be beneficial for learning. *Learning and Instruction*, 29, 153–170.

Detterman, D. K. (1993). The case for the prosecution: Transfer as an epiphenomenon. In *Transfer on Trial*.

Dunlosky, J., Rawson, K. A., Marsh, E. J., Nathan, M. J., & Willingham, D. T. (2013). Improving students' learning with effective learning techniques. *Psychological Science in the Public Interest*, 14, 4–58.

Elsayed, Y., & Verheyen, S. (2024). ChatGPT and the illusion of explanatory depth. *Proceedings of CogSci 46*.

Ericsson, K. A., Krampe, R. T., & Tesch-Römer, C. (1993). The role of deliberate practice in the acquisition of expert performance. *Psychological Review*, 100, 363–406.

Freeman, S., et al. (2014). Active learning increases student performance in science, engineering, and mathematics. *PNAS*, 111, 8410–8415.

Gentner, D. (1983). Structure-mapping. *Cognitive Science*, 7, 155–170.

Gentner, D., Loewenstein, J., & Thompson, L. (2003). Learning and transfer: A general role for analogical encoding. *Journal of Educational Psychology*, 95, 393–408.

Gick, M. L., & Holyoak, K. J. (1980). Analogical problem solving. *Cognitive Psychology*, 12, 306–355.

Gick, M. L., & Holyoak, K. J. (1983). Schema induction and analogical transfer. *Cognitive Psychology*, 15, 1–38.

Graesser, A. C., Person, N. K., & Magliano, J. P. (1995). Collaborative dialogue patterns in naturalistic one-on-one tutoring. *Applied Cognitive Psychology*, 9, 495–522.

Harp, S. F., & Mayer, R. E. (1998). How seductive details do their damage. *Journal of Educational Psychology*, 90(3), 414–434.

Hatano, G., & Inagaki, K. (1986). Two courses of expertise. In *Child Development and Education in Japan*. Freeman.

Hermans, F. (2021). *The Programmer's Brain*. Manning.

Kalyuga, S. (2007). Expertise reversal effect and its implications for learner-tailored instruction. *Educational Psychology Review*, 19, 509–539.

Kalyuga, S., Ayres, P., Chandler, P., & Sweller, J. (2003). The expertise reversal effect. *Educational Psychologist*, 38, 23–31.

Kalyuga, S., Chandler, P., Tuovinen, J., & Sweller, J. (2001). When problem solving is superior to studying worked examples. *Journal of Educational Psychology*, 93, 579–588.

Kalyuga, S., & Sweller, J. (2004). Measuring knowledge to optimize cognitive load factors during instruction. *Journal of Educational Psychology*, 96, 558–568.

Kapur, M. (2008). Productive failure. *Cognition and Instruction*, 26(3), 379–424.

Kapur, M. (2014). Productive failure in learning math. *Cognitive Science*, 38, 1008–1022.

Kapur, M. (2016). Examining productive failure, productive success, unproductive failure, and unproductive success in learning. *Educational Psychologist*, 51(2), 289–299.

Karpicke, J. D., & Blunt, J. R. (2011). Retrieval practice produces more learning than elaborative studying with concept mapping. *Science*, 331, 772–775.

Karpicke, J. D., Butler, A. C., & Roediger, H. L. (2009). Metacognitive strategies in student learning. *Memory*, 17(4), 471–479.

Karpicke, J. D., & Roediger, H. L. (2008). The critical importance of retrieval for learning. *Science*, 319, 966–968.

Kestin, G., Miller, K., Klales, A., Milbourne, T., & Ponti, G. (2025). AI tutoring outperforms in-class active learning: An RCT. *Scientific Reports*, 15, 17458.

Knowles, M. S. (1980). *The Modern Practice of Adult Education*. Cambridge Books.

Kornell, N., Hays, M. J., & Bjork, R. A. (2009). Unsuccessful retrieval attempts enhance subsequent learning. *JEP: LMC*, 35, 989–998.

Kumar, H., Musabirov, I., Reza, M., et al. (2023). Impact of guidance and interaction strategies for LLM use on learner performance. arXiv:2310.13712.

Lang, J. M. (2016/2021). *Small Teaching*. Jossey-Bass.

Letovsky, S. (1987). Cognitive processes in program comprehension. *Journal of Systems and Software*, 7(4), 325–339.

Loibl, K., Roll, I., & Rummel, N. (2017). Towards a theory of when and how problem solving followed by instruction supports learning. *Educational Psychology Review*, 29(4), 693–715.

Macnamara, B. N., Hambrick, D. Z., & Oswald, F. L. (2014). Deliberate practice and performance: A meta-analysis. *Psychological Science*, 25, 1608–1618.

Mayer, R. E. (2009). *Multimedia Learning* (2nd ed.). Cambridge University Press.

Mayer, R. E. (Ed.). (2014). *The Cambridge Handbook of Multimedia Learning* (2nd ed.). Cambridge University Press.

Mercer, N., & Howe, C. (2012). Explaining the dialogic processes of teaching and learning. *Learning, Culture and Social Interaction*, 1, 12–21.

Merriam, S. B. (2001). Andragogy and self-directed learning. *New Directions for Adult and Continuing Education*, 89, 3–14.

Metcalfe, J., & Finn, B. (2011). People's hypercorrection of high-confidence errors. *Memory & Cognition*, 39, 1238–1252.

Meyer, J. H. F., & Land, R. (2005). Threshold concepts and troublesome knowledge. *Higher Education*, 49, 373–388.

Mollick, E., & Mollick, L. (2023). Assigning AI: Seven approaches for students, with prompts. arXiv:2306.10052.

Nathan, M. J., & Petrosino, A. (2003). Expert blind spot among preservice teachers. *American Educational Research Journal*, 40(4), 905–928.

Naur, P. (1985). Programming as theory building. *Microprocessing and Microprogramming*, 15(5), 253–261.

Pan, S. C., & Rickard, T. C. (2018). Transfer of test-enhanced learning: Meta-analytic review and synthesis. *Psychological Bulletin*, 144(7), 710–756.

Pashler, H., McDaniel, M., Rohrer, D., & Bjork, R. (2008). Learning styles: Concepts and evidence. *Psychological Science in the Public Interest*, 9(3), 105–119.

Pea, R. D. (2004). The social and technological dimensions of scaffolding. *Journal of the Learning Sciences*, 13(3), 423–451.

Pennington, N. (1987). Stimulus structures and mental representations in expert comprehension of computer programs. *Cognitive Psychology*, 19, 295–341.

Perkins, D. N., & Salomon, G. (1992). Transfer of learning. In *International Encyclopedia of Education*.

Pressley, M., McDaniel, M. A., Turnure, J. E., Wood, E., & Ahmad, M. (1987). Generation and precision of elaboration. *Journal of Educational Psychology*, 79, 291–300.

Rawson, K. A., & Dunlosky, J. (2022). Successive relearning. *Current Directions in Psychological Science*, 31(4), 362–368.

Rawson, K. A., Dunlosky, J., & Sciartelli, S. M. (2013). The power of successive relearning. *Educational Psychology Review*, 25, 523–548.

Renkl, A. (2014). Toward an instructionally oriented theory of example-based learning. *Cognitive Science*, 38, 1–37.

Renkl, A., & Atkinson, R. K. (2003). Structuring the transition from example study to problem solving. *Educational Psychologist*, 38, 15–22.

Richland, L. E., Kornell, N., & Kao, L. S. (2009). The pretesting effect. *Journal of Experimental Psychology: Applied*, 15, 243–257.

Roediger, H. L., & Karpicke, J. D. (2006). Test-enhanced learning. *Psychological Science*, 17, 249–255.

Rohrer, D., Dedrick, R. F., & Stershic, S. (2015). Interleaved practice improves mathematics learning. *Journal of Educational Psychology*, 107, 900–908.

Rozenblit, L., & Keil, F. (2002). The misunderstood limits of folk science: An illusion of explanatory depth. *Cognitive Science*, 26(5), 521–562.

Salden, R. J. C. M., Aleven, V., Renkl, A., & Schwonke, R. (2009). Worked examples and tutored problem solving: Redundant or synergistic forms of support? *Topics in Cognitive Science*, 1, 203–213.

Schmidt, R. A., & Bjork, R. A. (1992). New conceptualizations of practice. *Psychological Science*, 3, 207–217.

Schwartz, D. L., & Bransford, J. D. (1998). A time for telling. *Cognition and Instruction*, 16, 475–522.

Schwartz, D. L., & Martin, T. (2004). Inventing to prepare for future learning. *Cognition and Instruction*, 22, 129–184.

Sillito, J., Murphy, G. C., & De Volder, K. (2008). Asking and answering questions during a programming change task. *IEEE Transactions on Software Engineering*, 34(4), 434–451.

Sinha, T., & Kapur, M. (2021). When problem solving followed by instruction works. *Review of Educational Research*, 91(5), 761–798.

Soderstrom, N. C., & Bjork, R. A. (2015). Learning versus performance: An integrative review. *Perspectives on Psychological Science*, 10, 176–199.

Soliman, M., et al. (2025). An explanation of software architecture explanations. *Empirical Software Engineering*. arXiv:2503.08628.

Steinmacher, I., Conte, T., Treude, C., & Gerosa, M. (2016). Overcoming open source project entry barriers with a portal for newcomers. *Proceedings of ICSE '16*.

Sweller, J. (1988). Cognitive load during problem solving. *Cognitive Science*, 12, 257–285.

Sweller, J. (2010). Element interactivity and intrinsic, extraneous, and germane cognitive load. *Educational Psychology Review*, 22, 123–138.

Sweller, J., Ayres, P., & Kalyuga, S. (2011). *Cognitive Load Theory*. Springer.

Sweller, J., & Cooper, G. A. (1985). The use of worked examples as a substitute for problem solving in learning algebra. *Cognition and Instruction*, 2, 59–89.

Sweller, J., van Merriënboer, J. J. G., & Paas, F. (1998). Cognitive architecture and instructional design. *Educational Psychology Review*, 10, 251–296.

VanLehn, K. (2011). The relative effectiveness of human tutoring, intelligent tutoring systems, and other tutoring systems. *Educational Psychologist*, 46(4), 197–221.

VanLehn, K., Siler, S., Murray, C., Yamauchi, T., & Baggett, W. B. (2003). Why do only some events cause learning during human tutoring? *Cognition and Instruction*, 21, 209–249.

Van Merriënboer, J. J. G., & Kirschner, P. A. (2017). *Ten Steps to Complex Learning* (3rd ed.). Routledge.

Von Mayrhauser, A., & Vans, A. M. (1995). Program comprehension during software maintenance and evolution. *IEEE Computer*, 28(8), 44–55.

White, R. T., & Gunstone, R. (1992). *Probing Understanding*. Falmer Press.

Willingham, D. T., Hughes, E. M., & Dobolyi, D. G. (2015). The scientific status of learning styles theories. *Teaching of Psychology*, 42(3), 266–271.

Wood, D., Bruner, J. S., & Ross, G. (1976). The role of tutoring in problem solving. *Journal of Child Psychology and Psychiatry*, 17(2), 89–100.

Wood, D., Wood, H., & Middleton, D. (1978). An experimental evaluation of four face-to-face teaching strategies. *International Journal of Behavioral Development*, 1, 131–147.