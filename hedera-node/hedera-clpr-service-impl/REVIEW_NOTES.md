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

### Response delivery charging — #27885

Both `onClprMessage` and `onClprResponse` prepay the same configured tinybar charge:
`messageExecutionCost + messageExecutionCost * endpointMarginPercent / 100`. The
connector contract is debited and the bundle payer is credited before application
code runs. CLPR dispatch metadata continues to suppress a second EVM gas charge.
Revert or out-of-gas does not refund the execution charge.

This aligns responsibility for response delivery with cross-platform spec §4.2,
Step 6. Hiero's existing flat execution cost and network-wide margin remain the
pricing policy for both paths. The spec's actual-gas formula and per-connector
margin are not implemented by this change; adopting them requires changing both
paths and their fee/configuration model together.

If the source connector is missing, the response callback is skipped. If its
contract account is missing or underfunded, the callback is skipped and the
connector is slashed, using the same penalty/reimbursement mechanism as underfunded
data delivery. A response incurs at most one slash, including when its status
also reports a remote connector failure. In either case, the response is consumed
and its in-flight slot is released when the connector still exists; no response
to the response is generated. A response with an empty sender has no callback and
no execution charge, but its status can still trigger the existing slashing rule.
