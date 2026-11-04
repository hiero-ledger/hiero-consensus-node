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

The queue remains append-only. Its links and their durable block-signing
witnesses have no pruning rule or retention limit: deleting either requires an
explicit policy preserving historical catch-up.

## Durable block-signing witnesses

A predecessor's signature authorizes a candidate key before handoff. It does
not establish that the network adopted the candidate. In particular, a completed
pending construction can be replaced without entering the adoption queue. An
untrusted endpoint can retain and replay its certificate after replacement.

`HINTS_KEY_WITNESSES` is a companion map keyed by target hinTS construction ID.
Each `HintsKeyWitnessRecord` contains the adopted `HintsKeyProvenance`, an
optional activation timestamp, and an optional `HintsKeyWitness`. Handoff
initializes the pending record in the same savepoint as the existing queue
append and both promotions. The queue's type and adoption ordering are unchanged.

The compact witness contains the target construction ID, a consensus timestamp,
one sibling hash, the block aggregate signature, and the block hash algorithm.
It reconstructs the directly signed block root as

```
hashInternalNode(hashLeaf(Timestamp.PROTOBUF.toBytes(timestamp)), siblingHash)
```

The timestamp is always the left child at depth one. SHA-256 and SHA-384 use
their existing block-tree domain separation, with exactly 32- and 48-byte sibling
hashes respectively. The signature must verify under the certificate's exact
target verification key at the strict greater-than-one-third threshold. This
fixed witness threshold, like the certificate threshold, is independent of
`tss.signingThresholdDivisor`. Producers must collect more than one third of the
generation's weight even if their block-signing configuration permits a lower
threshold. A divisor greater than three does not guarantee a usable witness:
the block aggregate may complete before it contains sufficient weight. Exactly
one third is insufficient, including with unequal party weights. No WRAPS
proof is duplicated in this witness: consumers establish key authority by
verifying the provenance chain from their own trusted key.

The protobuf `hash_algorithm` value defaults to SHA-256 (enum value zero), so an
absent field means SHA-256, regardless of the current network configuration.
Historical proofs select their recorded algorithm, not the verifier's current
block digest.

### Production and consensus admission

The block signing attempt captures its construction ID and verification key.
Once its aggregate signature is available, the node can publish a witness using
the `key_witness` variant of `HistoryProofKeyPublicationTransactionBody`. The
timestamp and sibling come from that same directly signed block; an indirect
proof for a different block cannot substitute its timestamp or root.

Consensus processing accepts a witness only for a generation already in the
companion map. The native admission rule requires a positive construction ID
matching that adopted record; the cryptographic verifier itself ignores this
unsigned field. Admission validates the proof's shape and signature, rejects timestamps
after the publication's consensus time, and, when the activation boundary is
known, requires a timestamp strictly after that boundary. This excludes a block
that started before a runtime handoff. Runtime adoption records the handoff's
consensus time; restart adoption uses the last consensus timestamp in the
startup state. Both paths then wait for an eligible block to be signed.
Every new provenance handoff requires a non-null activation boundary; an unknown
boundary is permitted only for legacy records imported by migration. A duplicate
target companion record, whether identical or conflicting, aborts a new handoff
before either the queue or the construction promotions are changed.

Witness publication and admission are disabled while `tss.forceMockSignatures`
is true. Such generations may therefore remain without witnesses after a later
cutover to real signing. Signature verification is warmed in pre-handle through
a bounded cache keyed by the full witness and expected verification key.
Handling checks the adopted record and timestamp eligibility again, and uses a
cached verification result when available. A cache miss verifies deterministically
in handle; local cache availability cannot change acceptance.

The cache is a throughput optimization, not the bound on native work. Pre-handle
also reruns on the handle thread before handling, a single event can carry tens of
thousands of publications, and a flood of unique forgeries evicts the cache. The
bound is consensus state: a publication that passes the cheap admission checks and
then fails verification (or carries a timestamp after its consensus time) records
its creator in the companion record's `rejected_node_ids`. Every later publication
from a recorded creator for that record is ignored before any native work, in
pre-handle and in handle alike. A node can therefore cost at most one failed
verification per generation, whatever the cache holds, and it can only silence
itself: a recorded node does not affect any other node's publications.
Publications that fail the cheaper admission checks (no pending record, malformed
shape, a timestamp at or before the activation boundary) record nothing, since no
native work is spent on them.

The first accepted witness is immutable. Consensus transaction order selects it,
not local asynchronous completion order. Repeated publications do not replace
the proof. Submitting a transaction or completing a local signature future is
not evidence that the witness was persisted. While real signing is enabled,
publication retries until consensus state contains it, and delayed signatures
retain their original generation binding even after another handoff. A generation
has at most one in-flight submission. Retries back off from 30 to at most 300
consensus seconds; retaining retries avoids stranding an already retired key
during a temporary outage. A replacement block candidate preserves the throttle.
Pending state is refreshed at most once per 30 consensus seconds between changes
of the active construction, rather than scanned on every round.

The ordinary block proof completes before the optional witness publication.
The producer checks its reconstructed witness root against the captured signed
root, and reports an error without publishing if they differ. A local publication
failure does not discard the history controller's consensus state changes.
The `app:hintsKeyWitnessPendingCount` and
`app:hintsKeyWitnessOldestPendingAgeSeconds` gauges expose missing witnesses;
the age covers known activation timestamps and excludes legacy records with
unknown boundaries. Both gauges are zero while real signing is disabled.

No rotation waits for witness publication. An active generation missing its
witness can use a later signed block, including after restart or reconnect.
The consensus admission path can accept a witness reconstructed from an existing
historical block signature, without asking retired nodes to sign again. However,
this implementation produces witnesses only from live signing callbacks; it
does not scan historical blocks or automatically publish backfill. The publication
transaction is node-only, so historical backfill requires additional node-side
tooling and cannot be submitted directly by an external endpoint. A generation
without a retained eligible signature cannot be recovered through this path.
Restart migration creates pending records for older queue entries that lack a
companion record. Their activation boundary is explicitly unknown, so migration
does not invent a recent boundary that would reject genuine historical proofs.

### Retrieval and consumer acceptance

`ReadableHistoryStore.getKeyWitnessRecord(id)` retrieves one record;
`keyProvenanceWithWitnesses()` exposes augmented records in queue adoption order.
The protobuf codec and typed state-change encoding make the records available
for endpoint reconstruction and serialization. An absent witness means pending
publication or missing historical data, not an invalid adoption. Endpoints must
preserve the distinction rather than manufacture a proof or silently advance a
consumer through an unwitnessed batch endpoint.

For gas-bounded catch-up, a consumer verifies a bounded certificate segment
starting at its currently trusted key, verifies the retained witness under the
segment's exact endpoint key, and only then commits the new anchor. Any failure
must preserve the previous anchor. Only the endpoint witness needs verification
in a batch; intermediate generations may lack witnesses. Keeping a witness for
every generation lets callers choose arbitrary batch boundaries.

For example, a January anchor can advance through several transactions ending
at January 20, February 8, and later historical keys before accepting a December
message. Old witness timestamps remain valid for this historical operation.
The reference consumer requires an endpoint timestamp strictly after its stored
checkpoint as an ordering policy; key continuity, rather than the timestamp,
authenticates the next key. Message freshness is a separate policy and must not
apply a wall-clock age limit to key catch-up.

Initialize a consumer that knows only an independently trusted key using
`HintsKeyProvenanceVerifier.Checkpoint.fromTrustedKey(key)`. This uses epoch as a
sentinel lower bound, so a consumer registered today can accept old historical
witnesses. Do not initialize its timestamp to registration time or the local
clock: doing so prevents catch-up through older endpoints. When resuming a
previously committed checkpoint, restore both its key and its authenticated
witness timestamp together.

`HintsKeyProvenanceVerifier` is a bounded reference for endpoint and EVM consumer
implementations, not a node-runtime component. It returns a new immutable
checkpoint only after the complete batch succeeds. The consuming application
must persist that pair atomically; the reference neither writes its checkpoint
nor enforces application-message freshness.

Construction IDs remain unsigned bookkeeping. The cryptographic chain follows
the actual keys, and the witness proves use of the endpoint key, not an
independently authenticated generation number. Neither asserted queue membership
nor a successor's self-signed block root replaces the predecessor certificate.
The security argument assumes honest nodes sign actual block roots using
activated generations and the applicable threshold fault bound holds. It does
not establish that a historical key is still current or solve later compromise
of retired signing keys.

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
