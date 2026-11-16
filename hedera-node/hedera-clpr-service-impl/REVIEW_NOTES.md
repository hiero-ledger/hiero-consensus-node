# ClprSubmitBundleHandler Review Notes

## Remaining items

### Endpoint punishment — RESOLVED

Implemented `validateTrueOrPenalize` using `HandleException.OnRollback` to charge a flat
penalty (configurable via `clpr.endpointPenaltyTinybars`) that survives transaction rollback.
Throw-path (Steps 3-7) uses OnRollback + `FeeCharging.Context.charge()`. Return-path
(Step 8) uses `context.tryToChargePayer()` since the transaction commits normally.
Future: geometric escalation can layer on top via infraction counter state.

### Step 9 auto-resume semantics — RESOLVED

PAUSED means "reject this one bad bundle." The next bundle still goes through Step 8's full
ordering validation — if it passes, the remote side has corrected the issue. An empty or
data-only bundle passing validation is sufficient proof; requiring a specific reply would be
an arbitrary gate. Current behavior is correct by design.

### Response endpoint compensation — #27885

Both `onClprMessage` and `onClprResponse` prepay the same flat endpoint compensation:
`messageExecutionCost + messageExecutionCost * endpointMarginPercent / 100`, in
addition to the network gas reimbursement introduced by #28148. The connector is
debited and the bundle payer credited before application code runs. Only unused
gas is refunded; callback revert, out-of-gas, or pre-execution throttling retains
endpoint compensation, matching data delivery. Network gas escrow, settlement,
transaction-wide callback limits, and protection against duplicate EVM gas charges
remain unchanged.

This aligns the two delivery paths' endpoint compensation. It does not implement
the cross-platform spec §4.2 Step 6 formula for a per-connector margin on actual
gas: Hiero retains its flat endpoint compensation plus separately priced actual
network gas. Adopting that formula requires a coordinated pricing-policy change.

A missing source connector skips the callback. When a callback has a nonempty
sender and a positive gas allowance, a missing contract account or a balance below
endpoint compensation plus the full gas reservation skips the callback and slashes
the connector, using the existing penalty/reimbursement mechanism. There is at most
one slash per response even if it also reports a remote connector failure.

Responses are consumed and in-flight counts released when the connector still
exists, without generating a reply to a reply. An empty sender or exhausted bundle
gas allowance causes no callback charge or local-underfunding slash; remote
connector-failure statuses still trigger the existing slashing rule. Connector
state is reloaded after callbacks to preserve nested sends, and replayed responses
are not charged again.
