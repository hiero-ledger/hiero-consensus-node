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
weight 11 passes the latter.

## Availability and fault tolerance

These stricter thresholds can reduce liveness for sparse schemes. In the worst case, if up to `F/3` additional
weight among scheme parties is unavailable, collecting a strict full-generation majority requires
`S - F/3 > F/2`, hence `S > 5F/6`. The existing scheme-admission condition `S > 2F/3` is weaker. A scheme can be
validly admitted yet fail to sign under that worst-case fault placement. Operators should check complete weight,
participating scheme weight, and available signing weight before rollout or key adoption. Missing weight must not
be removed from the denominator to regain progress.
The node logs an activation warning when `S <= 5F/6`, once for that generation rather than on every round.

## Compatibility and legacy state

History-disabled and mock-signature configurations retain their previous scheme-based behavior. With history
enabled, mock-signature rotations still promote hinTS and history together. Fresh genesis
initializes TSS singleton values in its first handled round; runtime restoration must not read those values before
that round. These compatibility paths do not claim full-generation enforcement.

In real history mode, a completed scheme requires a matching completed history proof: target identity,
verification key, any provenance binding, and participant weights must agree. Signing attempts freeze the complete
generation and native verification fraction across handoff. Reconnect discards stale runtime generations before
restoring state.

A legacy history proof without `target_node_weights` is usable only after consensus recovery finds complete weights
that reproduce its committed address-book hash. Startup defers signing for a matching recoverable legacy book;
reconciliation persists the verified snapshot before preprocessing or signing uses it. An incomplete bootstrap
history similarly defers signing. If no complete source survives, restore a verifiable snapshot or coordinate a
reset. A fresh genesis proof alone is not a guaranteed recovery path because preparing a new hinTS scheme may
already require the missing source denominator. The node never guesses from a different generation's roster.
Completed legacy non-WRAPS proofs without a membership snapshot fail with a recovery/reset diagnostic; they do not
silently enter the WRAPS recovery wait.

WRAPS proof-adoption votes and key-provenance authorization retain their separate scheme-based authority rules;
this release does not change those quorum denominators. Existing canonical native WRAPS book checks remain in force.

## Remaining verifier protocol work

This is consensus-node enforcement. WRAPS metadata currently authenticates only the hinTS verification key, and the
composite `TSS.verifyTSS` API still verifies against half the weight encoded in that key. A coalition above half of
a sparse scheme can therefore still produce a signature accepted by outside verifiers. A coordinated follow-up
must authenticate the minimum threshold alongside the key, update the cryptography library's composite format and
API, and update block-node, mirror-node, and CLPR verifiers. This release does not change the signature wire format
or established trust roots.
