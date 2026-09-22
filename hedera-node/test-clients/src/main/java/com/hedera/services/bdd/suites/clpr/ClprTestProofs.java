// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.hapi.node.state.clpr.ClprChannelStatus.fromProtobufOrdinal;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.protoToPbj;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.platform.state.StateItem;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hederahashgraph.api.proto.java.ClprBundleContent;
import com.hederahashgraph.api.proto.java.ClprChannelStatus;
import com.hederahashgraph.api.proto.java.ClprEndpointManifest;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprMessagePayload;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared helpers for CLPR HAPI tests that drive the {@code ClprPassThroughVerifier}
 * contract. The deployed verifier's {@code verifyConfig} scans the input bytes as a
 * {@link StateProof}, walks {@code paths -> state_item_leaf -> StateValue}, and unwraps
 * the first StateValue field as the proven {@link ClprLedgerConfiguration} — i.e. it
 * does NOT accept raw config bytes. Tests need to wrap their ledger config in this
 * synthetic state-proof shape before passing it as {@code configProofBytes}.
 *
 * <p>Spec refs: §3.1 (Verifier Contract Interface — {@code verifyConfig(proofBytes) -> bytes}
 * returns the proven {@code ClprLedgerConfiguration}); §5.1.3 (Phase 2 — Reveal: the
 * {@code completeChannel} handler invokes the verifier over the registrant's
 * {@code config_proof_bytes} and stores the returned config + its {@code initial_trust_anchor}
 * on the new Channel).
 */
final class ClprTestProofs {

    private ClprTestProofs() {}

    /**
     * Wraps a {@link ClprLedgerConfiguration} in a synthetic {@link StateProof} that the
     * passthrough verifier accepts. Mirrors {@code ClprOrchestratorSubmitTest#buildProofBytes}.
     *
     * <p>The wire shape is the one the passthrough Solidity verifier scans for:
     * {@code StateProof{ paths = [MerklePath{ state_item_leaf = StateItem{ value =
     * StateValue{ ClprService_I_LEDGER_CONFIGURATION = <config> } } }] }}. The verifier
     * unwraps the first StateValue field and returns those raw bytes; {@code EvmClprVerifier}
     * then parses them as PBJ {@code ClprLedgerConfiguration}. This matches the protocol
     * §3.1 contract (proof bytes → proven config) without performing any cryptographic
     * verification — fine for tests, not safe in production.
     */
    static byte[] toConfigProofBytes(final ClprLedgerConfiguration config) {
        final var pbjLedgerConfig = protoToPbj(config, com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration.class);
        final var stateValue = StateValue.newBuilder()
                .clprServiceILedgerConfiguration(pbjLedgerConfig)
                .build();
        final var leaf = StateItem.PROTOBUF.toBytes(
                StateItem.newBuilder().value(stateValue).build());
        final var path = MerklePath.newBuilder().stateItemLeaf(leaf).build();
        return StateProof.PROTOBUF
                .toBytes(StateProof.newBuilder().paths(path).build())
                .toByteArray();
    }

    /**
     * Wraps a {@link ClprEndpointManifest} in a synthetic {@link StateProof} that a
     * passthrough {@code verifyConfig(bytes,bytes,bytes)} accepts as its manifest-proof
     * argument. Analogous to {@link #toConfigProofBytes} but produces a state-item leaf
     * carrying a {@code ClprEndpointManifest} StateValue variant. Added for #332.
     *
     * <p>Note: the deployed passthrough Solidity verifier must expose the extended
     * {@code verifyConfig(bytes,bytes,bytes) -> (bytes,bytes)} selector for HAPI suites
     * to actually run end-to-end. Until the contract is updated (cross-repo work),
     * suites that {@code payingWith(GENESIS)} into a passthrough verifier will only
     * clear pureChecks - runtime verifier dispatch will still fail.
     */
    static byte[] toManifestProofBytes(final ClprEndpointManifest manifest) {
        final var pbjManifest = protoToPbj(manifest, com.hedera.hapi.node.state.clpr.ClprEndpointManifest.class);
        final var stateValue = StateValue.newBuilder()
                .clprServiceIEndpointManifest(pbjManifest)
                .build();
        final var leaf = StateItem.PROTOBUF.toBytes(
                StateItem.newBuilder().value(stateValue).build());
        final var path = MerklePath.newBuilder().stateItemLeaf(leaf).build();
        return StateProof.PROTOBUF
                .toBytes(StateProof.newBuilder().paths(path).build())
                .toByteArray();
    }

    /**
     * Wraps a peer-bundle scenario in a synthetic {@link StateProof} that the passthrough
     * {@code verifyBundle} accepts. The verifier walks state-item leaves and reassembles a
     * {@link com.hederahashgraph.api.proto.java.ClprBundleContent} from:
     *
     * <ul>
     *   <li>one {@link ClprChannel} leaf — the verifier reads {@code status} (field 7),
     *       {@code acked_message_id} (field 9), {@code received_message_id} (field 11) and
     *       {@code received_running_hash} (field 12), and projects them onto
     *       {@code ClprQueueMetadata.{state, received_message_id, received_running_hash}}; it
     *       computes {@code nextMessageId = ackedMessageId + 1 + msgCount} and
     *       {@code sentRunningHash = last message leaf's running_hash_after_processing}; and</li>
     *   <li>N {@link ClprMessageValue} leaves — one per outbound message the peer claims,
     *       each carrying the {@link ClprMessagePayload} and the SHA-256 running hash through
     *       that slot (formula: {@code SHA-256(prev_hash || SHA-256(serialized_payload))},
     *       see spec §4.1).</li>
     * </ul>
     *
     * <p>Spec refs: §3.1 (Verifier Contract Interface — {@code verifyBundle} returns the proven
     * {@code ClprBundleContent}); §4.1 (Running Hash Computation); §4.2 (Bundle Verification
     * Algorithm — handler's step 6 re-folds payloads in shipped order to validate
     * {@code sentRunningHash}).
     *
     * @param status               channel status reported by the peer
     * @param ackedMessageId       highest outbound msg ID the peer reports as acked
     * @param receivedMessageId    peer's highest received-from-us msg ID
     * @param receivedRunningHash  peer's cumulative received hash (32 bytes)
     * @param payloads             ordered list of message payloads the peer is delivering
     */
    static byte[] toBundleProofBytes(
            final ClprChannelStatus status,
            final long ackedMessageId,
            final long receivedMessageId,
            final byte[] receivedRunningHash,
            final List<ClprMessagePayload> payloads) {
        return toBundleProofBytes(
                status, ackedMessageId, receivedMessageId, receivedRunningHash, payloads, new byte[32]);
    }

    static byte[] toBundleProofBytes(
            final ClprChannelStatus status,
            final long ackedMessageId,
            final long receivedMessageId,
            final byte[] receivedRunningHash,
            final List<ClprMessagePayload> payloads,
            final byte[] initialRunningHash) {
        final var paths = new ArrayList<MerklePath>();

        // 1) Channel leaf — drives metadata.state, received_message_id, received_running_hash.
        // PBJ enums map by ordinal — protoc and PBJ share the wire-format numeric value.
        final var pbjStatus = fromProtobufOrdinal(status.getNumber());
        final var channel = ClprChannel.newBuilder()
                .status(pbjStatus)
                .ackedMessageId(ackedMessageId)
                .receivedMessageId(receivedMessageId)
                .receivedRunningHash(Bytes.wrap(receivedRunningHash))
                .build();
        paths.add(leafPath(StateValue.newBuilder().clprServiceIChannels(channel).build()));

        // 2) Message leaves — one per payload, carrying the cumulative running hash through
        //    that slot. The verifier picks the LAST leaf's running hash as metadata.sentRunningHash.
        var prevHash = initialRunningHash;
        for (final var payload : payloads) {
            final var pbjPayload = protoToPbj(payload, com.hedera.hapi.node.state.clpr.ClprMessagePayload.class);
            final var serializedPayload = com.hedera.hapi.node.state.clpr.ClprMessagePayload.PROTOBUF
                    .toBytes(pbjPayload)
                    .toByteArray();
            final var nextHash = sha256(prevHash, serializedPayload);
            final var messageValue = ClprMessageValue.newBuilder()
                    .payload(pbjPayload)
                    .runningHashAfterProcessing(Bytes.wrap(nextHash))
                    .build();
            paths.add(leafPath(StateValue.newBuilder()
                    .clprServiceIMessageQueue(messageValue)
                    .build()));
            prevHash = nextHash;
        }

        return StateProof.PROTOBUF
                .toBytes(StateProof.newBuilder().paths(paths).build())
                .toByteArray();
    }

    /**
     * Computes the cumulative running hash after a sequence of payloads, starting from
     * {@code initialHash}. Use this to chain bundles: pass the result of one call as
     * {@code initialHash} for the next so the second bundle's proof leaves carry the
     * correct cumulative hash without replaying the first bundle's messages.
     */
    static byte[] runningHashAfter(final byte[] initialHash, final List<ClprMessagePayload> payloads) {
        var hash = initialHash;
        for (final var payload : payloads) {
            final var pbjPayload = protoToPbj(payload, com.hedera.hapi.node.state.clpr.ClprMessagePayload.class);
            final var serializedPayload = com.hedera.hapi.node.state.clpr.ClprMessagePayload.PROTOBUF
                    .toBytes(pbjPayload)
                    .toByteArray();
            hash = sha256(hash, serializedPayload);
        }
        return hash;
    }

    /**
     * Convenience overload that derives every argument from a fully-built
     * {@link ClprBundleContent}. The verifier projects:
     * <ul>
     *   <li>{@code metadata.state} ← channel leaf {@code status}</li>
     *   <li>{@code metadata.received_message_id} ← channel leaf {@code received_message_id}</li>
     *   <li>{@code metadata.received_running_hash} ← channel leaf {@code received_running_hash}</li>
     *   <li>{@code metadata.next_message_id} = {@code acked_message_id + 1 + msgCount}, so we
     *       back-solve {@code acked_message_id = next_message_id - 1 - msgCount}</li>
     *   <li>{@code metadata.sent_running_hash} ← last message leaf's running hash (recomputed
     *       from the payloads; any caller-supplied value is ignored)</li>
     * </ul>
     */
    static byte[] toBundleProofBytes(final ClprBundleContent bundle) {
        return toBundleProofBytes(bundle, new byte[32]);
    }

    /**
     * Like {@link #toBundleProofBytes(ClprBundleContent)} but starts the message-leaf running
     * hash chain from {@code initialRunningHash} instead of zeros. Use this for bundles after
     * the first: pass {@code runningHashAfter(new byte[32], firstBundle.getMessagesList())} so
     * the second bundle's proof leaves carry the correct cumulative hash without replaying the
     * first bundle's messages.
     */
    static byte[] toBundleProofBytes(final ClprBundleContent bundle, final byte[] initialRunningHash) {
        final var metadata = bundle.getMetadata();
        final var ackedMessageId = metadata.getNextMessageId() - 1 - bundle.getMessagesCount();
        return toBundleProofBytes(
                metadata.getStatus(),
                ackedMessageId,
                metadata.getReceivedMessageId(),
                metadata.getReceivedRunningHash().toByteArray(),
                bundle.getMessagesList(),
                initialRunningHash);
    }

    private static MerklePath leafPath(final StateValue value) {
        final var leaf =
                StateItem.PROTOBUF.toBytes(StateItem.newBuilder().value(value).build());
        return MerklePath.newBuilder().stateItemLeaf(leaf).build();
    }

    /** Spec §4.1 chain step: {@code SHA-256(prev || SHA-256(serialized_payload))}. */
    private static byte[] sha256(final byte[] previousHash, final byte[] serializedPayload) {
        try {
            final var payloadHash = MessageDigest.getInstance("SHA-256").digest(serializedPayload);
            final var outer = MessageDigest.getInstance("SHA-256");
            outer.update(previousHash);
            outer.update(payloadHash);
            return outer.digest();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
