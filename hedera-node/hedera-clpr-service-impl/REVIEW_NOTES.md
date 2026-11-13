# ClprSubmitBundleHandler Review Notes

## Remaining items

### Endpoint punishment — REMOVED

The flat endpoint penalty (`clpr.endpointMisbehaviorPenaltyTinybars`) has been removed. Per the CLPR spec, a
rejected bundle costs the submitting node only its transaction fee; CLPR imposes no penalties on Hiero nodes, and
misbehaving nodes are removed from the roster by governance.

### Step 9 auto-resume semantics — RESOLVED

PAUSED means "reject this one bad bundle." The next bundle still goes through Step 8's full
ordering validation — if it passes, the remote side has corrected the issue. An empty or
data-only bundle passing validation is sufficient proof; requiring a specific reply would be
an arbitrary gate. Current behavior is correct by design.
