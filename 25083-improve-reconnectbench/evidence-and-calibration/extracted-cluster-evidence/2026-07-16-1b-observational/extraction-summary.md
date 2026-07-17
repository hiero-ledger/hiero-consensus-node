# 2026-07-16 1B Observational Reconnect Summary

## Collection Identity

| Evidence item | Status | Verified observation | Source |
|---|---:|---|---|
| Collection / run | present | `2026-07-16-1b-observational` / `reconnect-run`; one observational run, not a traversal-order comparison batch. | [Run Context](reconnect-run.md#run-context) |
| Mode and commit | present | `pullTopToBottom`; `09f7ef40e031fc3e1a06db6f7db5e7dcfe9abc73` (`NikitaReconnect`). | [Run Context](reconnect-run.md#run-context) |
| Learner | present | Internal node `0`, `network-node1-0`. | [Run Context](reconnect-run.md#run-context) |
| Workload target | present | 300,000,000 configured and loaded accounts; this is distinct from observed VirtualMap size. | [Workload Evidence](reconnect-run.md#workload-evidence) |

## Reconnect Outcome

| Layer | Status | Verified outcome | Source |
|---|---:|---|---|
| Receiver lifecycle | ambiguous | Iterations 1..341 finish; iteration 342 starts and remains in progress at learner-log EOF. | [Observational Outcome](reconnect-run.md#observational-outcome) |
| Platform recovery | missing | The learner never reaches `ACTIVE` in the supplied log, so the artifact does not demonstrate a passed platform recovery. | [Observational Outcome](reconnect-run.md#observational-outcome) |
| Post-recovery stability | not_applicable | There is no confirmed recovery interval to assess. | [Observational Outcome](reconnect-run.md#observational-outcome) |
| Workload/client outcome | ambiguous | Load continues through and beyond learner-log EOF; no terminal client/workflow result is supplied. | [Observational Outcome](reconnect-run.md#observational-outcome) |
| **Overall conclusion** | **derived** | **`indeterminate_due_to_evidence_gap`**. The final attempt is unmatched, but its `74.751 s` of learner coverage is shorter than the prior minimum `219.739 s` receiver lifecycle and `200.229 s` synchronization, so the artifact cannot classify that attempt as a protocol failure or establish later recovery. | [Observational Outcome](reconnect-run.md#observational-outcome) |

## Episode And Iteration Metrics

| Evidence item | Status | Verified observation | Source |
|---|---:|---|---|
| Receiver attempts | derived | 342 starts, 341 finishes; teacher distribution `59/62/53/52/66/50` across internal nodes 1..6. | [Reconnect Window And Roles](reconnect-run.md#reconnect-window-and-roles) |
| Repeated-reconnect span | derived | At least `34:02:27.015` without learner `ACTIVE`. | [Reconnect Window And Roles](reconnect-run.md#reconnect-window-and-roles) |
| Completed receiver wall time | derived | 341 durations; mean `349.650320 s`, range `219.739..1,053.714 s`, sum `119,230.759 s`. | [Learner Evidence](reconnect-run.md#learner-evidence) |
| Completed synchronization time | derived | 341 payloads; mean `331.668164 s`, range `200.229..1,034.318 s`, sum `113,098.844 s`. | [Learner Evidence](reconnect-run.md#learner-evidence) |
| Completed data usage | derived | 341 payloads; mean `2,203.476210052 MB`, range `1,289.7865762710571..21,875.007661819458 MB`, sum `751,385.387627602 MB`. | [Learner Evidence](reconnect-run.md#learner-evidence) |
| Load continuity | derived | Transaction/receipt samples remain near 10.37k..10.39k TPS across the supplied episode and continue after learner-log EOF. | [Workload Evidence](reconnect-run.md#workload-evidence) |

## Large-State And Work-Shape Evidence

| Evidence item | Status | Verified observation | Source |
|---|---:|---|---|
| Observed state scale | derived | Learner state grows `903,041,446 -> 1,212,707,746`; final sent target is `1,213,258,385`. The first target at least one billion is attempt 76. | [State And Divergence Evidence](reconnect-run.md#state-and-divergence-evidence) |
| Target gap | derived | Positive on all 342 attempts; `25,524,739` initially and `550,639` on attempt 342. | [State And Divergence Evidence](reconnect-run.md#state-and-divergence-evidence) |
| Work-shape aggregate | derived | 341 metric rows emit `33,261,254,990` transfers on each side; aggregate internal hashes are `78.132886%` clean and leaf data `77.143821%` clean. | [Reconnect Work-Shape Counters](reconnect-run.md#reconnect-work-shape-counters) |
| Growth-positive divergence | derived | Every sent target is larger than that attempt's learner state, and completed attempts contain clean and non-clean work. | [State And Divergence Evidence](reconnect-run.md#state-and-divergence-evidence) |
| Exact mutation composition | ambiguous | Append/modify/remove composition cannot be distinguished from the available counters. | [State And Divergence Evidence](reconnect-run.md#state-and-divergence-evidence) |

## SocketFactory Findings

| Evidence item | Status | Verified observation | Source |
|---|---:|---|---|
| Setter behavior | present | At the producing commit, `SocketFactory` does not set send or receive buffer sizes; it only logs Java getters around bind/connect. The observed values are OS/JVM-selected lifecycle snapshots. | [Socket-buffer comparison](reconnect-run.md#socket-buffer-comparison--lifecycle-getters-versus-live-kernel-caps) |
| Server receive buffer | derived | Seven bind pairs, uniformly `32768 -> 32768`. | [SocketFactory Lifecycle Telemetry](reconnect-run.md#socketfactory-lifecycle-telemetry) |
| Client buffers | derived | Completed connect pairs show send `32768 -> 43520` (`+10752`) and receive `32768 -> 32768`. | [SocketFactory Lifecycle Telemetry](reconnect-run.md#socketfactory-lifecycle-telemetry) |
| Lifecycle pairing | derived | 461 PRE pairs, 361 POST pairs, 100 PRE-only pairs, zero POST-without-PRE cases; PRE-only cause remains ambiguous. | [SocketFactory Lifecycle Telemetry](reconnect-run.md#socketfactory-lifecycle-telemetry) |

## Focused `ss -tinm` Findings

| Evidence item | Status | Verified observation | Source |
|---|---:|---|---|
| Memory telemetry | derived | All seven samplers contain `skmem` fields consistent with `ss -tinm`; the literal command invocation is not preserved. | [Passive Sampler Coverage And Endpoint Mapping](reconnect-run.md#passive-sampler-coverage-and-endpoint-mapping) |
| Both-endpoint coverage | derived | 315 of 342 receiver windows have full learner/active-teacher sampler spans; the last fully covered window is iteration 319. | [Passive Sampler Coverage And Endpoint Mapping](reconnect-run.md#passive-sampler-coverage-and-endpoint-mapping) |
| Buffer comparison | derived | The first learner `ss` caps normally equal twice the Java getters (`rb=65536` in 242/315 windows; `tb=87040` in 314/315), matching Linux accounting; other first samples already show growth. During transfer, learner maxima reach `rb=30,648,664` and `tb=6,162,432`, consistent with autotuning. | [Socket-buffer comparison](reconnect-run.md#socket-buffer-comparison--lifecycle-getters-versus-live-kernel-caps) |
| Fully covered reciprocal sockets | derived | All 315 fully covered windows were analyzed mechanically. The durable report keeps aggregate results and three source-anchored windows rather than exhaustive per-window socket/rate tables. | [Focused Learner/Teacher ss -tinm Evidence](reconnect-run.md#focused-learnerteacher-ss--tinm-evidence) |
| Detailed selected sockets | present | Iterations 1, 170, and 319 retain expanded bounded calculations and socket-rate context. | [Focused Learner/Teacher ss -tinm Evidence](reconnect-run.md#focused-learnerteacher-ss--tinm-evidence) |
| Interpretation | ambiguous | Selected windows show transient queues, socket-memory growth, retransmissions, and `rwnd_limited` time, but final attempts lack learner coverage; the evidence does not establish a TCP cause. Rate fields are socket behavior, not capacity. | [Focused Learner/Teacher ss -tinm Evidence](reconnect-run.md#focused-learnerteacher-ss--tinm-evidence) |

## Coverage Limitations And Unresolved Evidence

| Evidence item | Status | Verified limitation | Source |
|---|---:|---|---|
| Final receiver and platform outcome | ambiguous | Learner evidence ends during iteration 342 and contains no later `ACTIVE`. | [Unresolved Evidence Register](reconnect-run.md#unresolved-evidence-register) |
| Final passive socket coverage | missing | Full learner/active-teacher sampler windows are absent for iterations 320..342, plus four earlier role-specific gaps. | [Unresolved Evidence Register](reconnect-run.md#unresolved-evidence-register) |
| Workflow terminal outcome | missing | No control or terminal workflow artifact is supplied. | [Unresolved Evidence Register](reconnect-run.md#unresolved-evidence-register) |
| Missing counter fields | missing | The producing schema omits internal-data and leaf-hash total/clean pairs. | [Unresolved Evidence Register](reconnect-run.md#unresolved-evidence-register) |

## Verification Status

| Check | Status | Result | Source |
|---|---:|---|---|
| Fresh independent verification | present | Pass after checking all 315 fully covered reciprocal windows mechanically, retaining exact selected/extreme locators, making node naming and status splits explicit, and rechecking the final numeric evidence and `indeterminate_due_to_evidence_gap`. | [verification-notes.md](verification-notes.md) |
