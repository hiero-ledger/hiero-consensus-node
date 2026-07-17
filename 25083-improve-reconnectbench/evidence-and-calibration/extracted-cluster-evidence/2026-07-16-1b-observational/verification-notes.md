# 2026-07-16 1B Observational Reconnect Verification Notes

## Scope

| Item | Status | Verification scope |
|---|---:|---|
| Collection and run | present | `2026-07-16-1b-observational` / `reconnect-run` |
| Pre-verification extraction | present | Commit `77931195cb`, [reconnect-run.md](reconnect-run.md) |
| Governing profile | present | [Cluster ReconnectBench Observational Extraction Profile](../../cluster-reconnectbench-observational-extraction-profile.md) |
| Independent audit | present | Fresh read-only comprehensive verifier plus a separate targeted outcome/structure verifier. Neither edited extraction files. |

## Verification Method

| Check | Status | Method |
|---|---:|---|
| Structure and scope | present | Checked required heading order, exactly one absolute artifact root, canonical lowercase statuses, observational-only wording, and unresolved-register consistency. |
| Receiver lifecycle | present | Independently counted and ordinally joined receiver starts/finishes, recalculated receiver wall durations, checked all 342 ordered rows, teacher distribution, absence of learner `ACTIVE`, and final evidence duration. |
| State and work shape | present | Recomputed selected path sizes/gaps, aggregate path facts, raw counter sums, and dirty-counter formulas. |
| SocketFactory | present | Reparsed the exact lifecycle grammar in all seven node logs, grouped phase/metric/value/context/node counts, and inspected the producing commit for buffer setters versus getters. |
| Passive socket coverage | present | Rejoined all sampler bounds to receiver roles and verified every fully covered reciprocal tuple. |
| Per-iteration passive calculations | present | Checked temporary 315-row main/rate working data against raw samplers, then verified the compact aggregate and retained selected/extreme handles after the exhaustive report tables were removed. |
| Outcome precedence | present | Applied the profile's five-label precedence to the four outcome-layer rows without calibration criteria. |

## Run Result

| Check | Initial result | Correction or disposition | Final result |
|---|---:|---|---:|
| Required section order and one raw root | pass | No correction. | pass |
| Receiver counts and anchors | pass | `342` starts, `341` finishes, zero `ACTIVE`; teacher distribution `59/62/53/52/66/50`. | pass |
| Ordered receiver table | fail | Replaced a literal `\n`, added receiver wall duration to all 341 completed rows, and added the verified duration aggregate. | pass after correction |
| State/work-shape values | pass | No numeric correction. | pass |
| SocketFactory counts and values | pass | Reclassified the no-unexpected-values record from `missing` to `derived`. | pass after status correction |
| Passive per-iteration contract | fail | Generated and verified complete 315-window working data, then retained compact aggregates plus three detailed windows in the durable report. | pass after correction and presentation follow-up |
| Passive locators and field availability | fail | Added `minrtt`, rate-extrema, `ssthresh`, `rwnd_limited`, and exact source handles; replaced generic method references. | pass after correction |
| Node naming | fail | Declared internal-node numbering and made internal ID / pod ordinal pairs explicit. | pass after correction |
| Divergence status | fail | Split growth-positive derived evidence from ambiguous mutation composition. | pass after correction |
| Outcome conclusion | pass with wording correction | Added receiver wall-duration and target-initialization coverage to the ambiguous lifecycle row. | pass |

## Source Reference Failures

| Initial failure | Status | Correction and recheck |
|---|---:|---|
| Selected passive rows used generic `bounded tuple scan` / nearby-extrema wording. | derived | Replaced with exact sampler line handles for RTT, `minrtt`, rate, window, and `rwnd_limited` extrema; raw recheck passed. |
| Per-iteration passive evidence lacked enough source handles to verify the aggregate. | derived | Generated exact source-window/extrema handles for all 315 complete reciprocal windows and raw-checked them; the final report retains the aggregate extrema and detailed selected-window handles without embedding the exhaustive ledger. |
| Other source-path or locator failures | not_applicable | No incorrect artifact path or failed checked non-passive locator remained after recheck. |

## Socket Attribution And Calculation Checks

| Check | Status | Verified result |
|---|---:|---|
| SocketFactory bind pairs | derived | Seven PRE/POST bind pairs; receive buffer `32768` before and after. |
| SocketFactory client pairs | derived | `461` PRE send/receive pairs, `361` POST pairs, `100` PRE-only pairs, `1,658` lifecycle lines; only expected `32768` and `43520` values. |
| SocketFactory source behavior | present | The producing commit logs buffer getters but contains no production gossip `setReceiveBufferSize()` or `setSendBufferSize()` call. |
| Getter-to-`ss` normalization | derived | First learner sample `tb=87040` in 314/315 windows, exactly `2×` the post-connect Java send getter `43520`; iteration 5 is already larger. First `rb=65536` in 242/315 windows, exactly `2×` receive getter `32768`, while 73 windows are already larger at first sampling. |
| Live buffer-cap extrema | derived | Learner maxima: `rb=30,648,664`, `tb=6,162,432`; teacher maxima: `rb=29,592,328`, `tb=2,402,304`. Exact host sysctl ceilings are not present. |
| Full both-endpoint windows | derived | 315 unique ordered receiver windows, excluding exactly `304`, `314`, `317`, `318`, and `320..342`. |
| Reciprocal tuple and continuity | derived | All 315 rows have reciprocal endpoints and no sent/acked/received counter decrease. |
| Mechanical main analysis | derived | Temporary working data covered 42 fields per window; teacher/window roles, arithmetic, availability denominators, retransmission labels, source bounds, and all `18,100` extrema/value handles checked with zero mismatches. It is not retained as a Markdown ledger. |
| Mechanical rate analysis | derived | Temporary working data covered 10 fields per window; all `3,780` rate-extrema handles checked against raw `send`, `pacing_rate`, and `delivery_rate` tokens with zero mismatches. It is not retained as a Markdown ledger. |
| Rate interpretation | present | Per-socket estimates/behavior only; no link-capacity claim. |
| Final-iteration passive attribution | missing | Learner and active-teacher sampler coverage is absent for iterations 341 and 342; no extrapolation is made. |

## Ambiguous Or Unresolved Items

| Item | Status | Verification disposition |
|---|---:|---|
| Iteration 342 outcome | ambiguous | Retained. Learner logging ends `74.751 s` after receiver start and before the shortest prior receiver completion. |
| Eventual platform recovery | missing | Retained. No later learner `ACTIVE` is supplied. |
| Client/workflow terminal outcome | missing | Retained. Client logging ends while load is active. |
| Whole-episode TCP explanation | ambiguous | Retained. The final attempts lack active learner/teacher sampler coverage, so observed socket behavior is not a causal diagnosis. |
| Exact mutation composition | ambiguous | Retained. Dirty counters do not distinguish append, modify, and remove causes. |
| Literal `ss -tinm` invocation | missing | Retained. `skmem` confirms memory telemetry, but no command string is preserved. |

## Corrections Required

| Area | Initial finding | Correction applied | Recheck |
|---|---|---|---|
| Receiver iteration table | Malformed separator and no receiver wall-duration field. | Restored physical Markdown separator; added 341 wall durations and aggregate. | 342 rows match raw anchors; duration sum `119,230.759 s`. |
| Passive per-iteration evidence | Only iterations 1, 170, and 319 initially had deep blocks. | Generated complete 315-window main/rate working data for verification; after review, replaced its exhaustive Markdown presentation with aggregates and retained the three detailed blocks. | Row sets, roles, tuples, counters, extrema, source windows, aggregate buffer distributions, and retained handles pass raw recheck. |
| Passive selected rows | Missing `minrtt` and some exact extrema/availability locators. | Added exact values, availability columns, and narrow handles. | pass |
| Node identity | Internal IDs and `network-node` ordinals could be confused. | Added numbering rule and explicit mappings in affected rows. | pass |
| Canonical status | Unexpected-values record used `missing`; divergence mixed derived and ambiguous claims. | Reclassified unexpected values as `derived`; split divergence into two records. | pass |
| Outcome evidence | Lifecycle row cited synchronization minimum but not receiver wall minimum. | Added both minimums and exact final coverage relative to target initialization. | Label remains `indeterminate_due_to_evidence_gap`. |

## Final Verification Status

| Check | Status | Result |
|---|---:|---|
| Evidence verification | present | **Pass.** The comprehensive verifier reported no remaining corrections after the lead-applied changes. |
| Structural verification | present | Required section order, one absolute root, lowercase statuses, compact passive aggregates, selected detailed windows, and `git diff --check` all pass. |
| Observational outcome | derived | `indeterminate_due_to_evidence_gap`, verified against profile precedence and raw final-coverage duration. |
| Ready for summary and manifest closeout | present | Yes. [extraction-summary.md](extraction-summary.md) is constrained to verified [reconnect-run.md](reconnect-run.md) evidence. |
