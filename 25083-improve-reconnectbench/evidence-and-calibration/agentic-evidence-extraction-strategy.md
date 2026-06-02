# Agentic Evidence Extraction Strategy

Status: `active`
Updated: `2026-06-02`

## Purpose

Use this document as the operating procedure for extracting cluster ReconnectBench evidence.

Follow it together with `cluster-reconnectbench-artifact-processing-protocol.md` and
`cluster-reconnectbench-artifact-atlas.md`.

Do not extract values into this file.

## Output Requirements

Use Markdown-only extraction artifacts.

Do not create JSON, JSONL, generated schema files, or machine-ingestion output for this phase.

## Required Output Directory

```text
25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/
```

## Required Files

```text
top-to-bottom.md
two-phase-pessimistic.md
parallel-sync.md
verification-notes.md
cluster-calibration-summary.md
```

## Per-Run File Order

Each per-run extraction file must keep protocol-order evidence sections first:

```text
Run Context
Reconnect Window And Roles
Learner Evidence
Teacher Evidence
Reconnect Work-Shape Counters
Network Evidence
Workload Evidence
State And Divergence Evidence
Later Reconnects
Analysis Output Per Mode
Acceptance Notes
Unresolved Evidence Register
```

`Unresolved Evidence Register` is an index of missing, ambiguous, or not-applicable items already recorded in the
protocol-order sections. It must not introduce new evidence.

## Per-Run Source Of Truth

Per-run files are authoritative.

`cluster-calibration-summary.md` is only a comparison layer. It must link or point back to per-run evidence sections and
must not introduce unsourced values.

## Source Reference Rule

Every extracted value must include a source reference in the same row or evidence block.

Use the most precise available locator:

```text
plain log: relative path + line range
metrics CSV: relative path + column name + row number or timestamp key
config file: relative path + key or line range
passive sampler log: relative path + narrow line range + bounded window when available
derived value: formula + source references for all inputs
```

## Missing Evidence Rule

Missing, ambiguous, or not-applicable evidence must be recorded explicitly in the per-run file.

Use the canonical status record in `Evidence Status Policy`.

## Verification Output Rule

Require `verification-notes.md`.

Use it for post-extraction source-reference verification findings.

`cluster-calibration-summary.md` must summarize verification status, but detailed verification findings belong in
`verification-notes.md`.

## Agent Topology

Use run-scoped, phase-split agents.

Do not use one long-lived agent to extract an entire run.

Do not use global artifact-family agents across all runs.

## Topology

```text
Lead Agent
Run Anchor Agents, one per run
Run-Scoped Evidence Workers, respawned per run and evidence family
Lead Assembly
Required Verification
```

## Lead Agent Responsibilities

```text
resolve run roots from the atlas and record them in per-run Run Context
construct focused worker prompts
provide protocol, atlas, strategy, run root, and known anchors
prevent cross-run evidence mixing
assemble final Markdown files
record unresolved evidence
perform or coordinate required verification
```

Only the lead agent writes final extraction files.

Worker agents return Markdown fragments or concise findings with source references.

## Run Anchor Agents

Spawn one anchor agent per run.

Each anchor agent identifies only run-local anchors:

```text
traversal mode
learner node
teacher peer
first learner reconnect window
matching teacher window
later reconnect starts
```

Anchor agents must not extract full evidence families.

## Run-Scoped Evidence Workers

After anchors are known, spawn short-lived workers by run and evidence family.

Allowed worker families:

```text
log-role-counter worker
stats-csv worker
passive-network worker
workload-config worker
state-divergence worker
```

Each worker receives exactly one run root.

Each worker must stay inside that run root.

Each worker must return only protocol-required evidence, missing/ambiguous notes, and source references.

## Context Rule

Never load full large logs, CSVs, or sampler files into model context.

Use shell tools to locate narrow excerpts:

```text
rg
awk
sed
csv header and column targeting
line ranges
timestamp-window searches
```

Pass only:

```text
extracted values
observed column names
line ranges
timestamp keys
small source excerpts
search commands when useful
```

Respawn workers between evidence families when context would otherwise become noisy.

## Disallowed Topologies

Do not use:

```text
single long-lived all-run extractor
single long-lived per-run extractor
global log agent across all runs
global CSV agent across all runs
global network agent across all runs
workers that write final Markdown files directly
```

## Source Reference Format

Source references are mandatory for every extracted value.

Source references are verification handles. A later agent or human must be able to re-check the value without repeating
the full extraction.

## Source Reference Scope

Use run-root-relative paths in extraction rows.

Record the absolute artifact run root once in each per-run file under `Run Context`.

Do not repeat absolute artifact paths in every evidence row.

## Source Reference Placement

Keep the source reference in the same table row or evidence block as the extracted value.

If multiple sources support a value, list the primary source first and cross-check sources after it.

## Source Reference Forms

Use the most precise available locator:

```text
log:<relative-path>:<line-start>-<line-end>
csv:<relative-path>:column=<observed-column>;row=<row-number>
csv:<relative-path>:column=<observed-column>;timestamp=<timestamp>
config:<relative-path>:key=<key-or-setting>
sampler:<relative-path>:<line-start>-<line-end>;window=<start>..<end>
derived:formula=<rule>;inputs=<source-ref>/<source-ref>
```

## Source Reference Rules

```text
paths must stay inside the run root
CSV column names must be observed artifact column names
line ranges must be narrow
sampler references must include the bounded reconnect window when available
search commands are supplemental search-scope notes, not source references
derived values must cite all input source references
historical docs may guide search but cannot be source references for current extracted values
```

## Missing Or Ambiguous Evidence

Missing, ambiguous, or not-applicable evidence does not require a value source reference.

Use the canonical status record in `Evidence Status Policy`.

## Evidence Status Policy

Every protocol-required evidence item must end with exactly one status.

Allowed statuses:

```text
present
missing
ambiguous
not_applicable
derived
```

## Status Definitions

```text
present: value found with a valid source reference
missing: expected evidence not found after documented search
ambiguous: multiple plausible or conflicting values and no protocol precedence resolves them
not_applicable: protocol item does not apply to this run or artifact shape
derived: value computed from sourced inputs using a protocol-approved formula
```

## Canonical Status Record

Use this shape for every evidence item:

| Status | Required fields |
| --- | --- |
| `present` | `evidence item`, `status`, `value or observation`, `source references` |
| `derived` | `evidence item`, `status`, `value or observation`, `source references`, `reason=<formula or rule>` |
| `missing` | `evidence item`, `status`, `search scope`, `files checked`, `patterns or columns checked`, `reason` |
| `ambiguous` | `evidence item`, `status`, `value or observation=<candidate values>`, `source references=<source reference for each candidate>`, `reason=<why protocol cannot choose one>` |
| `not_applicable` | `evidence item`, `status`, `reason` |

## Inference Rule

Do not infer missing values from:

```text
intended run shape
historical docs
other traversal runs
local benchmark expectations
agent memory
```

## Passive Network Handling

Passive network extraction must run after reconnect anchors are known.

Passive network workers are run-scoped.

Primary purpose:

```text
TCP/window/backpressure evidence for networkInflightBytesLimit
```

Allowed secondary use:

```text
RTT or minRTT socket evidence, if linked to learner/teacher during reconnect
observed send, pacing, or delivery rate as throughput context
retransmission, unacked, notsent, Recv-Q, Send-Q, rwnd_limited, snd_wnd, cwnd, and ssthresh for in-flight or backpressure interpretation
```

Only extract sampler fields when they answer a protocol network question:

```text
RTT evidence present?
Bandwidth or throughput evidence present?
TCP/window evidence present?
```

## Passive Network Worker Inputs

Each passive network worker receives:

```text
run root
learner node
teacher node
learner reconnect window
matching teacher window when available
known learner/teacher peer linkage
```

If learner/teacher sockets cannot be linked to the reconnect window, record the evidence as `ambiguous` or `missing`.

## Passive Network Source Priority

Use network sources in this order:

```text
direct learner/teacher socket RTT or minRTT from bounded ss -tin samples, if linkable to reconnect window
stats CSV ping_us_* and ping_us_*MIN for RTT evidence when direct socket RTT is unavailable or as cross-check
stats CSV bytes_per_sec_sent* for throughput evidence
per-node network_sampler_network-node<N>-0.log files for TCP/window/backpressure evidence
reconnect_network_samples_1_summary.log only when present and bounded by timestamp/window
reconnect_network_samples_1.log only if non-empty and relevant
```

Observed artifact note:

```text
reconnect_network_samples_1.log may be empty or only contain sampler stop text
reconnect_network_samples_1_summary.log may be raw combined ss -tin output, not a compact summary
per-node network_sampler_network-node<N>-0.log files contain timestamped ss -tin samples
```

## Passive Network Output Shape

The passive network worker returns evidence blocks, not a TCP field dump.

Required targets:

```text
TCP/window/backpressure
RTT, if sampler evidence is relevant or needed as cross-check
throughput context, if sampler evidence is relevant or needed as cross-check
```

Each output block must include:

```text
evidence target
status
value or observation
source reference
interpretation note limited to local benchmark network setting relevance
```

## Passive Network Guardrails

Do not:

```text
mine unrelated TCP anomalies
summarize full sampler files
compare network behavior globally across runs during extraction
claim ss send or delivery_rate is link capacity
use active bandwidth-generator evidence as reconnect-window evidence
use reconnect MiB divided by synchronization seconds as link capacity
```

`ss -tin` sampler rates are observed socket behavior only. They may support throughput context, but not true cluster
link capacity.

## Verification Requirements

Verification is a required post-extraction phase.

Use fresh verifier subagents.

Do not reuse extraction workers as verifiers.

## Verification Topology

```text
Lead Agent
Run-Scoped Extraction Workers
Lead Assembly
Run Verifier Agents, one per per-run extraction file
Lead Corrections
Final Verification Summary
```

## Run Verifier Agents

Spawn one verifier per run file:

```text
top-to-bottom.md verifier
two-phase-pessimistic.md verifier
parallel-sync.md verifier
```

Each verifier receives:

```text
strategy document
protocol document
atlas document
one per-run extraction file
that file's artifact run root
```

Each verifier checks the per-run file against the artifact sources.

Verifier agents do not edit extraction files directly.

Verifier agents return findings to the lead agent.

The lead agent applies corrections and records verification results.

## Verification Notes File

`verification-notes.md` must contain:

```text
Scope
Verification Method
Per-Run Results
Source Reference Failures
Ambiguous Or Unresolved Items
Corrections Required
Final Verification Status
```

## Required Verification Checks

Each verifier checks:

```text
source path stays inside the run root
source file exists
line range, CSV column, row, timestamp, or config key resolves
source content supports extracted value
present values have source references
derived values cite protocol-approved formulas and all input source references
missing evidence records files or patterns checked and reason
ambiguous evidence records candidate values and source references
passive network evidence is bounded to reconnect window or marked ambiguous
ss -tin send or delivery_rate is not claimed as link capacity
summary values do not introduce unsourced values
```

## Verification Failure Handling

If verification fails:

```text
record finding in verification-notes.md
correct the per-run extraction file or mark the item ambiguous/missing
rerun the affected verification check
do not finalize cluster-calibration-summary.md until failures are resolved or explicitly recorded
```

## Final Summary Shape

`cluster-calibration-summary.md` is a comparison and calibration-input layer only.

It is not the source of truth.

It must not introduce values that are absent from per-run files.

Every evidence or calibration row must point back to the per-run file section.

Only verification-status rows may point to `verification-notes.md`.

## Required Summary Sections

`cluster-calibration-summary.md` must contain:

```text
Scope
Run Mapping
Verification Status
Per-Mode Acceptance Summary
Cluster Network Evidence Summary
State And Divergence Summary
Reconnect Work-Shape Summary
Traversal Ordering Summary
Calibration Inputs For Local ReconnectBench
Remaining Gaps
```

## Per-Mode Acceptance Summary

The primary comparison table must mirror protocol `Analysis Output Per Mode`.

Required columns:

```text
mode
artifact directory
commit
learner node
teacher node
first reconnect start
first reconnect end
learner duration
teacher reconnect context present
reconnect stats present
teacher/learner state size present
workload profile present
RTT evidence present
bandwidth evidence present
TCP/window evidence present
later reconnects observed
accepted for calibration
reason if not accepted
source
```

The `source` column must point to the relevant per-run file section.

## Calibration Input Table

Include a table mapping extracted cluster evidence to local `ReconnectBench` inputs.

Required columns:

```text
cluster evidence
local ReconnectBench input
recommended value or sweep
source per-run files
confidence or gaps
```

Use protocol mapping:

```text
RTT -> networkLatencyMicroseconds
throughput or bandwidth -> networkBandwidthMegabitsPerSecond
TCP/window or backpressure -> networkInflightBytesLimit
teacher/learner state size -> numFiles * numRecords target
state gap and workload profile -> add/modify/remove probabilities or future divergence controls
clean/dirty counters -> local state-shape validation
cluster traversal ordering -> local traversal-mode ordering target
```

## Final Merge Guardrails

Do not:

```text
make causal claims
declare a winning traversal mode
average runs unless a later protocol step requires it
hide rejected or incomplete runs
resolve ambiguity that remains unresolved in per-run files
introduce unsourced values
use historical values as current-run evidence
```

## Skill Usage

Use Superpowers skills as process guardrails only.

Do not use test-driven development unless writing extraction scripts.

Do not use implementation-planning skills unless the user asks to turn this strategy into a step-by-step execution plan.

## Required During Extraction

```text
dispatching-parallel-agents: use before spawning run-scoped extraction or verifier agents
systematic-debugging: use when sources contradict, extraction output is inconsistent, or expected evidence is absent
verification-before-completion: use before claiming extraction, verification, or summary work is complete
```

## Only For Strategy Changes

```text
brainstorming: use while deciding or changing extraction strategy
requesting-code-review: use for fresh review of this strategy document or any substantial extraction-output change
```

## Skills Not Used By Default

```text
test-driven-development: not needed unless writing extraction scripts
writing-plans: not needed unless the user requests a separate execution plan
executing-plans: not needed unless executing an approved plan
```

## Execution Rule

If a skill conflicts with explicit task instructions in `AGENTS.md` or this strategy document, follow the explicit task
instructions.
