# Cluster ReconnectBench Observational Extraction Profile

Status: `design-approved; extraction not started`

Updated: `2026-07-17`

## Purpose

This profile defines a reusable extraction path for a collected cluster ReconnectBench artifact that contains one
large-state reconnect run rather than a traversal-order comparison batch.

The output is a factual, source-referenced account of whether and how the reconnect completed, what work it performed,
and what network and socket behavior was observed. It is not a calibration pass.

The first intended application is the approximately one-billion-record collection identified by the future manifest
batch ID `2026-07-16-1b-observational`. The manifest owns the concrete raw artifact root and pod-log root; do not
duplicate those paths here.

## Relationship To Existing Extraction Documents

Follow this profile together with:

- [Cluster ReconnectBench Artifact Processing Protocol](cluster-reconnectbench-artifact-processing-protocol.md);
- [Cluster ReconnectBench Artifact Atlas](cluster-reconnectbench-artifact-atlas.md);
- [Agentic Evidence Extraction Strategy](agentic-evidence-extraction-strategy.md);
- [Cluster ReconnectBench Artifact Manifest](cluster-reconnectbench-artifact-manifest.md).

The existing documents remain authoritative for shared mechanics, including source references, evidence statuses,
reconnect anchoring, artifact-family source maps, bounded handling of large files, worker isolation, and independent
verification. This profile overrides them only where it explicitly defines a different observational rule.

## Applicability

Use this profile when all of the following are true:

- the collected artifact contains one reconnect run or otherwise has no traversal-order comparison set;
- the primary question is whether and how reconnect completed at the observed state scale;
- the requested output is an observational extraction rather than local ReconnectBench calibration;
- passive `ss -tinm` socket-memory telemetry and/or SocketFactory lifecycle telemetry are relevant evidence.

Do not use this profile to rank traversal modes, declare a winning mode, select local benchmark parameters, or decide
whether a run is accepted for calibration.

## Observational Overrides

| Existing calibration-oriented rule | Observational rule |
|---|---|
| A batch normally supports traversal comparison. | A collection with one run is valid. |
| Fatal network disease stops normal calibration extraction. | Record the preflight result and continue extracting evidence needed to explain the observed outcome. |
| Produce `batch-summary.md`. | Produce `extraction-summary.md`. |
| Produce `Analysis Output Per Mode`. | Produce `Observational Outcome`. |
| Decide whether the run is accepted for calibration. | Calibration acceptance is `not_applicable`. |
| Map evidence to local ReconnectBench inputs. | Do not recommend calibration inputs or sweeps. |
| Summarize traversal ordering. | Traversal ordering is `not_applicable`. |

## Required Output Contract

Create one directory:

```text
25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/<observational-id>/
```

Create exactly these durable extraction outputs in that directory:

```text
reconnect-run.md
verification-notes.md
extraction-summary.md
```

`reconnect-run.md` is the source of truth. `extraction-summary.md` is a concise observational summary and must not
introduce values or conclusions absent from the per-run file. `verification-notes.md` records the independent source
audit and corrections.

Do not add JSON, JSONL, generated CSV, schema files, or other permanent machine-ingestion intermediates. Temporary
bounded command output may be used outside the documentation tree during extraction and must not become a repository
deliverable.

The cluster artifact manifest remains the discovery index for the collection. The calibration-oriented global summary
must not be updated for an observational extraction.

## Processing Sequence

Process the collection in this order:

0. Add the collection and its one run to the artifact manifest with status `pending`.
1. Inventory artifact families and coverage without loading full large logs, CSVs, or sampler files into model context.
2. Run the network disease preflight across every available node `swirlds.log`.
3. Anchor the learner timeline, reconnect iterations, teacher roles, later `ACTIVE`, and post-recovery coverage.
4. Extract the standard reconnect evidence families from the existing protocol.
5. Extract SocketFactory lifecycle buffer telemetry from every available node `swirlds.log`.
6. After reconnect anchors are fixed, extract focused, window-bounded `ss -tinm` evidence.
7. Derive the four independent reconnect outcome layers and the evidence-bounded overall conclusion.
8. Assemble `reconnect-run.md`.
9. Run fresh independent verification, correct the extraction where required, and write `verification-notes.md`.
10. Write `extraction-summary.md` exclusively from verified per-run evidence.
11. Change the manifest run and collection statuses from `pending` to `extracted` and add task-index links.

If fatal network disease is found, continue this sequence. The disease evidence affects the outcome description but
does not turn the run into calibration evidence.

## Standard Reconnect Evidence

Retain the protocol's standard reconnect evidence where available:

- observed traversal mode and commit;
- learner identity and teacher identity per reconnect iteration;
- receiver reconnect start, finish, duration, and finish status per iteration;
- complete catch-up duration and finish-to-`ACTIVE` interval;
- learner and teacher state size evidence;
- reconnect transfer and clean/dirty work-shape counters;
- workload profile, rate, mix, and whether load continued during reconnect;
- RTT and throughput context;
- TCP window and backpressure behavior;
- state/divergence classification;
- network disease symptoms and relevant failure evidence.

Use window-local error, exception, GC, JVM, or fallback evidence only when required to explain an incomplete or unstable
outcome. Do not turn the pass into an unbounded general health audit.

## SocketFactory Lifecycle Evidence

SocketFactory lifecycle evidence is mandatory for this profile when the producing commit contains the telemetry.

For every available node `swirlds.log`, extract:

- pre-bind server receive-buffer size;
- post-bind server receive-buffer size;
- pre-connect client send-buffer size;
- pre-connect client receive-buffer size;
- post-connect client send-buffer size;
- post-connect client receive-buffer size.

Report per node and lifecycle phase:

- unique observed values;
- occurrence count;
- whether the expected paired phase is present;
- pre/post differences;
- missing phases;
- unexpected values or exceptions.

Do not treat the absence of client lifecycle lines on a node as a failure without first checking whether the node
initiated relevant client connections. The artifact logs, rather than the source branch or intended configuration, are
the evidence for observed buffer values.

## Focused `ss -tinm` Evidence

### Coverage And Attribution

Inventory all node sampler files and record:

- file existence;
- capture start and end;
- approximate sampling cadence;
- whether each reconnect iteration and the subsequent `ACTIVE` transition are covered;
- local pod/IP attribution;
- learner/teacher socket attribution by endpoint and reconnect iteration.

Use all seven samplers for coverage and endpoint mapping. Deep analysis is limited to learner-to-teacher reconnect
sockets on both endpoints. Non-teacher sockets provide nearby idle/control context and are expanded only when they show
an anomaly relevant to the reconnect outcome.

### Active-Socket Fields

For each attributed learner/teacher socket and bounded reconnect window, extract only fields that answer reconnect
network questions:

- `Recv-Q` and `Send-Q`;
- `skmem` fields `r`, `rb`, `t`, `tb`, `f`, `w`, `o`, `bl`, and `d`;
- `rtt` and `minrtt`;
- `cwnd`, `ssthresh`, `rcv_space`, and `snd_wnd`;
- `bytes_sent`, `bytes_retrans`, `bytes_acked`, and `bytes_received`;
- `unacked`, `notsent`, and `rwnd_limited` when emitted;
- `send`, `pacing_rate`, and `delivery_rate` only as observed socket behavior.

Use bounded statistics appropriate to the field:

- queue and allocated/queued memory peaks, with nearby typical or idle values when available;
- buffer-cap and RTT ranges;
- window/backpressure observations;
- first/last values and deltas for cumulative counters only when the same socket four-tuple and continuity are
  established.

If socket continuity is not established, report cumulative counters as point observations or maxima and mark any delta
as `ambiguous`. Never claim sampler `send`, `pacing_rate`, or `delivery_rate` as link capacity.

Each derived statistic must state its bounded window and method and must carry precise verification handles for the
source window and relevant extrema or representative observations.

## Reconnect Outcome Model

Report four independent outcome layers.

### 1. Receiver Lifecycle

Determine whether every observed learner receiver reconnect start has a matching finish. Report iteration duration,
finish status, and unmatched starts or finishes.

### 2. Platform Recovery

Determine whether the learner subsequently reached `ACTIVE`. Report the final receiver finish-to-`ACTIVE` interval
separately from complete catch-up duration.

### 3. Post-Recovery Stability

From the confirming `ACTIVE` transition through the end of available coverage, report:

- observed stable duration;
- later `ACTIVE -> CHECKING` transitions;
- later fall-behind or reconnect activity;
- shutdowns or fatal errors relevant to recovery stability.

### 4. Workload And Client Outcome

Determine whether load continued during reconnect and whether the client/workflow completed, stopped, or emitted a
reconnect-related failure.

### Overall Observational Conclusion

Use exactly one evidence-bounded label:

```text
completed_and_stable_in_observed_window
completed_but_post_reconnect_instability_observed
receiver_completed_without_active_confirmation
reconnect_incomplete_or_failed
indeterminate_due_to_evidence_gap
```

Apply the labels with this precedence:

1. Use `reconnect_incomplete_or_failed` when a receiver lifecycle failure is observed or a reconnect start remains
   unmatched despite sufficient log coverage.
2. Use `receiver_completed_without_active_confirmation` when the receiver lifecycle completes but no later learner
   `ACTIVE` is present in otherwise sufficient status-log coverage.
3. Use `completed_but_post_reconnect_instability_observed` when receiver completion and later `ACTIVE` are present but
   subsequent platform instability, another reconnect episode, or a reconnect-related client/workflow failure is
   observed.
4. Use `completed_and_stable_in_observed_window` when receiver completion and later `ACTIVE` are present, no later
   instability is observed through the documented coverage end, and no reconnect-related client/workflow failure is
   present.
5. Use `indeterminate_due_to_evidence_gap` when missing or ambiguous evidence prevents the preceding determinations.

Each outcome layer must carry a canonical evidence status plus its outcome value and source references. The overall
label must cite the four layer rows from which it is derived.

The conclusion must not imply calibration fitness or unsupported causality.

## `reconnect-run.md` Section Order

Use this section order:

```text
Scope And Artifact Coverage
Network Disease Preflight
Run Context
Reconnect Window And Roles
Learner Evidence
Teacher Evidence
Reconnect Work-Shape Counters
Network Evidence
  Metrics-Derived RTT And Throughput Context
  SocketFactory Lifecycle Telemetry
  Passive Sampler Coverage And Endpoint Mapping
  Focused Learner/Teacher ss -tinm Evidence
Workload Evidence
State And Divergence Evidence
Reconnect Episodes And Iterations
Observational Outcome
Unresolved Evidence Register
```

Every protocol-required or profile-required evidence item must use the evidence status policy from the agentic strategy.
The unresolved register may index only missing, ambiguous, or not-applicable items already recorded in the ordered
sections.

## `extraction-summary.md` Contract

Include only:

- collection identity and observed traversal mode;
- four-layer reconnect outcome and overall observational conclusion;
- episode and iteration headline metrics;
- observed large-state and work-shape facts;
- SocketFactory and focused `ss -tinm` headline findings;
- coverage limitations and unresolved evidence;
- a link to the authoritative per-run file and verification notes.

Do not include calibration acceptance, calibration-input recommendations, traversal ranking, historical comparison, or
cross-run causal claims.

## Missing, Conflicting, Or Unbounded Evidence

Apply these rules:

- Record absent evidence as `missing` with the exact files and patterns or columns checked.
- Record conflicting candidates as `ambiguous` with a source reference for every candidate.
- If no trustworthy reconnect window can be anchored, SocketFactory lifecycle evidence may still be extracted, but
  `ss -tinm` evidence must be marked unbounded and unsuitable for reconnect attribution. Do not mine the full sampler as
  a substitute.
- An incomplete reconnect still receives a failure-oriented report using every safely attributable evidence family.
- Contradictions trigger a systematic source audit before assembly.

## Agent Topology

Retain the phase-split topology from the agentic extraction strategy:

```text
Lead Agent
Network Disease Preflight Worker
Run Anchor Worker
Run-Scoped Evidence Workers
Lead Assembly
Fresh Run Verifier
Lead Corrections And Summary
```

The profile adds two focused worker scopes:

```text
socketfactory-lifecycle worker: all-node lifecycle log telemetry
passive-network-socket-memory worker: post-anchor, window-bounded ss -tinm evidence
```

Use separate worker turns for these scopes so large sampler results do not contaminate the lifecycle-log context. Keep
log/counter, stats CSV, workload/config, and state/divergence work isolated according to the existing strategy.

Only the lead agent writes final Markdown files. Extraction workers and the verifier must not edit them directly.

## Verification Requirements

Fresh verification must reproduce or spot-check:

- all reconnect lifecycle anchors and duration calculations;
- learner/teacher role and endpoint attribution per iteration;
- all source-reference locations and evidence statuses;
- reconnect work-shape values and derived dirty counters;
- state-size and workload observations;
- sampler coverage boundaries and active-socket attribution;
- SocketFactory phase pairing, values, and counts;
- representative and extreme `ss -tinm` observations;
- cumulative-counter deltas only where socket continuity supports them;
- every outcome-layer status and the overall conclusion;
- absence of unsourced values or conclusions in `extraction-summary.md`.

Record corrections and the final result in `verification-notes.md`. Do not finalize the extraction summary until
verification failures are corrected or explicitly retained as unresolved evidence.

## Completion Conditions

The observational extraction is complete only when:

- all three required output files exist;
- every required evidence item has a canonical evidence status;
- the four outcome layers and overall conclusion are sourced;
- focused socket evidence is bounded to reconnect windows or explicitly marked unbounded;
- independent verification is complete;
- the extraction summary contains no new evidence;
- the manifest status is `extracted`;
- the task index links the profile and extraction summary.
