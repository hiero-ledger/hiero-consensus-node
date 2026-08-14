<!-- SPDX-License-Identifier: Apache-2.0 -->

# ISS simulator behavior matrix (self-ISS)

Simulator-only baseline. For the diverging node (node1): is its ISS-round block still in the in-memory buffer when
the debug capture runs?

**Rule:** the block is kept **iff it is unacked, OR `lag ≤ keep`** — `lag = currentBlock − issBlock`,
`keep = blockStream.buffer.ackedBlocksToRetain`. Outcome: `.iss.gz` = kept, `iss-round-*.txt` = lost.

|                Test                | keep |   acks   | lag  |    acked     | inBuffer |      outcome       |
|------------------------------------|------|----------|------|--------------|----------|--------------------|
| C3 `selfIssRetain10Sim`            | 10   | on       | 1    | yes          | yes      | **KEPT** (.iss.gz) |
| C5 `selfIssBadBlockProofRejection` | 1    | on       | 1    | yes          | yes      | **KEPT** (.iss.gz) |
| C9 `selfIssCnBehindResend`         | 10   | on       | 1    | yes          | yes      | **KEPT** (.iss.gz) |
| C4 `selfIssBnBehindWithheldAcks`   | 1    | withheld | 1    | no           | yes      | **KEPT** (.iss.gz) |
| C10 `selfIssRetain0Pruned`         | 0    | on       | 1    | yes → pruned | no       | **LOST** (.txt)    |
| C11 `selfIssLateNotification`      | 1    | on       | ~2–3 | yes → pruned | no       | **LOST** (.txt)    |

Every row satisfies the rule: kept when unacked (C4) or `lag ≤ keep` (C3/C5/C9); lost when `lag > keep` (C10: 1>0; C11: ~3>1).

Notes:
- Lost rows (C10/C11): the block was acked (hence prunable) and pruned before capture — the `ISS-DIAG` line reads
`issBlock=-1 acked=false` only because the block is already gone; `lag` shown is the effective lag.
- C5/C9 also exercise post-detection block-node signals (bad-proof / resend); those do not change the buffer outcome.

Baseline for the planned proof-matching ack change: after it, every self-ISS row should show **`acked=false` → KEPT**
(the divergent block's proof will not match the ack's proof, so it is never acked → never pruned). Re-run to compare.
Catastrophic ISS is covered by the real-block-node test C6 (see `ISS_TEST_RESULTS.md`).

---

## Post-ack-proof re-run + new-mechanism rows (session 2026-08-13)

Re-ran the whole SIM matrix on top of the ack-proof work — a mock `block_proof` on every `BlockAcknowledgement`, the
`blockNode.requireAckProof` CN gate (**default off**), and the simulator's `sendInvalidAckForBlock` / `delayAckForBlock`
hooks — and added three rows (C13–C15) that exercise those mechanisms. Numbers are node1's `ISS-DIAG` at detection;
`rej` = count of the CN's `does not match expected` ack rejections.

**Correction to the baseline prediction above.** The gate is **opt-in per node** (a `@NodeProperty`), not a global
switch, so the change does *not* make "every self-ISS row `acked=false` → KEPT". A row is only `acked=false → KEPT` if
that node both turns the gate on **and** the ack's proof mismatches (C13) or the ack is withheld/deferred (C4/C15). With
the gate off, or with a *matching* proof (C14), the block is still acked exactly as before — by design (a blanket
"never ack" would stall every real-BN stream).

**How the new mechanisms fill the matrix.** The only self-ISS LOSS rows are C10 (keep=0) and C11 (late notification),
and both lose *because the divergent block is acknowledged* (`acked=true` ⇒ prunable). Each new mechanism instead makes
the divergent block **unacked** — and, unlike the retention-window keeps (C3/C5/C9, which keep only while `lag ≤ keep`),
does so **deterministically**, because an unacked block is never pruned at any keep or lag:

|                       Test                       | keep |         acks         | lag  |    acked     | inBuffer |      outcome       | rej |    vs old matrix    |
|--------------------------------------------------|------|----------------------|------|--------------|----------|--------------------|-----|---------------------|
| C13 `selfIssInvalidAckProofKept`                 | 0    | invalid proof + gate | 1    | **no**       | yes      | **KEPT** (.iss.gz) | 1   | **flips C10 loss**  |
| C15 `selfIssDelayedAckKept`                      | 0    | valid, delayed 5 blk | 1    | **no**       | yes      | **KEPT** (.iss.gz) | 0   | **flips C10 loss**  |
| C14 `selfIssLateNotificationValidAckProofPruned` | 1    | valid proof + gate   | ~2–3 | yes → pruned | no       | **LOST** (.txt)    | 0   | **= C11** (control) |

C13 keeps because the gate rejects the mismatched proof (`highestAcked` never reaches the ISS block). C15 keeps because
the ack is deferred past detection (`highestAcked = issBlock − 5`, the configured delay). C14 is the control: a *matching*
proof is still honored (`acked=true` ⇒ pruned ⇒ LOST), proving the gate rejects only *wrong* proofs, not all acks — it
does not regress the normal path.

Pre-existing rows, re-run with the gate **off** (outcomes unchanged in kind):

|                Test                | keep |   acks   | acked (this run) |       outcome        |                       note                        |
|------------------------------------|------|----------|------------------|----------------------|---------------------------------------------------|
| C3 `selfIssRetain10Sim`            | 10   | on       | no\*             | **KEPT**             | \*acks not yet arrived; keep=10 ⇒ kept regardless |
| C4 `selfIssBnBehindWithheldAcks`   | 1    | withheld | no               | **KEPT**             | unacked ⇒ never pruned                            |
| C5 `selfIssBadBlockProofRejection` | 1    | on       | yes              | **KEPT**             | lag=1 ≤ keep=1                                    |
| C9 `selfIssCnBehindResend`         | 10   | on       | yes              | **KEPT**             | lag=1 ≤ keep=10                                   |
| C11 `selfIssLateNotification`      | 1    | on       | yes → pruned     | **LOST**             | lag ~2–3 > keep=1 (reliable)                      |
| C10 `selfIssRetain0Pruned` (×3)    | 0    | on       | — (see note)     | **LOST, KEPT, LOST** | **flaky**                                         |

**C10 is flaky, not deterministic** (this session 2/3 LOST; an earlier `regress-C10` also KEPT). keep=0 at lag=1 is a
*two-sided* race — the loss needs the ack to arrive **and** the prune to fire before the async capture. The KEPT run
here missed on the ack side (`highestAcked=-1`, ack not yet arrived); a prior KEPT missed on the prune side
(`acked=true`, not yet pruned). The **reliable** loss is C11 (`lag > keep` by a margin), which is why C14's control is
built on the C11 base rather than keep=0.

**Gap left open (documented, not forced).** A faithful "the gate flips the *reliable* C11 loss to KEPT" row is not
cleanly runnable with the mock: forcing `lag > keep` needs `blockPeriod=0`, which drives block numbers into the
hundreds (timing-dependent), and leaving the ISS block unacked would require suppressing its ack across an unknown,
open-ended high range — which saturates the 200-block buffer and stalls production before detection. C13 already
demonstrates the gate's KEEP effect (at keep=0), and since "unacked ⇒ never pruned" is independent of `lag`/`keep`, the
keep=1-late KEEP outcome follows from C13 by the same invariant.
