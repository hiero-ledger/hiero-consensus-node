# AGENTS.md

This repository supports collaboration between human contributors and AI software development agents.

AI Agents: Start Here
---------------------

Welcome. Before taking any action, you MUST read and internalize the following kernel documents in order. They provide the minimum shared context required for safe and extensible operation.

1. `docs/ai/system/README.md` – Orientation to the kernel architecture, directory layout, and expectations for extension.
2. `docs/ai/system/invocations.md` – Authoritative list of available commands, clarifications, and confirmation rules.
3. `docs/ai/system/universal-workflow-constraints.md` – Mandatory guardrails for every workflow.
4. `docs/ai/system/workflows/ai-workflow-design-workflow.md` – How to design and register new workflows safely.
5. `docs/ai/diataxis/reference/gradle-conventions.md` – Build system conventions and module testing guidance.

After reading these five files, you will be fully onboarded to the kernel. Do not proceed with any other actions until you have done so.

Kernel Documentation Hubs
-------------------------

- `docs/diataxis/` – Repository-wide reference, how-to, tutorial, and explanation material (non-AI specific).
- `docs/ai/diataxis/` – Diátaxis-aligned material for the AI system (tutorials, how-tos, explanations, references).
- `docs/ai/system/` – Canonical workflows, guardrails, invocation index, and system metadata.
- `docs/ai/tasks/` – Persistent task sequences and other resumable AI work artifacts.
- `docs/dev/` – Human-authored project notes, planning docs, or issue breakdowns that the AI may reference.

Invocation-triggered Workflow Loading (per-session)
---------------------------------------------------

1) Consult `docs/ai/system/invocations.md` (already read during core ingestion). Use exact-trigger matching first; fall back to alias matching (case-insensitive, trimmed) only if no exact match is found. If multiple mapping rows match, present a short disambiguation prompt to the user and proceed with the chosen trigger.

2) Per-session loading semantics ("first time" behavior):
- Maintain an in-memory session set `loadedTriggers` for the current AI session.
- When a trigger T is encountered for the first time (T ∉ `loadedTriggers`), the agent MUST load the associated workflow spec:
  a) Read the workflow spec path (relative to `docs/ai/system/`) from the invocation table.
  b) Parse front matter metadata (`mode`, `status`, `required clarifications`, `preconditions`, `confirmation required`).
  c) Record T in `loadedTriggers` and cache the parsed workflow spec for the session.
- On subsequent occurrences of T, use the cached parsed spec — do NOT re-read the file unless the user asks to reload it.

3) Pre-flight obligations after loading a workflow spec:
- If the workflow `Mode` = M or R→M, present a short summary to the user including:
  * Workflow title and path
  * Mode (R, M, or R→M)
  * Required clarifications (questions to ask if missing)
  * Preconditions (e.g., clean working tree)
  * Whether confirmation is required before modifications
  * Primary outputs of the workflow
- Ask any `Required Clarifications` that are missing before executing the workflow.
- Do NOT perform writes (create/stage/commit/push) unless the workflow and universal constraints allow it and the user explicitly authorizes the modification.

4) Multi-trigger input and disambiguation:
- If user input contains multiple recognized triggers, present a numbered list of matched workflows and ask which one(s) to load and apply. Do NOT assume an ordering or implicitly load all of them.

5) Missing or malformed workflow spec files:
- If the invocation points to a missing or malformed workflow, inform the user and ask whether to (a) create a scaffold via the design workflow, (b) proceed in read-only analysis mode, or (c) abort.

6) Optional preload behavior (explicit user opt-in):
- If the user plans to run many workflows (or asks to "preload workflows"), ask: "Load all invocation-linked workflows now for faster responses? (yes/no)" — only proceed if the user agrees.

7) Auditing and traceability:
- Track session metadata: triggers encountered, workflow files loaded, timestamps, and user approvals for any M-mode actions. Include this metadata in final reports when applicable.

Rationale
---------

This on-demand loading approach minimizes initial read cost while ensuring the agent follows the authoritative workflow spec for each trigger the first time it is requested. It preserves safety by requiring the pre-flight checks defined in each workflow spec and in `docs/ai/system/universal-workflow-constraints.md`.

Operating Principles for AI Agents
----------------------------------

1. Always inspect before editing. Use search/read tools to gather sufficient context.
2. Minimize blast radius: prefer the smallest, cohesive diff that satisfies the requirement.
3. Declare assumptions when ambiguity exists; do not invent APIs or modules.
4. Validate changes locally (build + tests) before proposing completion.
5. Maintain repository conventions (style, structure, naming, branching) – consult existing patterns and `docs/diataxis/reference/` or `docs/ai/diataxis/reference/` entries.
6. Keep formatting lint-clean: include standard front matter (`---` block with `title`, `status`, `diataxis`, `last-validated`) when authoring Markdown; avoid trailing whitespace or inconsistent tables.
7. Distinguish authoritative statements from inferred hypotheses.
8. Never remove or overwrite human-authored rationale without preserving intent.
9. Surface risks early (hidden coupling, migration cost, dependency churn).
10. Escalate with alternative solution sketches if the primary approach seems costly or brittle.
11. Treat prompts, tutorials, and workflow docs as living artifacts – propose incremental improvements with clear diffs.
12. Obey universal workflow constraints (`docs/ai/system/universal-workflow-constraints.md`) at all times (including "no commits/pushes without explicit user direction").

Startup Protocol (AI Mandatory Behavior)
----------------------------------------

At the start of every AI session, the agent MUST execute the following ordered protocol. This is not a workflow to be invoked, but a mandatory, built-in procedure. The agent MUST confirm completion before accepting user instructions.

**Protocol Steps:**

- [ ] **Step 1: Core Documentation Ingestion.** Read and load:

  - [ ] `docs/ai/system/README.md`
  - [ ] `docs/ai/system/invocations.md`
  - [ ] `docs/ai/system/universal-workflow-constraints.md`
  - [ ] `docs/ai/system/workflows/ai-workflow-design-workflow.md`
  - [ ] `docs/ai/diataxis/reference/gradle-conventions.md`

  **Execution note:** When running Gradle tasks that target service modules in this repository, do NOT prefix the module path with `:hedera-node`. Invoke modules directly (e.g., `./gradlew :hiero-clpr-interledger-service-impl:test`) per the Gradle conventions doc.

- [ ] **Step 2: Active Task Sequence Scan.** Scan `docs/ai/tasks/active/` for `seq-*.md` files.

- [ ] **Step 3: Report and Prompt for Resumption.**

  - [ ] If one or more active or completable task sequences exist, present a numbered list to the user (newest first).
  - [ ] Prompt: "Resume a task sequence? (enter number, 'none', 'archive <n>', 'delete <n>')".
  - [ ] If the user chooses to resume a sequence, read its `Resumption Context` before proceeding.

- [ ] **Step 4: Protocol Confirmation.** Output: "Startup protocol complete. I have loaded all required AI documentation and checked for active task sequences. I am ready for your instructions."

Rationale: This explicit procedure ensures the agent actually ingests the kernel docs and honors resumable work before executing any instruction.

Task Sequence Auto-Creation Heuristics
--------------------------------------

Before executing any workflow beyond a trivial single-step response, evaluate whether to create a new task sequence in `docs/ai/tasks/active/`. Create one (if none active for the same scope) when ANY of these hold:
- Predicted steps > 4 OR multi-phase (R→M with approval gate)
- Expected edits across > 2 files OR > 200 total modified lines (estimate)
- Workflow involves both analysis and patch generation phases
- Operation spans more than one logical domain (e.g., code + docs sync)
- User indicates "this may take a while" or similar intent

Creation Protocol:
1. Derive slug from primary goal (kebab-case, ≤5 words).
2. Copy `docs/ai/tasks/template-sequence.md` to `docs/ai/tasks/active/seq-<yyyymmdd>-<slug>.md`.
3. Populate front matter (branch, purpose, workflow).
4. Insert ONLY the pending subset of tasks (not the entire workflow spec).
5. Set `status: active` and timestamp `created` + `last-updated`.
6. Inform user: "Created task sequence <filename> (delete when complete). Proceed? (yes/no)".

Non-Creation Justification: If heuristics trigger but no sequence is created, state a concise justification (e.g., "All remaining steps atomic; single response").

Completion & Deletion Safeguard
-------------------------------

When all tasks are checked:
1. Set `status: completed`.
2. Ask: "Delete or archive task sequence <filename>? (delete/archive/skip)".
3. Delete if `delete`; move to `docs/ai/tasks/tabled/` if `archive`; leave unchanged if `skip`.

Auditability & Minimal Diff Guarantee
-------------------------------------

- Updates to a task sequence should only modify checkbox states, `last-updated`, or append context/decision notes.
- Avoid reformatting unaffected lines.
- Keep sequences lean; do not duplicate workflow specs.

Tutorial Facilitation
---------------------

Tutorial content lives under `docs/ai/diataxis/tutorials/`. Use the `run tutorial` workflow to guide users through a lesson interactively. Tutorials may introduce additional workflows (e.g., SDLC stages) as part of the learning experience.

Sequence-Aware Recommendation Policy
------------------------------------

During retrospective or improvement workflows, scan `docs/ai/tasks/active/` and `docs/ai/tasks/tabled/`. If a recommendation's scope overlaps an existing sequence, output "Resume sequence: <slug>" in lieu of duplicating the recommendation.

End of File.
