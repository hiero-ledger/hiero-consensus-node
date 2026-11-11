// SPDX-License-Identifier: Apache-2.0
/**
 * Java port of the Solidity {@code CantonVerifier}: a {@link com.hedera.node.app.service.clpr.impl.verifier.ClprVerifier}
 * that checks a CLPR bundle was committed by the CLPR service on a Canton synchronizer.
 *
 * <p>The design is vendored at {@code docs/superpowers/specs/canton-verifier/canton-verifier.md}
 * (see also {@code canton-protobuf-inventory.md} and {@code solidity-reference/} alongside it).
 *
 * <h2>What a bundle proof shows</h2>
 * Canton has no blocks and no state root, so each bundle is checked in two parts:
 * <ol>
 *   <li><b>The verdict.</b> At least the threshold of the synchronizer's mediators signed that the transaction of a
 *       write into the channel was approved. The verdict names the transaction by its Merkle root hash.</li>
 *   <li><b>The opening.</b> The transaction's Merkle tree, rebuilt from the view of the write and the path of
 *       sibling hashes above it, hashes to that root. The write created the channel's next version, which holds the
 *       state after the write, including the running hash after its last message.</li>
 * </ol>
 * A bundle may carry the payloads of several consecutive writes with the proof of the last one only; the proven
 * running hash binds them.
 *
 * <h2>What the verifier trusts</h2>
 * <ul>
 *   <li>At most {@code threshold - 1} mediators sign a verdict for a transaction that was not approved.</li>
 *   <li>The declared synchronizer id, owner set and mediator set at channel setup (trust on first use). The
 *       setup verdict shows the declared keys are live, not that they are the synchronizer's real mediators.</li>
 *   <li>At most {@code threshold - 1} owners, of the owner set trusted at a time, sign a topology change that the
 *       synchronizer did not make. An endpoint can withhold a change; verdicts signed with keys the trust anchor
 *       does not know then fail and the channel waits, but nothing false passes.</li>
 *   <li>The application's own participant, for the messages of its channel. It alone approves its sends, and only
 *       approving participants check that an input contract is still active, so a dishonest application could
 *       approve two sends on one channel version. The CLPR service takes the first one delivered and rejects the
 *       other by its message ids and running hash; this only affects the application's own channel.</li>
 *   <li>Not the Hedera-side operator. It signs and approves nothing, so it cannot forge, change, delay or reorder a
 *       message, or reply in an application's name.</li>
 * </ul>
 *
 * <h2>What the verifier validates</h2>
 * The verdict's mediator signatures against the trusted mediator set, the Merkle opening against the verdict's root
 * hash, the channel and application identity in the opened contract, and (when a bundle carries them) topology
 * rotations against the trusted owner set and trust anchor. Canton's protobuf is parsed strictly: because the
 * application's participant writes every leaf of the transaction tree, the verifier must read the view as Canton
 * does, so a repeated singular field, a wrong wire type, or a {@code oneof} holder holding anything but the one
 * expected field is rejected as malformed. Canton writes none of these, so only crafted bytes are rejected.
 *
 * <h2>Consensus-safety contract</h2>
 * This verifier is expected to be byte-for-byte equivalent in outcome to the Solidity reference: every node must
 * accept and reject exactly the same bundles. Any divergence from the vendored Solidity behavior is a consensus
 * bug, not a style difference. The verifier keeps no state of its own; the trust anchor it is handed and returns
 * is the only persisted trust state.
 *
 * <h2>Not supported yet</h2>
 * Per the design doc: the authorization chain at setup, session-key signatures, and topology transactions of
 * proto version 31 are not supported. They are rejected, and the channel waits for a verifier that reads them.
 */
package com.hedera.node.app.service.clpr.impl.verifier.canton;
