# 1B Observational Reconnect Extraction Execution Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to execute this plan task-by-task. Before spawning extraction or verification workers, use `superpowers:dispatching-parallel-agents`. Use `superpowers:systematic-debugging` for contradictory or unexpectedly missing evidence and `superpowers:verification-before-completion` before claiming the extraction complete.

**Goal:** Produce a verified, Markdown-only observational extraction of the single approximately one-billion-record reconnect collection from July 16, 2026, including layered recovery outcomes, standard reconnect evidence, SocketFactory lifecycle telemetry, and focused `ss -tinm` evidence.

**Architecture:** Register one observational collection in the manifest, then use the repository's phase-split extraction topology: preflight, run anchors, run-scoped evidence workers, lead-only assembly, and a fresh verifier. The reusable observational profile supplies the calibration overrides and new socket worker contracts; the existing protocol, atlas, and agentic strategy continue to supply shared extraction and source-reference rules.

**Tech Stack:** Markdown, Git, `rg`, `find`, `sed`, `awk`, standard shell text processing, and Codex sub-agents. No Java, Gradle, production-code changes, or permanent machine-ingestion artifacts are required.

## Global Constraints

- Raw artifact files are read-only.
- Allowed repository edits are under `25083-improve-reconnectbench/**` only.
- Do not modify production/runtime consensus-node behavior.
- This is an observational extraction, not a calibration pass.
- Do not report calibration acceptance, local benchmark parameter recommendations, traversal ranking, historical comparison, or cross-run causal claims.
- Fatal network disease does not stop the observational pass; it becomes outcome evidence.
- Only the lead agent writes final Markdown files.
- Workers return findings and source references; they do not edit extraction outputs.
- Every required evidence item uses exactly one canonical status: `present`, `derived`, `missing`, `ambiguous`, or `not_applicable`.
- Every present or derived value has a same-row or same-block run-root-relative source reference.
- Do not load full large logs, stats CSVs, or sampler files into model context.
- Use all seven passive samplers for coverage and endpoint attribution, but deeply analyze only learner/teacher reconnect sockets on both endpoints.
- Never claim `ss` `send`, `pacing_rate`, or `delivery_rate` as link capacity.
- Durable extraction outputs are exactly `reconnect-run.md`, `verification-notes.md`, and `extraction-summary.md`.
- Do not update the calibration-oriented `extracted-cluster-evidence/global-summary.md`.
- Java 25 remains the repository requirement, but no Java or Gradle command is part of this documentation-only extraction.

## Governing Documents

- Design/profile: `25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-observational-extraction-profile.md`
- Processing protocol: `25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-processing-protocol.md`
- Artifact atlas: `25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-atlas.md`
- Agent rules: `25083-improve-reconnectbench/evidence-and-calibration/agentic-evidence-extraction-strategy.md`
- Manifest: `25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-manifest.md`

## Shared Handoff Interfaces

### `RunAnchors`

The run-anchor worker returns this logical record to the lead:

```text
observedMode: string with source reference
learnerNodeId: integer with source reference
learnerPod: string with source reference
iterations: ordered list of
  index: integer
  teacherNodeId: integer with source reference
  learnerStartUtc: timestamp with source reference
  learnerEndUtc: timestamp or missing/ambiguous status
  learnerFinishStatus: string or missing/ambiguous status
  teacherStartUtc: timestamp or missing/ambiguous status
  teacherEndUtc: timestamp or missing/ambiguous status
activeUtc: timestamp or missing/ambiguous status
postRecoveryCoverageEndUtc: timestamp with source reference
laterStatusEvents: ordered sourced list
```

### `EvidenceFragment`

Every evidence worker returns Markdown-ready rows or blocks with this logical shape:

```text
evidenceItem: string
status: present | derived | missing | ambiguous | not_applicable
valueOrObservation: string when status permits
sourceReferences: run-root-relative verification handles when status permits
methodOrReason: derivation method, ambiguity reason, missing search scope, or not-applicable reason
```

### Lead-Owned Files

```text
25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/reconnect-run.md
25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/verification-notes.md
25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/extraction-summary.md
```

---

### Task 1: Register The Observational Collection

**Files:**

- Modify: `25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-manifest.md`
- Inspect only: `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/16-07-2026-1B/**`

**Interfaces:**

- Consumes: the user-supplied raw artifact root and approved observational profile.
- Produces: manifest collection ID `2026-07-16-1b-observational`, run ID `reconnect-run`, authoritative `runRoot`, `podLogRoot`, workflow-log root, expected learner context, and output path.

- [ ] **Step 1: Verify the collection shape without extracting reconnect evidence**

Run:

```bash
find /Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/16-07-2026-1B -maxdepth 2 -type f -print
find /Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/16-07-2026-1B/podlog_solo-mdlt-n12 -maxdepth 2 -type d -name 'network-node*_logs' -print
```

Expected: top-level `version_run.txt`, `client.log`, `pod_state.txt`, seven `network-node*-0_network_sampler.log` files, and seven `network-node*_logs` directories under `podlog_solo-mdlt-n12`.

- [ ] **Step 2: Add the manifest collection row**

Add this row to the top-level batches table:

```markdown
| `2026-07-16-1b-observational` | pending | Single-run, approximately one-billion-record observational reconnect extraction with SocketFactory and focused `ss -tinm` evidence. | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/16-07-2026-1B` | `extracted-cluster-evidence/2026-07-16-1b-observational/` |
```

- [ ] **Step 3: Add the collection section and run entry**

Add a section that records:

```markdown
## 2026-07-16 1B Observational Reconnect

### Collection Context

- This is one observational reconnect run, not a traversal-order comparison batch.
- The intended state scale is approximately one billion VirtualMap records; extraction must report observed path-range and stats evidence rather than treating the intended scale as observed fact.
- `version_run.txt` is expected to contain `inputs.NLG_Accounts=300000000` and producing commit `09f7ef40e031fc3e1a06db6f7db5e7dcfe9abc73`; extraction must verify both from the artifact.
- The intended learner is `network-node1-0` / node `0`; extraction must verify the learner from reconnect lifecycle logs.
- SocketFactory pre/post bind/connect telemetry is expected from the producing branch; extraction must source observed values from node logs.
- Seven passive sampler files are expected to contain `ss -tinm` output; extraction must verify `skmem` presence and exact reconnect-window coverage.
- Calibration acceptance, traversal ordering, local parameter mapping, and historical comparison are not applicable.

### Run Entry

| Run ID | Purpose | `runRoot` | `podLogRoot` | Workflow log root | Expected learner | Output file | Status |
|---|---|---|---|---|---|---|---:|
| `reconnect-run` | Single-run large-state observational reconnect extraction | `.` | `podlog_solo-mdlt-n12` | `.` | `network-node1-0` / node `0` | `extracted-cluster-evidence/2026-07-16-1b-observational/reconnect-run.md` | pending |

### Collection Outputs

| Output | Path |
|---|---|
| Run extraction | `extracted-cluster-evidence/2026-07-16-1b-observational/reconnect-run.md` |
| Verification notes | `extracted-cluster-evidence/2026-07-16-1b-observational/verification-notes.md` |
| Extraction summary | `extracted-cluster-evidence/2026-07-16-1b-observational/extraction-summary.md` |
```

- [ ] **Step 4: Validate and commit the manifest registration**

Run:

```bash
rg -n '2026-07-16-1b-observational|reconnect-run|16-07-2026-1B' 25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-manifest.md
git diff --check -- 25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-manifest.md
git add 25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-manifest.md
git commit -m "docs: register 1b observational reconnect artifact"
```

Expected: one collection row, one run row, three output mappings, no whitespace errors, and a commit containing only the manifest.

---

### Task 2: Run The Network Disease Preflight

**Files:**

- Inspect only: manifest-resolved `podLogRoot/network-node*_logs/swirlds.log`
- Modify: none

**Interfaces:**

- Consumes: manifest collection/run entry.
- Produces: per-node symptom counts, first examples, status-transition context, and one canonical preflight result `EvidenceFragment` for lead assembly.

- [ ] **Step 1: Invoke the parallel-agent dispatch guardrail**

Use `superpowers:dispatching-parallel-agents` before spawning the preflight worker. This task uses one bounded worker, but the skill establishes the extraction topology required by the governing strategy.

- [ ] **Step 2: Dispatch a dedicated preflight worker**

Use this prompt, resolving paths from the manifest:

```text
You are the network-disease-preflight worker for manifest collection 2026-07-16-1b-observational, run reconnect-run.
Read the observational profile, processing protocol, artifact atlas, and agentic extraction strategy.
Stay inside the manifest-resolved run root. Search every available network-node*_logs/swirlds.log for:
- post-startup ACTIVE -> CHECKING text and JSON transitions;
- CHECKING -> ACTIVE recovery context;
- Shadowgraph: Missing non-expired other parent.
Ignore normal startup OBSERVING -> CHECKING -> ACTIVE. Return per-node compact counts, the first narrow source reference for each found symptom family, missing-log records, and one canonical derived preflight result. Fatal disease does not stop this observational extraction. Do not edit files and do not extract reconnect timing or other evidence families.
```

- [ ] **Step 3: Lead spot-check the returned findings**

Run:

```bash
rg -n -m 5 'StatusStateMachine: Platform spent .* in ACTIVE\. Now in CHECKING|"oldStatus":"ACTIVE","newStatus":"CHECKING"|StatusStateMachine: Platform spent .* in CHECKING\. Now in ACTIVE|"oldStatus":"CHECKING","newStatus":"ACTIVE"|Shadowgraph: Missing non-expired other parent' /Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/16-07-2026-1B/podlog_solo-mdlt-n12/network-node*_logs/swirlds.log
```

Expected: the lead can reproduce every worker count/example or changes the relevant record to `ambiguous` and invokes `superpowers:systematic-debugging` before continuing.

---

### Task 3: Anchor Reconnect Episodes, Iterations, And Roles

**Files:**

- Inspect only: manifest-resolved node `swirlds.log` files and relevant stats CSV lifecycle columns.
- Modify: none

**Interfaces:**

- Consumes: manifest run entry and preflight result.
- Produces: one complete `RunAnchors` record, including post-recovery coverage end and later status events.

- [ ] **Step 1: Dispatch a fresh run-anchor worker**

Use this prompt:

```text
You are the run-anchor worker for manifest collection 2026-07-16-1b-observational, run reconnect-run.
Read the governing observational profile, protocol, atlas, and agentic strategy. Stay inside this run root.
Identify only: observed virtualMap.reconnectMode; learner node; every learner receiver reconnect start and finish; otherNodeId/teacher per iteration; matching teacher sender windows; learner status transitions through later ACTIVE; any later ACTIVE -> CHECKING, fall-behind, reconnect, shutdown, or fatal status event through log coverage end. Cross-check lifecycle stats columns only when useful.
Return the exact RunAnchors interface defined in the execution plan with narrow run-root-relative source references and canonical missing/ambiguous records. Do not extract counters, state/workload evidence, or passive network statistics. Do not edit files.
```

- [ ] **Step 2: Verify iteration pairing and episode boundaries**

Lead-run pattern scan:

```bash
rg -n 'ReconnectController: Preparing for reconnect|SELF_FALLEN_BEHIND|ReconnectStartPayload|Starting reconnect in the role of the receiver|Finished reconnect in the role of the receiver|BEHIND.*RECONNECT_COMPLETE|RECONNECT_COMPLETE.*CHECKING|Now in ACTIVE|oldStatus.*ACTIVE.*newStatus.*CHECKING' /Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/16-07-2026-1B/podlog_solo-mdlt-n12/network-node1_logs/swirlds.log
```

Expected: every reported learner start/finish/`ACTIVE`/later-status anchor resolves to a narrow line reference. If the observed learner is not node 0, rerun the scan against the worker-identified learner log and correct the expected-learner manifest context without changing raw evidence.

- [ ] **Step 3: Verify teacher matching**

For each `otherNodeId`, inspect `network-node<otherNodeId + 1>_logs/swirlds.log` for sender starts/finishes overlapping the learner window.

Expected: each iteration has one matching teacher context or an explicit `missing`/`ambiguous` status with searched log and time window.

---

### Task 4: Extract Log, Stats, And Workload Evidence In Parallel

**Files:**

- Inspect only: manifest-resolved logs, configs, `client.log`, `version_run.txt`, and stats CSVs.
- Modify: none

**Interfaces:**

- Consumes: manifest entry, preflight result, and verified `RunAnchors`.
- Produces: three isolated collections of `EvidenceFragment` records for lead assembly.

- [ ] **Step 1: Dispatch the `log-role-counter` worker**

```text
Extract learner evidence, matching teacher evidence, per-iteration learner/teacher stage context, raw ReconnectMapMetrics counters, reconnect data-usage reports, and relevant window-local reconnect errors. Use only the supplied RunAnchors and run root. Return protocol-ordered Markdown-ready EvidenceFragments with narrow source references. Compute dirty counters only with the protocol formulas and source every input. Do not extract CSV, workload/config, or passive network evidence. Do not edit files.
```

- [ ] **Step 2: Dispatch the `stats-csv` worker concurrently**

```text
Inspect CSV headers first, then target only rows around supplied reconnect starts, finishes, ACTIVE, and post-recovery coverage. Extract lifecycle cross-checks, vmap_size_state and relevant service/store snapshots, bytes_per_sec_sent* throughput context, ping_us_* RTT context, and storage/lifecycle context only where the observational profile requires it. Return observed column names, exact rows/timestamps, canonical statuses, and run-root-relative CSV references. Do not scan or summarize whole CSVs in model context. Do not edit files.
```

- [ ] **Step 3: Dispatch the `workload-config` worker concurrently**

```text
Extract commit/version context, observed traversal mode from settingsUsed.txt, namespace/network size, NLG configuration, account target, workload profile, transaction mix, transaction-rate samples around every supplied reconnect window, load continuity, and client/workflow completion or failure evidence. Treat intended manifest context only as a search guide. Return Markdown-ready EvidenceFragments with exact config keys or narrow log references. Do not extract reconnect counters, stats CSV values, or passive socket evidence. Do not edit files.
```

- [ ] **Step 4: Reconcile the three worker outputs**

Expected checks:

- producing commit and observed mode agree across artifact sources or are marked `ambiguous`;
- reconnect lifecycle cross-checks do not replace primary log anchors;
- each iteration has raw counter evidence or a documented missing search;
- state snapshots use observed CSV columns and rows;
- workload evidence is sampled before, during, and after reconnect where coverage exists;
- no worker introduces calibration or historical-comparison claims.

Invoke `superpowers:systematic-debugging` for contradictions before starting Task 5.

---

### Task 5: Extract State And Focused Socket Evidence In Parallel

**Files:**

- Inspect only: manifest-resolved learner/teacher logs, stats CSVs, configs, all seven sampler files, and all available node `swirlds.log` files.
- Modify: none

**Interfaces:**

- Consumes: verified `RunAnchors` plus reconciled Task 4 evidence.
- Produces: state/divergence, SocketFactory lifecycle, and passive-network socket-memory `EvidenceFragment` collections.

- [ ] **Step 1: Dispatch the `state-divergence` worker**

```text
Using supplied anchors plus verified log/stats/workload evidence, derive learner and teacher state sizes per iteration from path ranges and vmap_size_state, state-size gaps, teacher growth during reconnect where covered, behind duration, and coarse divergence shape. Use lastLeafPath - firstLeafPath + 1 only when both paths are sourced. Keep lifecycle/storage metrics separate from traversal/network interpretation. Return sourced EvidenceFragments and explicit gaps. Do not perform calibration mapping or historical comparison. Do not edit files.
```

- [ ] **Step 2: Dispatch the `socketfactory-lifecycle` worker concurrently**

Use the exact worker contract in the observational profile. Require:

```text
all-node swirlds.log coverage
PRE BIND and POST BIND server receive-buffer values/counts
PRE CONNECT and POST CONNECT client send/receive-buffer values/counts
paired-phase and pre/post differences by node/connection context
bounded SocketFactory exception/anomaly search
canonical statuses and narrow source references
```

Expected: source-log observations only; no values inferred from the NikitaReconnect source branch.

- [ ] **Step 3: Dispatch the `passive-network-socket-memory` worker concurrently**

Use the exact worker contract in the observational profile and supply the resolved `RunAnchors` record. Require:

```text
all-seven-sampler existence/start/end/cadence/coverage table
pod/IP and four-tuple endpoint attribution
per-iteration learner-endpoint active-socket block
per-iteration teacher-endpoint active-socket block
bounded queue, skmem, RTT, window, retransmission, and backpressure statistics
nearby control observations only when attributable and relevant
continuity decision before every cumulative-counter delta
explicit missing/ambiguous coverage and field records
```

Expected: all statistics are bounded to supplied reconnect windows; no unbounded full-file anomaly mining; rate fields are socket-behavior context only.

- [ ] **Step 4: Lead-review focused socket claims**

For every iteration, confirm:

- learner and teacher IPs match config/sampler evidence;
- the same connection is identifiable on both endpoints or the mismatch is explicit;
- every maximum/range/delta has a method and a bounded source window;
- `skmem` `rb`/`tb` values are distinguished from SocketFactory log values;
- missing coverage is not described as a zero value;
- non-teacher sockets are not expanded without a reconnect-relevant reason.

Invoke `superpowers:systematic-debugging` if endpoint attribution or cumulative-counter continuity conflicts.

---

### Task 6: Assemble The Authoritative Run Extraction

**Files:**

- Create: `25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/reconnect-run.md`
- Modify: none other

**Interfaces:**

- Consumes: preflight result, `RunAnchors`, and every reconciled worker `EvidenceFragment`.
- Produces: authoritative protocol/profile-ordered run extraction and four sourced outcome-layer rows.

- [ ] **Step 1: Create the output directory**

Run:

```bash
mkdir -p 25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational
```

- [ ] **Step 2: Assemble `reconnect-run.md` in the approved order**

Use exactly these headings:

```markdown
# 2026-07-16 1B Observational Reconnect Extraction

## Scope And Artifact Coverage
## Network Disease Preflight
## Run Context
## Reconnect Window And Roles
## Learner Evidence
## Teacher Evidence
## Reconnect Work-Shape Counters
## Network Evidence
### Metrics-Derived RTT And Throughput Context
### SocketFactory Lifecycle Telemetry
### Passive Sampler Coverage And Endpoint Mapping
### Focused Learner/Teacher `ss -tinm` Evidence
## Workload Evidence
## State And Divergence Evidence
## Reconnect Episodes And Iterations
## Observational Outcome
## Unresolved Evidence Register
```

Record the absolute artifact root exactly once under `Run Context`. Use run-root-relative paths everywhere else.

- [ ] **Step 3: Derive the four-layer outcome and overall label**

Create one canonical-status row each for:

```text
Receiver lifecycle
Platform recovery
Post-recovery stability
Workload and client outcome
```

Then apply the profile's precedence to derive exactly one overall label. The overall row must reference the four layer rows and must not claim calibration fitness or unsupported causality.

- [ ] **Step 4: Build the unresolved register**

Index only missing, ambiguous, or not-applicable items already recorded above. Do not introduce evidence or interpretation in this section.

- [ ] **Step 5: Run structural checks**

Run:

```bash
rg -n '^## ' 25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/reconnect-run.md
rg -n '/Users/' 25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/reconnect-run.md
rg -n '\| (PRESENT|DERIVED|MISSING|AMBIGUOUS|NOT_APPLICABLE) \|' 25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/reconnect-run.md
git diff --check -- 25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/reconnect-run.md
```

Expected: headings match the approved order; the absolute artifact root appears once; uppercase canonical status cells produce no matches; no whitespace errors.

- [ ] **Step 6: Commit the authoritative extraction before verification**

Run:

```bash
git add 25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/reconnect-run.md
git commit -m "docs: extract 1b observational reconnect evidence"
```

Expected: the commit contains only `reconnect-run.md`. It is explicitly subject to the fresh verification task that follows.

---

### Task 7: Verify And Correct The Run Extraction

**Files:**

- Modify when corrections are required: `25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/reconnect-run.md`
- Create: `25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/verification-notes.md`

**Interfaces:**

- Consumes: authoritative run extraction, manifest entry, raw run root, governing docs, and worker method notes.
- Produces: independent findings, lead-applied corrections, and resolved verification status.

- [ ] **Step 1: Dispatch a fresh run verifier**

The verifier must not be any extraction worker. Use this prompt:

```text
You are the fresh verifier for 2026-07-16-1b-observational/reconnect-run.md.
Read the observational profile, processing protocol, artifact atlas, agentic extraction strategy, manifest entry, run extraction, and raw artifact root. Do not edit files.
Verify: section order; source paths and narrow locators; every present/derived value; missing search scopes; ambiguous candidates; reconnect starts/finishes/durations/teacher matching; ACTIVE and post-recovery coverage; counter formulas; state/workload facts; SocketFactory phase/value counts; all-seven sampler coverage; both-endpoint active-socket attribution; bounded ss -tinm extrema/ranges/deltas; rate-field interpretation; four outcome layers; label precedence; and unresolved-register consistency.
Return findings categorized as pass, correction required, ambiguous/unresolved, and final disposition. Include replacement evidence rows when correction is required.
```

- [ ] **Step 2: Apply verifier corrections as lead**

For every failure:

- correct the value/reference;
- or change its status to `missing`/`ambiguous` with a complete record;
- rerun the affected source check;
- retain the finding and resolution for verification notes.

Use `superpowers:systematic-debugging` when the verifier reveals source contradictions rather than a transcription error.

- [ ] **Step 3: Create `verification-notes.md`**

Use exactly these sections:

```markdown
# 2026-07-16 1B Observational Reconnect Verification Notes

## Scope
## Verification Method
## Run Result
## Source Reference Failures
## Socket Attribution And Calculation Checks
## Ambiguous Or Unresolved Items
## Corrections Required
## Final Verification Status
```

Record the initial finding, correction, recheck, and final disposition. If a section has no findings, record an explicit `not_applicable` or no-findings row rather than omitting the section.

- [ ] **Step 4: Rerun final per-run checks**

Run:

```bash
git diff --check -- 25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/reconnect-run.md 25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/verification-notes.md
rg -n '\| (PRESENT|DERIVED|MISSING|AMBIGUOUS|NOT_APPLICABLE) \|' 25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/reconnect-run.md 25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/verification-notes.md
```

Expected: no whitespace errors and no uppercase canonical status cells.

---

### Task 8: Summarize And Close Out The Collection

**Files:**

- Create: `25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/extraction-summary.md`
- Modify: `25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-manifest.md`
- Modify: `25083-improve-reconnectbench/Index.md`
- Modify when corrections exist: `25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/reconnect-run.md`
- Include: `25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/verification-notes.md`

**Interfaces:**

- Consumes: verified run extraction and resolved verification notes.
- Produces: source-linked summary, manifest status `extracted`, index link, and final verified commit.

- [ ] **Step 1: Create `extraction-summary.md` only from verified run evidence**

Use exactly these sections:

```markdown
# 2026-07-16 1B Observational Reconnect Summary

## Collection Identity
## Reconnect Outcome
## Episode And Iteration Metrics
## Large-State And Work-Shape Evidence
## SocketFactory Findings
## Focused `ss -tinm` Findings
## Coverage Limitations And Unresolved Evidence
## Verification Status
```

Every summary row points to the relevant `reconnect-run.md` section. Only the verification-status section points to `verification-notes.md`. Do not copy the absolute raw artifact root into the summary.

- [ ] **Step 2: Close the manifest entry**

Change both the collection and run statuses from `pending` to `extracted`. Do not use `accepted` or `rejected`.

- [ ] **Step 3: Add the extraction summary to the task index**

Add a link under Evidence And Calibration describing it as a single-run observational large-state extraction with reconnect outcome and focused socket evidence. Update the index date to the completion date if it differs.

- [ ] **Step 4: Run cross-file structural verification**

Run:

```bash
rg -n 'accepted for calibration|rejected for calibration|Calibration Inputs|Traversal Ordering Summary|historical comparison' 25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational
rg -n '2026-07-16-1b-observational|reconnect-run' 25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-manifest.md
rg -n '2026-07-16 1B Observational Reconnect Summary' 25083-improve-reconnectbench/Index.md
git diff --check -- 25083-improve-reconnectbench/Index.md 25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-manifest.md 25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational
git status --short
```

Expected: prohibited calibration/comparison phrases produce no matches; manifest has one collection and one run entry with `extracted`; index contains the summary link; no whitespace errors; unrelated user files remain unstaged and untouched.

- [ ] **Step 5: Invoke verification-before-completion**

Use `superpowers:verification-before-completion`. Re-run its required evidence checks after all corrections and inspect the fresh output before claiming completion.

- [ ] **Step 6: Stage only the extraction closeout files**

Run:

```bash
git add 25083-improve-reconnectbench/Index.md 25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-manifest.md 25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/reconnect-run.md 25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/verification-notes.md 25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/extraction-summary.md
git diff --cached --check
git diff --cached --name-only
```

Expected staged paths: the index, manifest, verification notes, and extraction summary, plus `reconnect-run.md` only when the verifier caused a correction. No other paths are staged.

- [ ] **Step 7: Commit the verified closeout**

Run:

```bash
git commit -m "docs: verify 1b observational reconnect extraction"
```

Expected: the commit contains verification notes, extraction summary, manifest closeout, task-index link, and any verifier-driven run-report corrections.

---

## Final Deliverables

The execution is complete only when all of these statements are freshly verified:

- manifest collection `2026-07-16-1b-observational` is `extracted`;
- `reconnect-run.md` contains every approved section in order;
- every required evidence item has a canonical status;
- the four outcome layers and overall label are sourced;
- SocketFactory lifecycle evidence covers every available node log;
- sampler coverage covers all seven sampler files and deep analysis stays focused on learner/teacher reconnect sockets;
- `ss -tinm` evidence is window-bounded or explicitly unbounded/ambiguous;
- fresh verification is resolved and recorded;
- `extraction-summary.md` introduces no new evidence;
- `Index.md` links the profile and extraction summary;
- the calibration global summary is unchanged;
- no production files or unrelated user files were modified or staged.
