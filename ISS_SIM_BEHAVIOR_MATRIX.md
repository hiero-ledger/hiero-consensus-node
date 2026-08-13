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
