# Rollout note: complete-generation hinTS thresholds

For real hinTS with history enabled, preprocessing votes and block signatures use the complete weight snapshot of
the matching committed generation. Eligible contributors keep their hinTS scheme weights. Members omitted from
the scheme contribute to the denominator but cannot vote or sign. Platform roster weights can lag daily TSS stake
rotations, so a current platform roster is not a substitute for the committed generation.

Let `F` be the complete generation weight and `S` the weight represented in its hinTS scheme. Relative to the
previous main-branch behavior:

|         Operation          |          Previous requirement          |            New requirement             |
|----------------------------|----------------------------------------|----------------------------------------|
| Preprocessing adoption     | At least `S/3` eligible vote weight    | At least `F/3` eligible vote weight    |
| Block signing, divisor two | Strictly more than `S/2` signer weight | Strictly more than `F/2` signer weight |

Threshold comparisons retain their existing strictness and integer rounding. For example, `F=7, S=5` changes the
minimum preprocessing vote weight from 2 to 3 and the minimum block signer weight from 3 to 4. A native signature
whose signer weight is 10 with `F=20, S=18` passes the old `1/2` check but fails the new equivalent `5/9` check;
weight 11 passes the latter. A history-backed key preprocessed with TSS 3.29.1 commits 20 directly, so its ordinary
`1/2` check rejects weight 10 and accepts weight 11.

## Availability and fault tolerance

These stricter thresholds can reduce liveness for sparse schemes. In the worst case, if up to `F/3` additional
weight among scheme parties is unavailable, collecting a strict full-generation majority requires
`S - F/3 > F/2`, hence `S > 5F/6`. The existing scheme-admission condition `S > 2F/3` is weaker. A scheme can be
validly admitted yet fail to sign under that worst-case fault placement. Operators should check complete weight,
participating scheme weight, and available signing weight before rollout or key adoption. Missing weight must not
be removed from the denominator to regain progress.
The node logs an activation warning when `S <= 5F/6`, once for that generation rather than on every round.

## Compatibility and legacy state

Preprocessing with history disabled produces participant-weight keys. Preprocessing with history enabled commits
the complete target weight even while block signatures are mocked, so the key is ready for cutover. Mock-signature
rotations promote hinTS and history together. Fresh genesis
initializes TSS singleton values in its first handled round; runtime restoration must not read those values before
that round.

In real history mode, a completed scheme requires a matching completed history proof: target identity,
verification key, any provenance binding, and participant weights must agree. Signing attempts freeze the complete
generation and native verification fraction across handoff. Reconnect discards stale runtime generations before
restoring state.
If required generation validation fails, the node cancels and removes its preprocessing controller before
processing more votes. A recovered valid snapshot allows preparation to resume; already-open signing attempts
retain their captured generation.

A legacy history proof without `target_node_weights` is usable only after consensus recovery finds complete weights
that reproduce its committed address-book hash. Startup defers signing for a matching recoverable legacy book;
reconciliation persists the verified snapshot before preprocessing or signing uses it. An incomplete bootstrap
history similarly defers signing. If no complete source survives, restore a verifiable snapshot or coordinate a
reset. A fresh genesis proof alone is not a guaranteed recovery path because preparing a new hinTS scheme may
already require the missing source denominator. The node never guesses from a different generation's roster.
Completed legacy non-WRAPS proofs without a membership snapshot fail with a recovery/reset diagnostic; they do not
silently enter the WRAPS recovery wait.

Incremental WRAPS proof-adoption votes use the source scheme's eligible weight. WRAPS signing requires a
majority of effective Schnorr weight. A provenance signature additionally requires strictly more than one third
of the source hinTS key's committed weight.

## Verifying across the 3.29.1 upgrade

The requirements for a given key are fixed when preprocessing starts. New history-backed constructions persist
`preprocessing_roster_weight` with their cutoff. Every target member contributes its listed weight to the
verification key, and only members with valid hints at the cutoff can sign. On restart the node recovers the
same inputs. A construction already past its cutoff at upgrade completes under the participant-weight rule,
so previously cast votes still agree with the result.

WRAPS authenticates the verification key bytes. For a new history-backed key, the composite verifier's existing
strict `1/2` check enforces a full-book majority. The existing wire format and ledger trust root support ordinary
rotation to these keys. Legacy sparse keys continue to expose their participant-weight denominator to external
verifiers, including when they verify historical proofs. The consensus node still uses the complete history
snapshot to enforce its local block-signing threshold for those keys.

The CN/CLPR regression `HistoryLibraryImplTest.compositeBlockProofRequiresStrictMajorityOfTheFullRoster`
rotates a participant-weight key (18) to a full-roster key (20) under one ledger ID. The 3.29.1
`NativeTssVerifier` and the released 3.28.0 composite verifier both accepted the historical 10-weight proof,
rejected a 10-weight proof after rotation, and accepted an 11-weight proof after rotation. Both also
rejected proofs assembled from mismatched generations after verification had populated the WRAPS cache.
