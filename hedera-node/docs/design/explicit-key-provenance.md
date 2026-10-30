# Explicit hinTS key provenance

Each hinTS signing generation authorizes its successor by signing the successor's
verification key. This supplements the WRAPS chain of trust with an explicit
sequence of adjacent-key signatures. It does not replace the ledger ID or WRAPS
proof as the network's trust anchor.

## Signed message and identity

The signed message is exactly the next hinTS verification key's serialized bytes.
It does not include the address-book hash, a digest of the key, construction IDs,
or a separate message prefix. The signature verifies under the previous hinTS
verification key with `verifyAggregate(signature, nextKey, previousKey, 1, 3)`.

`HintsKeyProvenance` records both verification keys, their hinTS construction IDs,
the aggregate signature, and the history construction ID. These IDs make the local
adoption sequence explicit, but are bookkeeping rather than signed statements.
A consumer must verify the signature and follow the actual keys from its trusted
starting key; untrusted construction IDs alone establish no trust.

Before assembly, the pending history construction binds the expected source and
target construction IDs and verification keys in a `HintsKeyProvenance` whose
signature is initially empty. Proof finalization supplies the verified signature
without changing that binding.

The signer captures the completed source `HintsConstruction`, its bound CRS,
aggregation and verification keys, and node-to-party assignments. It does not use
the mutable block-signing context. Thus source and target generations can coexist
while the next roster's WRAPS construction is in progress.

## Participation and thresholds

For a key rotation, nodes attach their source-generation hinTS partial signature
to the existing WRAPS R1 publication in
`HistoryProofKeyPublicationTransactionBody`. The accompanying source hinTS
construction ID is distinct from the history construction ID already in that
body. No new gossip transaction type or signing round is needed.

A rotation's R1 message is accepted only when the accompanying partial signature
verifies for the transaction creator's party in the source scheme, over the exact
target verification key. The companion is persisted with the WRAPS message so
restart and reconnect reconstruct the same accepted R1 set. Duplicate, malformed,
wrong-generation, or wrong-key companions do not add signing weight.

The WRAPS implementation freezes its R1 participants after their source weight
is **strictly greater than one half**, then requires R2 and R3 messages from that
same set. Its source weights come from the persisted active hinTS scheme. Because
every admitted R1 participant supplies a valid companion, the frozen set also
exceeds the provenance signature's **strictly greater than one third** threshold.
The provenance check uses the fixed fraction `1/3`, independent of the configurable
block-signature threshold. Exactly one third is insufficient.

This requirement also prevents a Byzantine participant from supplying its WRAPS
message while withholding the companion needed to authorize the next key. A node
whose previous private key is unavailable cannot contribute to this rotation's
R1 quorum; another valid source participant must contribute instead.

## Completion and consensus state

The prover aggregates the companions from the frozen R1 set while computing its
proof output. The result is carried in `HistoryProof.key_provenance_signature`.
Aggregation runs outside the consensus thread; completion of a local future does
not itself write network state.

Every proof vote for a key rotation must carry a valid provenance signature under
the expected source key and over the expected target key, in addition to a valid
WRAPS proof. This validation applies even if the receiving node has not observed
the prover's complete local MPC transcript. The proof-vote identity includes the
provenance signature, so a congruent vote refers to the entire submitted proof.

Before completing the history construction, consensus processing records the
validated `HintsKeyProvenance` in `HistoryProofConstruction.key_provenance`.
Consequently, a completed rotation proof has both the WRAPS evidence and the
previous generation's authorization needed for adoption. Local aggregation
timing cannot select a different certificate to put in state.

## Adoption queue

The history service's `HINTS_KEY_PROVENANCE` queue contains authorization links in
adoption order. Preparing a certificate does not enqueue it. The joint hinTS and
history handoff first checks that the certificate names the outgoing and incoming
hinTS construction IDs and verification keys, and that the incoming key matches
the history proof's metadata.

During consensus handling, the queue append and promotion of the next
constructions to active use the same writable savepoint. A failed transaction
rolls them back together. Restart roster adoption invokes the same guarded
handoff against the startup state before that state is published. A successful
handoff clears the next slot, so repeating the handoff cannot append the same
transition again. Weight rotations use this same joint handoff path.

Genesis has no preceding signing generation and creates no entry. A history-only
re-anchor that retains the hinTS generation and a transport-only roster rebind
also create no new link. Equal roster hashes are not, by themselves, evidence
that a signing generation is unchanged.

The queue is append-only in this change. No consumer, pruning rule, or retention
limit is introduced: deleting old links requires an explicit future policy that
preserves the provenance needed by consumers.

## Restart, retries, and upgrades

Persisted source and target bindings, accepted R1 companions, completed proof
votes, and construction certificates provide the information needed to resume
without using block-signing timeouts or a transient previous-key acceptance
window. A replacement target key cannot inherit signatures collected for the old
target; its bytes must be signed and verified afresh.

A retry of the WRAPS protocol uses fresh Schnorr entropy. The hinTS companions
are ordinary signatures of the same target key and have no Schnorr round nonce,
but they must still be associated with the consensus-defined R1 participant set
of the resumed attempt.

Populated legacy TSS state remains subject to the parent PR's rejection policy;
this feature does not reset state or silently create a new ledger trust root.
Compatible completed source generations can authorize later generations. An
unfinished rotation must not be treated as ready merely because it has an older
WRAPS proof without the required provenance certificate.
