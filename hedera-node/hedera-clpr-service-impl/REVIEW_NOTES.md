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
