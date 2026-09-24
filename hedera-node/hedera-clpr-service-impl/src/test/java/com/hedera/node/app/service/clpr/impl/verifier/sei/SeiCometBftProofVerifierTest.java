// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.sei;

import static com.hedera.node.app.service.clpr.impl.verifier.PbjTestUtils.appendUnknownField;
import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.endpointManifestCommitmentSlot;
import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.keccak256;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.bytesField;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.concat;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.messageField;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.varintField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprSeiBundlePayload;
import com.hedera.hapi.node.state.clpr.ClprSeiLedgerConfigurationPayload;
import com.hedera.hapi.node.state.clpr.SeiCommit;
import com.hedera.hapi.node.state.clpr.SeiHeader;
import com.hedera.hapi.node.state.clpr.SeiStateProof;
import com.hedera.hapi.node.state.clpr.SeiStorageProofEntry;
import com.hedera.hapi.node.state.clpr.SeiTrustAnchor;
import com.hedera.hapi.node.state.clpr.SeiValidatorSet;
import com.hedera.hapi.node.state.clpr.SeiValidatorSetUpdate;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link SeiCometBftProofVerifier} against the synthetic chain built by
 * {@link SyntheticSeiChain}: full happy paths for config and bundle verification, trust-anchor
 * rotation, and the rejection paths that matter for consensus safety.
 */
class SeiCometBftProofVerifierTest {

    private static final List<SyntheticSeiChain.Validator> VALIDATORS = SyntheticSeiChain.validators(40, 30, 20, 10);
    private static final byte[] SERVICE = SyntheticSeiChain.SERVICE_ADDRESS;
    private static final BigInteger CHANNEL_BASE_SLOT = BigInteger.valueOf(1000);
    private static final byte[] LAST_MESSAGE_RUNNING_HASH_SLOT = slot(BigInteger.valueOf(900));

    // ── slot fixtures ──

    /** The trust-anchor id format the verifier emits: {@code validator_set_hash || height}. */
    private static byte[] trustAnchorId(final SeiValidatorSet set, final long height) {
        final byte[] hash = SeiHashing.validatorSetHash(set);
        final byte[] id = new byte[hash.length + Long.BYTES];
        System.arraycopy(hash, 0, id, 0, hash.length);
        ByteBuffer.wrap(id, hash.length, Long.BYTES).putLong(height);
        return id;
    }

    private static byte[] slot(final int n) {
        return slot(BigInteger.valueOf(n));
    }

    private static byte[] slot(final BigInteger n) {
        final byte[] s = new byte[32];
        final byte[] bytes = n.toByteArray();
        final int copyLen = Math.min(bytes.length, s.length);
        System.arraycopy(bytes, bytes.length - copyLen, s, s.length - copyLen, copyLen);
        return s;
    }

    private static byte[][] queueStorageSlotsInVerifierOrder() {
        return new byte[][] {
            LAST_MESSAGE_RUNNING_HASH_SLOT,
            slot(CHANNEL_BASE_SLOT.add(BigInteger.ONE)),
            slot(CHANNEL_BASE_SLOT.add(BigInteger.TWO)),
            slot(CHANNEL_BASE_SLOT.add(BigInteger.valueOf(4))),
            slot(CHANNEL_BASE_SLOT.add(BigInteger.valueOf(5)))
        };
    }

    private static byte[][] queueSlotValues() {
        final byte[] lastMsgHash = SyntheticSeiChain.hash32("last-msg").toByteArray();
        final byte[] statusSlot = new byte[32];
        ByteBuffer.wrap(statusSlot, 3, 8).putLong(42L); // nextMessageId
        statusSlot[11] = 3; // status
        System.arraycopy(SyntheticSeiChain.sha20("peer-verifier"), 0, statusSlot, 12, 20);
        final byte[] receivedSlot = new byte[32];
        ByteBuffer.wrap(receivedSlot, 16, 8).putLong(17L); // receivedMessageId
        ByteBuffer.wrap(receivedSlot, 24, 8).putLong(16L); // ackedMessageId
        return new byte[][] {
            lastMsgHash,
            statusSlot,
            receivedSlot,
            SyntheticSeiChain.hash32("sent-running").toByteArray(),
            SyntheticSeiChain.hash32("received-running").toByteArray()
        };
    }

    private static SyntheticSeiChain.Chain bundleChain(
            final List<SyntheticSeiChain.Validator> signers, final List<SyntheticSeiChain.Validator> nextSet) {
        return SyntheticSeiChain.stateProof(
                VALIDATORS, signers, nextSet, queueStorageSlotsInVerifierOrder(), queueSlotValues());
    }

    private static byte[] anchorBytes() {
        return SeiTrustAnchor.PROTOBUF
                .toBytes(SeiTrustAnchor.newBuilder()
                        .chainId(SyntheticSeiChain.CHAIN_ID)
                        .height(SyntheticSeiChain.HEIGHT - 10)
                        .validatorSet(SyntheticSeiChain.toProtoSet(VALIDATORS))
                        .serviceAddress(Bytes.wrap(SERVICE))
                        .build())
                .toByteArray();
    }

    private static byte[] bundlePayload(final SyntheticSeiChain.Chain chain, final SeiValidatorSet nextSet) {
        final var builder = ClprSeiBundlePayload.newBuilder()
                .stateProof(chain.stateProof())
                .bundleContent(Bytes.wrap(new byte[] {1, 2, 3, 4}));
        if (nextSet != null) {
            builder.nextValidatorSet(nextSet);
        }
        return ClprSeiBundlePayload.PROTOBUF.toBytes(builder.build()).toByteArray();
    }

    /** A bundle with a chain of prior updates, a content state proof, and no current rotation. */
    private static byte[] bundlePayloadWithPriorUpdates(
            final SyntheticSeiChain.Chain chain, final List<SeiValidatorSetUpdate> priorUpdates) {
        return ClprSeiBundlePayload.PROTOBUF
                .toBytes(ClprSeiBundlePayload.newBuilder()
                        .stateProof(chain.stateProof())
                        .bundleContent(Bytes.wrap(new byte[] {1, 2, 3, 4}))
                        .priorValidatorSetUpdates(priorUpdates)
                        .build())
                .toByteArray();
    }

    /** A trust-update-only bundle: only prior validator-set updates, no state proof or content. */
    private static byte[] trustUpdateOnlyPayload(final List<SeiValidatorSetUpdate> priorUpdates) {
        return ClprSeiBundlePayload.PROTOBUF
                .toBytes(ClprSeiBundlePayload.newBuilder()
                        .priorValidatorSetUpdates(priorUpdates)
                        .build())
                .toByteArray();
    }

    /** Trust anchor at {@code height} for {@code set}, used to seed prior-update tests. */
    private static byte[] anchorBytes(final List<SyntheticSeiChain.Validator> set, final long height) {
        return SeiTrustAnchor.PROTOBUF
                .toBytes(SeiTrustAnchor.newBuilder()
                        .chainId(SyntheticSeiChain.CHAIN_ID)
                        .height(height)
                        .validatorSet(SyntheticSeiChain.toProtoSet(set))
                        .serviceAddress(Bytes.wrap(SERVICE))
                        .build())
                .toByteArray();
    }

    private static void verifyStateProof(final SeiStateProof stateProof, final List<SyntheticSeiChain.Validator> set) {
        SeiCometBftProofVerifier.verifyStateProof(stateProof, SyntheticSeiChain.toProtoSet(set), SERVICE);
    }

    private static SeiStateProof withCommit(final SeiStateProof proof, final SeiCommit commit) {
        return proof.copyBuilder()
                .signedHeader(
                        proof.signedHeaderOrThrow().copyBuilder().commit(commit).build())
                .build();
    }

    private static SeiStateProof withHeader(final SeiStateProof proof, final SeiHeader header) {
        return proof.copyBuilder()
                .signedHeader(
                        proof.signedHeaderOrThrow().copyBuilder().header(header).build())
                .build();
    }

    private static byte[] simpleCommitmentProof(final byte[] key, final byte[] value) {
        final byte[] leafOp =
                concat(varintField(1, 1), varintField(3, 1), varintField(4, 1), bytesField(5, new byte[] {0}));
        return messageField(1, concat(bytesField(1, key), bytesField(2, value), messageField(3, leafOp)));
    }

    private static byte[] nonExistenceCommitmentProof(final byte[] key, final byte[] leftCommitmentProof) {
        return messageField(
                2, concat(bytesField(1, key), messageField(2, existenceBodyFromCommitment(leftCommitmentProof))));
    }

    private static byte[] existenceBodyFromCommitment(final byte[] commitmentProof) {
        final var reader = new SeiProto.Reader(commitmentProof);
        final int tag = reader.readTag();
        if (tag != ((1 << 3) | SeiProto.WIRE_LENGTH_DELIMITED)) {
            throw new AssertionError("expected existence commitment proof");
        }
        final byte[] body = reader.readBytes();
        if (reader.hasMore()) {
            throw new AssertionError("expected single existence commitment proof");
        }
        return body;
    }

    @Nested
    class ConfigVerification {
        private static final long INITIAL_HEIGHT = 500L;

        private byte[] configPayload() {
            final var payload = ClprSeiLedgerConfigurationPayload.newBuilder()
                    .initialValidatorSet(SyntheticSeiChain.toProtoSet(VALIDATORS))
                    .initialValidatorSetHeight(INITIAL_HEIGHT)
                    .ledgerConfiguration(ClprLedgerConfiguration.newBuilder()
                            .chainId("sei:" + SyntheticSeiChain.CHAIN_ID)
                            .serviceAddress(Bytes.wrap(SERVICE))
                            .build())
                    .build();
            return ClprSeiLedgerConfigurationPayload.PROTOBUF.toBytes(payload).toByteArray();
        }

        @Test
        void happyPathDerivesBootstrapTrustAnchor() throws Exception {
            final var verified = SeiCometBftProofVerifier.verifyConfigPayload(configPayload());

            final var anchorBytes = verified.ledgerConfiguration().initialTrustAnchor();
            final var anchor = SeiTrustAnchor.PROTOBUF.parse(anchorBytes.toReadableSequentialData());
            assertThat(anchor.chainId()).isEqualTo(SyntheticSeiChain.CHAIN_ID);
            assertThat(anchor.height()).isEqualTo(INITIAL_HEIGHT);
            assertThat(anchor.serviceAddress().toByteArray()).isEqualTo(SERVICE);
            assertThat(anchor.validatorSetOrThrow().validators()).hasSize(4);
            assertThat(verified.ledgerConfiguration().initialTrustAnchorId().toByteArray())
                    .isEqualTo(trustAnchorId(SyntheticSeiChain.toProtoSet(VALIDATORS), INITIAL_HEIGHT));
        }

        @Test
        void rawChainIdAcceptedForTrustAnchor() throws Exception {
            final var payload = ClprSeiLedgerConfigurationPayload.newBuilder()
                    .initialValidatorSet(SyntheticSeiChain.toProtoSet(VALIDATORS))
                    .initialValidatorSetHeight(INITIAL_HEIGHT)
                    .ledgerConfiguration(ClprLedgerConfiguration.newBuilder()
                            .chainId(SyntheticSeiChain.CHAIN_ID)
                            .serviceAddress(Bytes.wrap(SERVICE))
                            .build())
                    .build();
            final var verified = SeiCometBftProofVerifier.verifyConfigPayload(
                    ClprSeiLedgerConfigurationPayload.PROTOBUF.toBytes(payload).toByteArray());
            final var anchor = SeiTrustAnchor.PROTOBUF.parse(
                    verified.ledgerConfiguration().initialTrustAnchor().toReadableSequentialData());
            assertThat(anchor.chainId()).isEqualTo(SyntheticSeiChain.CHAIN_ID);
        }

        @Test
        void garbageRejected() {
            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyConfigPayload(new byte[] {0x08}))
                    .isInstanceOf(ProofException.class);
        }

        @Test
        void nonPositiveInitialHeightRejected() {
            for (final long initialHeight : List.of(0L, -1L)) {
                final var payload = ClprSeiLedgerConfigurationPayload.newBuilder()
                        .initialValidatorSet(SyntheticSeiChain.toProtoSet(VALIDATORS))
                        .initialValidatorSetHeight(initialHeight)
                        .ledgerConfiguration(ClprLedgerConfiguration.newBuilder()
                                .chainId("sei:" + SyntheticSeiChain.CHAIN_ID)
                                .serviceAddress(Bytes.wrap(SERVICE))
                                .build())
                        .build();

                assertThatThrownBy(() ->
                                SeiCometBftProofVerifier.verifyConfigPayload(ClprSeiLedgerConfigurationPayload.PROTOBUF
                                        .toBytes(payload)
                                        .toByteArray()))
                        .isInstanceOf(ProofException.class)
                        .hasMessageContaining("initial_validator_set_height must be positive");
            }
        }

        @Test
        void serviceAddressMustBeTwentyBytes() {
            final var payload = ClprSeiLedgerConfigurationPayload.newBuilder()
                    .initialValidatorSet(SyntheticSeiChain.toProtoSet(VALIDATORS))
                    .initialValidatorSetHeight(INITIAL_HEIGHT)
                    .ledgerConfiguration(ClprLedgerConfiguration.newBuilder()
                            .serviceAddress(Bytes.wrap(new byte[19]))
                            .build())
                    .build();

            assertThatThrownBy(() ->
                            SeiCometBftProofVerifier.verifyConfigPayload(ClprSeiLedgerConfigurationPayload.PROTOBUF
                                    .toBytes(payload)
                                    .toByteArray()))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("service_address must be 20 bytes");
        }

        @Test
        void requiredConfigFieldsMustBePresent() {
            final byte[] bytes = ClprSeiLedgerConfigurationPayload.PROTOBUF
                    .toBytes(ClprSeiLedgerConfigurationPayload.newBuilder()
                            .ledgerConfiguration(ClprLedgerConfiguration.DEFAULT)
                            .build())
                    .toByteArray();

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyConfigPayload(bytes))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("initial_validator_set is missing");
        }

        @Test
        void chainIdMustBePresent() {
            final var payload = ClprSeiLedgerConfigurationPayload.newBuilder()
                    .initialValidatorSet(SyntheticSeiChain.toProtoSet(VALIDATORS))
                    .initialValidatorSetHeight(INITIAL_HEIGHT)
                    .ledgerConfiguration(ClprLedgerConfiguration.newBuilder()
                            .serviceAddress(Bytes.wrap(SERVICE))
                            .build())
                    .build();

            assertThatThrownBy(() ->
                            SeiCometBftProofVerifier.verifyConfigPayload(ClprSeiLedgerConfigurationPayload.PROTOBUF
                                    .toBytes(payload)
                                    .toByteArray()))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("chain_id is empty");
        }

        @Test
        void validatorSetMustBeValid() {
            final var payload = ClprSeiLedgerConfigurationPayload.newBuilder()
                    .initialValidatorSet(SeiValidatorSet.DEFAULT)
                    .initialValidatorSetHeight(INITIAL_HEIGHT)
                    .ledgerConfiguration(ClprLedgerConfiguration.newBuilder()
                            .chainId("sei:" + SyntheticSeiChain.CHAIN_ID)
                            .serviceAddress(Bytes.wrap(SERVICE))
                            .build())
                    .build();

            assertThatThrownBy(() ->
                            SeiCometBftProofVerifier.verifyConfigPayload(ClprSeiLedgerConfigurationPayload.PROTOBUF
                                    .toBytes(payload)
                                    .toByteArray()))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("validator set is empty");
        }

        @Test
        void unknownFieldRejected() {
            // Spec §1: "Implementations MUST reject messages containing unrecognized fields."
            // Serialize a valid payload, append a record for proto field #255 (which the schema
            // doesn't define), and expect the strict parse to reject it.
            final byte[] payloadWithUnknown = appendUnknownField(configPayload());

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyConfigPayload(payloadWithUnknown))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("not a valid ClprSeiLedgerConfigurationPayload");
        }
    }

    @Nested
    class BundleVerification {
        @Test
        void happyPathProvesQueueMetadataAndContent() {
            final var chain = bundleChain(VALIDATORS, VALIDATORS);
            final var verified = SeiCometBftProofVerifier.verifyBundle(bundlePayload(chain, null), anchorBytes());

            assertThat(verified.blockHash32()).isEqualTo(chain.headerHash());
            assertThat(verified.bundleContentBytes()).isEqualTo(new byte[] {1, 2, 3, 4});
            assertThat(verified.newTrustAnchor()).isNull();
            final var metadata = verified.queueMetadata();
            assertThat(metadata.nextMessageId()).isEqualTo(42L);
            assertThat(metadata.status()).isEqualTo(3);
            assertThat(metadata.receivedMessageId()).isEqualTo(17L);
            assertThat(metadata.lastMessageRunningHash())
                    .isEqualTo(SyntheticSeiChain.hash32("last-msg").toByteArray());
            assertThat(metadata.sentRunningHash())
                    .isEqualTo(SyntheticSeiChain.hash32("sent-running").toByteArray());
            assertThat(metadata.receivedRunningHash())
                    .isEqualTo(SyntheticSeiChain.hash32("received-running").toByteArray());
        }

        @Test
        void storageProofsCanArriveInChannelFirstRelayOrder() {
            final var chain = bundleChain(VALIDATORS, VALIDATORS);
            final var entries = new ArrayList<>(chain.stateProof().storageProofs());
            final var oldRelayOrder =
                    List.of(entries.get(1), entries.get(2), entries.get(3), entries.get(4), entries.get(0));
            final var stateProof = chain.stateProof()
                    .copyBuilder()
                    .storageProofs(oldRelayOrder)
                    .build();

            final var proven = SeiCometBftProofVerifier.verifyStateProof(
                    stateProof, SyntheticSeiChain.toProtoSet(VALIDATORS), SERVICE);
            final var metadata = SeiCometBftProofVerifier.decodeQueueMetadata(proven.slotValues());

            assertThat(metadata.nextMessageId()).isEqualTo(42L);
            assertThat(metadata.lastMessageRunningHash())
                    .isEqualTo(SyntheticSeiChain.hash32("last-msg").toByteArray());
            assertThat(metadata.receivedRunningHash())
                    .isEqualTo(SyntheticSeiChain.hash32("received-running").toByteArray());
        }

        @Test
        void emptyMessageBundleAcceptsFourChannelProofs() {
            final var slots = queueStorageSlotsInVerifierOrder();
            final var values = queueSlotValues();
            final var chain = SyntheticSeiChain.stateProof(
                    VALIDATORS,
                    VALIDATORS,
                    VALIDATORS,
                    Arrays.copyOfRange(slots, 1, slots.length),
                    Arrays.copyOfRange(values, 1, values.length));

            final var verified = SeiCometBftProofVerifier.verifyBundle(bundlePayload(chain, null), anchorBytes());

            assertThat(verified.queueMetadata().nextMessageId()).isEqualTo(42L);
            assertThat(verified.queueMetadata().receivedMessageId()).isEqualTo(17L);
            assertThat(verified.queueMetadata().hasLastMessageProof()).isFalse();
            assertThat(verified.queueMetadata().lastMessageRunningHash()).isEqualTo(new byte[32]);
        }

        @Test
        void nonExistenceStorageProofMapsToZeroWord() {
            final var slots = queueStorageSlotsInVerifierOrder();
            final var values = queueSlotValues();
            final var chain = SyntheticSeiChain.stateProof(
                    VALIDATORS, VALIDATORS, VALIDATORS, Arrays.copyOf(slots, 4), Arrays.copyOf(values, 4));
            final var entries = new ArrayList<>(chain.stateProof().storageProofs());
            final var leftNeighbor = entries.get(entries.size() - 1);
            final byte[] missingKey = concat(new byte[] {0x03}, SERVICE, slots[4]);
            entries.add(SeiStorageProofEntry.newBuilder()
                    .key(Bytes.wrap(missingKey))
                    .value(Bytes.EMPTY)
                    .iavlProof(Bytes.wrap(nonExistenceCommitmentProof(
                            missingKey, leftNeighbor.iavlProof().toByteArray())))
                    .build());

            final var proven = SeiCometBftProofVerifier.verifyStateProof(
                    chain.stateProof().copyBuilder().storageProofs(entries).build(),
                    SyntheticSeiChain.toProtoSet(VALIDATORS),
                    SERVICE);

            assertThat(proven.slotValues()[4]).isEqualTo(new byte[32]);
        }

        @Test
        void quorumJustAboveTwoThirdsAccepted() {
            // signers 40 + 30 = 70 of 100: 210 > 200
            final var chain = bundleChain(VALIDATORS.subList(0, 2), VALIDATORS);
            final var verified = SeiCometBftProofVerifier.verifyBundle(bundlePayload(chain, null), anchorBytes());
            assertThat(verified.queueMetadata().nextMessageId()).isEqualTo(42L);
        }

        @Test
        void insufficientQuorumRejected() {
            // signers 40 + 20 = 60 of 100: 180 <= 200
            final var chain = bundleChain(List.of(VALIDATORS.get(0), VALIDATORS.get(2)), VALIDATORS);
            final byte[] payload = bundlePayload(chain, null);
            final byte[] anchor = anchorBytes();
            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("2/3");
        }

        @Test
        void unknownValidatorSetRejected() {
            final var strangers = SyntheticSeiChain.validators(60, 40);
            final var chain = SyntheticSeiChain.stateProof(
                    strangers,
                    strangers,
                    strangers,
                    new byte[][] {slot(1), slot(2), slot(3), slot(4), slot(5)},
                    queueSlotValues());
            final byte[] payload = bundlePayload(chain, null);
            final byte[] anchor = anchorBytes();
            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("validators_hash");
        }

        @Test
        void wrongChainIdInAnchorRejected() {
            final var chain = bundleChain(VALIDATORS, VALIDATORS);
            final byte[] anchor = SeiTrustAnchor.PROTOBUF
                    .toBytes(SeiTrustAnchor.newBuilder()
                            .chainId("pacific-1")
                            .height(1)
                            .validatorSet(SyntheticSeiChain.toProtoSet(VALIDATORS))
                            .serviceAddress(Bytes.wrap(SERVICE))
                            .build())
                    .toByteArray();
            final byte[] payload = bundlePayload(chain, null);
            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("chain_id");
        }

        @Test
        void headerOlderThanAnchorRejected() {
            final var chain = bundleChain(VALIDATORS, VALIDATORS);
            final byte[] anchor = SeiTrustAnchor.PROTOBUF
                    .toBytes(SeiTrustAnchor.newBuilder()
                            .chainId(SyntheticSeiChain.CHAIN_ID)
                            .height(SyntheticSeiChain.HEIGHT + 1)
                            .validatorSet(SyntheticSeiChain.toProtoSet(VALIDATORS))
                            .serviceAddress(Bytes.wrap(SERVICE))
                            .build())
                    .toByteArray();
            final byte[] payload = bundlePayload(chain, null);
            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("older than trust anchor");
        }

        @Test
        void slotKeyForDifferentContractRejected() {
            // proofs are valid for SERVICE_ADDRESS; anchor names a different service contract
            final var chain = bundleChain(VALIDATORS, VALIDATORS);
            final byte[] anchor = SeiTrustAnchor.PROTOBUF
                    .toBytes(SeiTrustAnchor.newBuilder()
                            .chainId(SyntheticSeiChain.CHAIN_ID)
                            .height(1)
                            .validatorSet(SyntheticSeiChain.toProtoSet(VALIDATORS))
                            .serviceAddress(Bytes.wrap(SyntheticSeiChain.sha20("other-contract")))
                            .build())
                    .toByteArray();
            final byte[] payload = bundlePayload(chain, null);
            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("does not target the CLPR service contract");
        }

        @Test
        void wrongSlotCountRejected() {
            final var chain = SyntheticSeiChain.stateProof(
                    VALIDATORS, VALIDATORS, VALIDATORS, new byte[][] {slot(1), slot(2)}, new byte[][] {
                        queueSlotValues()[0], queueSlotValues()[1]
                    });
            final byte[] payload = bundlePayload(chain, null);
            final byte[] anchor = anchorBytes();
            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("expected 4 or 5 proven slot values");
        }

        @Test
        void malformedTrustAnchorRejected() {
            final var chain = bundleChain(VALIDATORS, VALIDATORS);
            final byte[] payload = bundlePayload(chain, null);

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, new byte[] {0x08}))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("trustAnchor is not a valid");
        }

        @Test
        void trustAnchorServiceAddressMustBeTwentyBytes() {
            final var chain = bundleChain(VALIDATORS, VALIDATORS);
            final byte[] anchor = SeiTrustAnchor.PROTOBUF
                    .toBytes(SeiTrustAnchor.newBuilder()
                            .chainId(SyntheticSeiChain.CHAIN_ID)
                            .height(1)
                            .validatorSet(SyntheticSeiChain.toProtoSet(VALIDATORS))
                            .serviceAddress(Bytes.wrap(new byte[19]))
                            .build())
                    .toByteArray();

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(bundlePayload(chain, null), anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("service_address must be 20 bytes");
        }

        @Test
        void trustAnchorChainIdMustBePresent() {
            final var chain = bundleChain(VALIDATORS, VALIDATORS);
            final byte[] anchor = SeiTrustAnchor.PROTOBUF
                    .toBytes(SeiTrustAnchor.newBuilder()
                            .height(1)
                            .validatorSet(SyntheticSeiChain.toProtoSet(VALIDATORS))
                            .serviceAddress(Bytes.wrap(SERVICE))
                            .build())
                    .toByteArray();

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(bundlePayload(chain, null), anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("chain_id is empty");
        }

        @Test
        void malformedBundlePayloadRejected() {
            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(new byte[] {0x08}, anchorBytes()))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("bundlePayload is not a valid");
        }

        @Test
        void unknownFieldInBundlePayloadRejected() {
            // Spec §1: "Implementations MUST reject messages containing unrecognized fields."
            // Serialize a valid bundlePayload, append a record for proto field #255 (which the
            // schema doesn't define), and expect the strict parse to reject it.
            final var chain = bundleChain(VALIDATORS, VALIDATORS);
            final byte[] payloadWithUnknown = appendUnknownField(bundlePayload(chain, null));

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payloadWithUnknown, anchorBytes()))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("bundlePayload is not a valid ClprSeiBundlePayload");
        }
    }

    @Nested
    class StateProofRejections {
        @Test
        void signedHeaderIsRequired() {
            assertThatThrownBy(() -> verifyStateProof(SeiStateProof.DEFAULT, VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("signed_header is missing");
        }

        @Test
        void headerMustBeWellFormed() {
            final var chain = bundleChain(VALIDATORS, VALIDATORS);

            assertThatThrownBy(() -> verifyStateProof(
                            withHeader(
                                    chain.stateProof(),
                                    chain.header().copyBuilder().chainId("").build()),
                            VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("chain_id is empty");
            assertThatThrownBy(() -> verifyStateProof(
                            withHeader(
                                    chain.stateProof(),
                                    chain.header().copyBuilder().height(0).build()),
                            VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("height must be positive");
            assertThatThrownBy(() -> verifyStateProof(
                            withHeader(
                                    chain.stateProof(),
                                    chain.header()
                                            .copyBuilder()
                                            .validatorsHash(Bytes.wrap(new byte[31]))
                                            .build()),
                            VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("validators_hash, next_validators_hash, and app_hash");
            assertThatThrownBy(() -> verifyStateProof(
                            withHeader(
                                    chain.stateProof(),
                                    chain.header()
                                            .copyBuilder()
                                            .appHash(Bytes.wrap(new byte[31]))
                                            .build()),
                            VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("validators_hash, next_validators_hash, and app_hash");
            assertThatThrownBy(() -> verifyStateProof(
                            withHeader(
                                    chain.stateProof(),
                                    chain.header()
                                            .copyBuilder()
                                            .nextValidatorsHash(Bytes.wrap(new byte[31]))
                                            .build()),
                            VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("validators_hash, next_validators_hash, and app_hash");
        }

        @Test
        void commitPartSetHeaderMustBeValid() {
            final var chain = bundleChain(VALIDATORS, VALIDATORS);

            assertThatThrownBy(() -> verifyStateProof(
                            withCommit(
                                    chain.stateProof(),
                                    chain.commit().copyBuilder().partSetTotal(0).build()),
                            VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("part-set header");
            assertThatThrownBy(() -> verifyStateProof(
                            withCommit(
                                    chain.stateProof(),
                                    chain.commit()
                                            .copyBuilder()
                                            .partSetHash(Bytes.wrap(new byte[31]))
                                            .build()),
                            VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("part-set header");
        }

        @Test
        void signersBitsMustMatchValidatorSet() {
            final var chain = bundleChain(VALIDATORS, VALIDATORS);

            assertThatThrownBy(() -> verifyStateProof(
                            withCommit(
                                    chain.stateProof(),
                                    chain.commit()
                                            .copyBuilder()
                                            .signersBits(Bytes.EMPTY)
                                            .build()),
                            VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("signers_bits must be");
            assertThatThrownBy(() -> verifyStateProof(
                            withCommit(
                                    chain.stateProof(),
                                    chain.commit()
                                            .copyBuilder()
                                            .signersBits(Bytes.wrap(new byte[] {(byte) 0xF8}))
                                            .build()),
                            VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("outside the trusted set");
        }

        @Test
        void selectedSignaturesMustBePresentValidAndExact() {
            final var chain = bundleChain(VALIDATORS, VALIDATORS);
            final var commit = chain.commit();

            assertThatThrownBy(() -> verifyStateProof(
                            withCommit(
                                    chain.stateProof(),
                                    commit.copyBuilder()
                                            .signatures(commit.signatures()
                                                    .subList(
                                                            0,
                                                            commit.signatures().size() - 1))
                                            .build()),
                            VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("more validators than signatures");

            final var badSignatures = new ArrayList<>(commit.signatures());
            badSignatures.set(
                    0,
                    badSignatures
                            .getFirst()
                            .copyBuilder()
                            .signature(Bytes.wrap(new byte[64]))
                            .build());
            assertThatThrownBy(() -> verifyStateProof(
                            withCommit(
                                    chain.stateProof(),
                                    commit.copyBuilder()
                                            .signatures(badSignatures)
                                            .build()),
                            VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("invalid commit signature");

            final var extraSignatures = new ArrayList<>(commit.signatures());
            extraSignatures.add(commit.signatures().getFirst());
            assertThatThrownBy(() -> verifyStateProof(
                            withCommit(
                                    chain.stateProof(),
                                    commit.copyBuilder()
                                            .signatures(extraSignatures)
                                            .build()),
                            VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("signatures but signers_bits selects");
        }

        @Test
        void votingPowerOverflowRejected() {
            final var overflowingTotal = SyntheticSeiChain.validators(Long.MAX_VALUE, 1);
            final var totalChain = SyntheticSeiChain.stateProof(
                    overflowingTotal, overflowingTotal, overflowingTotal, new byte[][] {slot(1)}, new byte[][] {
                        queueSlotValues()[0]
                    });

            assertThatThrownBy(() -> verifyStateProof(totalChain.stateProof(), overflowingTotal))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("voting power overflow");

            final var overflowingQuorumMath = SyntheticSeiChain.validators(Long.MAX_VALUE);
            final var quorumChain = SyntheticSeiChain.stateProof(
                    overflowingQuorumMath,
                    overflowingQuorumMath,
                    overflowingQuorumMath,
                    new byte[][] {slot(1)},
                    new byte[][] {queueSlotValues()[0]});

            assertThatThrownBy(() -> verifyStateProof(quorumChain.stateProof(), overflowingQuorumMath))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("voting power overflow");
        }

        @Test
        void storeKeyAndMultistoreProofMustMatch() {
            final var chain = bundleChain(VALIDATORS, VALIDATORS);

            assertThatThrownBy(() -> verifyStateProof(
                            chain.stateProof()
                                    .copyBuilder()
                                    .storeKey(Bytes.wrap("wasm".getBytes(StandardCharsets.UTF_8)))
                                    .build(),
                            VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("store_key must be 'evm'");

            final var badMultistoreProof =
                    simpleCommitmentProof("bad".getBytes(StandardCharsets.UTF_8), new byte[] {1, 2, 3});
            assertThatThrownBy(() -> verifyStateProof(
                            chain.stateProof()
                                    .copyBuilder()
                                    .multistoreProof(Bytes.wrap(badMultistoreProof))
                                    .build(),
                            VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("multistore proof key");
        }

        @Test
        void storageProofValueMustBeThirtyTwoBytes() {
            final var chain = SyntheticSeiChain.stateProof(
                    VALIDATORS, VALIDATORS, VALIDATORS, new byte[][] {slot(1)}, new byte[][] {new byte[31]});

            assertThatThrownBy(() -> verifyStateProof(chain.stateProof(), VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("value must be 32 bytes");
        }

        @Test
        void headerTimeWithNegativeSecondsRejected() {
            final var chain = bundleChain(VALIDATORS, VALIDATORS);
            final var invalidTime = Timestamp.newBuilder().seconds(-1).nanos(0).build();
            assertThatThrownBy(() -> verifyStateProof(
                            withHeader(
                                    chain.stateProof(),
                                    chain.header()
                                            .copyBuilder()
                                            .time(invalidTime)
                                            .build()),
                            VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("invalid timestamp");
        }

        @Test
        void headerTimeWithNanosOutOfRangeRejected() {
            final var chain = bundleChain(VALIDATORS, VALIDATORS);
            final var invalidTime =
                    Timestamp.newBuilder().seconds(1000).nanos(1_000_000_000).build();
            assertThatThrownBy(() -> verifyStateProof(
                            withHeader(
                                    chain.stateProof(),
                                    chain.header()
                                            .copyBuilder()
                                            .time(invalidTime)
                                            .build()),
                            VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("invalid timestamp");
        }

        @Test
        void commitSigWithNegativeTimestampRejected() {
            final var chain = bundleChain(VALIDATORS, VALIDATORS);
            final var invalidTs = Timestamp.newBuilder().seconds(-1).nanos(0).build();
            final var modifiedSigs = new java.util.ArrayList<>(chain.commit().signatures());
            modifiedSigs.set(
                    0, modifiedSigs.get(0).copyBuilder().timestamp(invalidTs).build());
            final var modifiedCommit =
                    chain.commit().copyBuilder().signatures(modifiedSigs).build();
            assertThatThrownBy(() -> verifyStateProof(withCommit(chain.stateProof(), modifiedCommit), VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("invalid timestamp");
        }

        @Test
        void commitSigWithNanosOutOfRangeRejected() {
            final var chain = bundleChain(VALIDATORS, VALIDATORS);
            final var invalidTs =
                    Timestamp.newBuilder().seconds(1000).nanos(1_000_000_000).build();
            final var modifiedSigs = new java.util.ArrayList<>(chain.commit().signatures());
            modifiedSigs.set(
                    0, modifiedSigs.get(0).copyBuilder().timestamp(invalidTs).build());
            final var modifiedCommit =
                    chain.commit().copyBuilder().signatures(modifiedSigs).build();
            assertThatThrownBy(() -> verifyStateProof(withCommit(chain.stateProof(), modifiedCommit), VALIDATORS))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("invalid timestamp");
        }

        @Test
        void stateProofRecordsRequireNonNullComponents() {
            assertThatThrownBy(
                            () -> new SeiCometBftProofVerifier.QueueMetadata(1, null, 2, new byte[32], 1, new byte[32]))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new SeiCometBftProofVerifier.VerifiedConfig(null, new byte[0]))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class TrustAnchorRotation {
        @Test
        void verifiedRotationEmitsSuccessorAnchor() throws Exception {
            final var successors = SyntheticSeiChain.validators(25, 25, 25, 25, 25);
            final var chain = bundleChain(VALIDATORS, successors);
            final var verified = SeiCometBftProofVerifier.verifyBundle(
                    bundlePayload(chain, SyntheticSeiChain.toProtoSet(successors)), anchorBytes());

            assertThat(verified.newTrustAnchor()).isNotNull();
            final var successor = SeiTrustAnchor.PROTOBUF.parse(
                    Bytes.wrap(verified.newTrustAnchor()).toReadableSequentialData());
            assertThat(successor.validatorSetOrThrow().validators()).hasSize(5);
            // successor anchor is active one height past the rotating header
            assertThat(successor.height()).isEqualTo(SyntheticSeiChain.HEIGHT + 1);
            assertThat(verified.newTrustAnchorId())
                    .isEqualTo(trustAnchorId(SyntheticSeiChain.toProtoSet(successors), SyntheticSeiChain.HEIGHT + 1));
        }

        @Test
        void rotationEvidenceNotMatchingNextValidatorsHashRejected() {
            final var successors = SyntheticSeiChain.validators(25, 25, 25, 25, 25);
            final var impostors = SyntheticSeiChain.validators(99, 1);
            final var chain = bundleChain(VALIDATORS, successors);
            final byte[] payload = bundlePayload(chain, SyntheticSeiChain.toProtoSet(impostors));
            final byte[] anchor = anchorBytes();
            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("next_validators_hash");
        }

        @Test
        void omittedRotationEvidenceRejectedWhenHeaderAnnouncesValidatorSetChange() {
            final var successors = SyntheticSeiChain.validators(25, 25, 25, 25, 25);
            final var chain = bundleChain(VALIDATORS, successors);
            final byte[] payload = bundlePayload(chain, null);
            final byte[] anchor = anchorBytes();

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("next_validator_set is missing");
        }

        @Test
        void redundantCurrentRotationEvidenceRejected() {
            final var chain = bundleChain(VALIDATORS, VALIDATORS);
            final byte[] payload = bundlePayload(chain, SyntheticSeiChain.toProtoSet(VALIDATORS));
            final byte[] anchor = anchorBytes();

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("does not change the validator set");
        }
    }

    @Nested
    class PriorValidatorSetUpdates {
        private final List<SyntheticSeiChain.Validator> setB = SyntheticSeiChain.validators(25, 25, 25, 25, 25);
        private final List<SyntheticSeiChain.Validator> setC = SyntheticSeiChain.validators(20, 20, 20, 20, 20, 20);

        @Test
        void singlePriorUpdateAdvancesAnchorThenProvesContent() {
            final var update = SyntheticSeiChain.validatorSetUpdate(995, VALIDATORS, VALIDATORS, setB);
            final var contentChain = SyntheticSeiChain.stateProof(
                    setB, setB, setB, queueStorageSlotsInVerifierOrder(), queueSlotValues());

            final var verified = SeiCometBftProofVerifier.verifyBundle(
                    bundlePayloadWithPriorUpdates(contentChain, List.of(update)), anchorBytes(VALIDATORS, 990));

            assertThat(verified.queueMetadata().nextMessageId()).isEqualTo(42L);
            final var successor = parseAnchor(verified.newTrustAnchor());
            assertThat(successor.validatorSetOrThrow().validators()).hasSize(5);
            assertThat(successor.height()).isEqualTo(996L);
            assertThat(verified.newTrustAnchorId()).isEqualTo(trustAnchorId(SyntheticSeiChain.toProtoSet(setB), 996L));
        }

        @Test
        void multiplePriorUpdatesChainToLatestSet() {
            final var update1 = SyntheticSeiChain.validatorSetUpdate(993, VALIDATORS, VALIDATORS, setB);
            final var update2 = SyntheticSeiChain.validatorSetUpdate(994, setB, setB, setC);
            final var contentChain = SyntheticSeiChain.stateProof(
                    setC, setC, setC, queueStorageSlotsInVerifierOrder(), queueSlotValues());

            final var verified = SeiCometBftProofVerifier.verifyBundle(
                    bundlePayloadWithPriorUpdates(contentChain, List.of(update1, update2)),
                    anchorBytes(VALIDATORS, 990));

            assertThat(verified.queueMetadata().nextMessageId()).isEqualTo(42L);
            final var successor = parseAnchor(verified.newTrustAnchor());
            assertThat(successor.validatorSetOrThrow().validators()).hasSize(6);
            assertThat(successor.height()).isEqualTo(995L);
        }

        @Test
        void trustUpdateOnlyBundleReturnsAnchorWithoutContent() {
            final var update = SyntheticSeiChain.validatorSetUpdate(995, VALIDATORS, VALIDATORS, setB);

            final var verified = SeiCometBftProofVerifier.verifyBundle(
                    trustUpdateOnlyPayload(List.of(update)), anchorBytes(VALIDATORS, 990));

            assertThat(verified.blockHash32()).isNull();
            assertThat(verified.bundleContentBytes()).isNull();
            assertThat(verified.queueMetadata()).isNull();
            final var successor = parseAnchor(verified.newTrustAnchor());
            assertThat(successor.height()).isEqualTo(996L);
            assertThat(verified.newTrustAnchorId()).isEqualTo(trustAnchorId(SyntheticSeiChain.toProtoSet(setB), 996L));
        }

        @Test
        void emptyTrustUpdateOnlyBundleRejected() {
            final byte[] payload = trustUpdateOnlyPayload(List.of());
            final byte[] anchor = anchorBytes(VALIDATORS, 990);
            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("neither state_proof nor prior_validator_set_updates");
        }

        @Test
        void tooManyPriorUpdatesRejectedBeforeVerification() {
            final var update = SyntheticSeiChain.validatorSetUpdate(995, VALIDATORS, VALIDATORS, setB);
            final var updates =
                    java.util.Collections.nCopies(SeiCometBftProofVerifier.MAX_PRIOR_VALIDATOR_SET_UPDATES + 1, update);
            final byte[] payload = trustUpdateOnlyPayload(updates);

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchorBytes(VALIDATORS, 990)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining(
                            "must contain at most " + SeiCometBftProofVerifier.MAX_PRIOR_VALIDATOR_SET_UPDATES);
        }

        @Test
        void priorUpdateWithMismatchedNextSetRejected() {
            final var impostors = SyntheticSeiChain.validators(99, 1);
            // header announces setB, but the carried next_validator_set is a different set
            final var badUpdate = SyntheticSeiChain.validatorSetUpdate(995, VALIDATORS, VALIDATORS, setB)
                    .copyBuilder()
                    .nextValidatorSet(SyntheticSeiChain.toProtoSet(impostors))
                    .build();
            final byte[] payload = trustUpdateOnlyPayload(List.of(badUpdate));
            final byte[] anchor = anchorBytes(VALIDATORS, 990);
            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("next_validators_hash");
        }

        @Test
        void priorUpdateWithInsufficientQuorumRejected() {
            // signers 40 + 20 = 60 of 100: 180 <= 200
            final var update = SyntheticSeiChain.validatorSetUpdate(
                    995, VALIDATORS, List.of(VALIDATORS.get(0), VALIDATORS.get(2)), setB);
            final byte[] payload = trustUpdateOnlyPayload(List.of(update));
            final byte[] anchor = anchorBytes(VALIDATORS, 990);
            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("2/3");
        }

        @Test
        void priorUpdateHeaderSignedByWrongSetRejected() {
            // update's header is signed by setB, but the anchor's trusted set is VALIDATORS
            final var update = SyntheticSeiChain.validatorSetUpdate(995, setB, setB, setC);
            final byte[] payload = trustUpdateOnlyPayload(List.of(update));
            final byte[] anchor = anchorBytes(VALIDATORS, 990);
            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("validators_hash");
        }

        @Test
        void priorUpdateOlderThanAnchorRejected() {
            // update header height 989 is older than the anchor height 990
            final var update = SyntheticSeiChain.validatorSetUpdate(989, VALIDATORS, VALIDATORS, setB);
            final byte[] payload = trustUpdateOnlyPayload(List.of(update));
            final byte[] anchor = anchorBytes(VALIDATORS, 990);
            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("older than trust anchor");
        }

        @Test
        void priorUpdateMustChangeValidatorSet() {
            final var update = SyntheticSeiChain.validatorSetUpdate(995, VALIDATORS, VALIDATORS, VALIDATORS);
            final byte[] payload = trustUpdateOnlyPayload(List.of(update));
            final byte[] anchor = anchorBytes(VALIDATORS, 990);

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("does not change the validator set");
        }

        @Test
        void rotationHeightOverflowRejected() {
            final var update = SyntheticSeiChain.validatorSetUpdate(Long.MAX_VALUE, VALIDATORS, VALIDATORS, setB);
            final byte[] payload = trustUpdateOnlyPayload(List.of(update));
            final byte[] anchor = anchorBytes(VALIDATORS, 990);

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("height cannot be incremented");
        }

        private SeiTrustAnchor parseAnchor(final byte[] anchor) {
            assertThat(anchor).isNotNull();
            try {
                return SeiTrustAnchor.PROTOBUF.parse(Bytes.wrap(anchor).toReadableSequentialData());
            } catch (final Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    /**
     * Exercises the optional endpoint-manifest advance (spec §4.2 Step 1b) verified by
     * {@link SeiCometBftProofVerifier}'s private {@code verifyEndpointManifestProof}, reached through
     * {@code verifyBundle} by carrying {@code manifest_storage_proof} + {@code endpoint_manifest}. The
     * fixture proves the slot-18 commitment against the same store root as the queue slots.
     */
    @Nested
    class EndpointManifestProof {
        private static final byte[] OTHER_SERVICE = SyntheticSeiChain.sha20("other-service-contract");

        private static ClprEndpointManifest manifest(final long version, final byte[] serviceAddress) {
            return ClprEndpointManifest.newBuilder()
                    .version(version)
                    .serviceAddress(Bytes.wrap(serviceAddress))
                    .endpoints(ClprEndpoint.newBuilder()
                            .tlsCertificate(Bytes.wrap("endpoint-cert".getBytes(StandardCharsets.UTF_8)))
                            .build())
                    .build();
        }

        private static byte[] preimageOf(final ClprEndpointManifest manifest) {
            return ClprEndpointManifest.PROTOBUF.toBytes(manifest).toByteArray();
        }

        /**
         * Builds a chain over the five queue slots plus one manifest slot carrying {@code commitment},
         * all committed by the same signed header (hence the same store root).
         */
        private static SyntheticSeiChain.Chain chainWithManifestSlot(
                final byte[] manifestSlot, final byte[] commitment) {
            final byte[][] queueSlots = queueStorageSlotsInVerifierOrder();
            final byte[][] queueValues = queueSlotValues();
            final byte[][] allSlots = Arrays.copyOf(queueSlots, queueSlots.length + 1);
            final byte[][] allValues = Arrays.copyOf(queueValues, queueValues.length + 1);
            allSlots[queueSlots.length] = manifestSlot;
            allValues[queueValues.length] = commitment;
            return SyntheticSeiChain.stateProof(VALIDATORS, VALIDATORS, VALIDATORS, allSlots, allValues);
        }

        /** The last entry (the manifest slot); the queue entries precede it. */
        private static SeiStorageProofEntry manifestEntry(final SyntheticSeiChain.Chain chain) {
            final var entries = chain.stateProof().storageProofs();
            return entries.get(entries.size() - 1);
        }

        /** The bundle's state proof: the queue slots only, preserving the fixed queue-slot ordering. */
        private static SeiStateProof bundleStateProof(final SyntheticSeiChain.Chain chain) {
            final var entries = chain.stateProof().storageProofs();
            return chain.stateProof()
                    .copyBuilder()
                    .storageProofs(entries.subList(0, entries.size() - 1))
                    .build();
        }

        private static byte[] manifestBundle(
                final SeiStateProof stateProof, final SeiStorageProofEntry manifestEntry, final byte[] preimage) {
            // Pairing invariant (verifier ~line 255): manifest_storage_proof present iff endpoint_manifest
            // non-empty — always set both together.
            return ClprSeiBundlePayload.PROTOBUF
                    .toBytes(ClprSeiBundlePayload.newBuilder()
                            .stateProof(stateProof)
                            .bundleContent(Bytes.wrap(new byte[] {1, 2, 3, 4}))
                            .manifestStorageProof(manifestEntry)
                            .endpointManifest(Bytes.wrap(preimage))
                            .build())
                    .toByteArray();
        }

        /** A fully valid manifest bundle: slot-18 commitment == keccak256(preimage), preimage strict-parses. */
        private static byte[] validBundle(final ClprEndpointManifest manifest) {
            final byte[] preimage = preimageOf(manifest);
            final var chain = chainWithManifestSlot(endpointManifestCommitmentSlot(), keccak256(preimage));
            return manifestBundle(bundleStateProof(chain), manifestEntry(chain), preimage);
        }

        @Test
        void verifiesManifestAndReturnsPreimage() throws Exception {
            final var manifest = manifest(7L, SERVICE);
            final var verified = SeiCometBftProofVerifier.verifyBundle(validBundle(manifest), anchorBytes());

            assertThat(verified.newEndpointManifestBytes()).isEqualTo(preimageOf(manifest));
            final var proven = ClprEndpointManifest.PROTOBUF.parse(
                    Bytes.wrap(verified.newEndpointManifestBytes()).toReadableSequentialData());
            assertThat(proven.version()).isEqualTo(7L);
            assertThat(proven.serviceAddress().toByteArray()).isEqualTo(SERVICE);
            assertThat(proven.endpoints()).hasSize(1);
            // the queue metadata is still proven, and no rotation is announced
            assertThat(verified.queueMetadata().nextMessageId()).isEqualTo(42L);
            assertThat(verified.newTrustAnchor()).isNull();
        }

        @Test
        void rejectsKeyNotTargetingServiceContract() {
            final byte[] preimage = preimageOf(manifest(1L, SERVICE));
            final var chain = chainWithManifestSlot(endpointManifestCommitmentSlot(), keccak256(preimage));
            // valid prefix is 0x03; swap it for 0x04 while keeping service + slot
            final byte[] wrongKey = concat(new byte[] {0x04}, SERVICE, endpointManifestCommitmentSlot());
            final var tampered =
                    manifestEntry(chain).copyBuilder().key(Bytes.wrap(wrongKey)).build();
            final byte[] payload = manifestBundle(bundleStateProof(chain), tampered, preimage);
            final byte[] anchor = anchorBytes();

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("does not target the CLPR service contract");
        }

        @Test
        void rejectsWrongCommitmentSlot() {
            final byte[] preimage = preimageOf(manifest(1L, SERVICE));
            // slot 19 targets the service contract but is not the endpoint-manifest commitment slot (18)
            final var chain = chainWithManifestSlot(slot(19), keccak256(preimage));
            final byte[] payload = manifestBundle(bundleStateProof(chain), manifestEntry(chain), preimage);
            final byte[] anchor = anchorBytes();

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("endpoint-manifest commitment slot (18)");
        }

        @Test
        void rejectsNon32ByteValue() {
            final byte[] preimage = preimageOf(manifest(1L, SERVICE));
            final var chain = chainWithManifestSlot(endpointManifestCommitmentSlot(), keccak256(preimage));
            final var tampered = manifestEntry(chain)
                    .copyBuilder()
                    .value(Bytes.wrap(new byte[31]))
                    .build();
            final byte[] payload = manifestBundle(bundleStateProof(chain), tampered, preimage);
            final byte[] anchor = anchorBytes();

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("value must be 32 bytes");
        }

        @Test
        void rejectsIavlProofNotMatchingStoreRoot() {
            final byte[] preimage = preimageOf(manifest(1L, SERVICE));
            final var chain = chainWithManifestSlot(endpointManifestCommitmentSlot(), keccak256(preimage));
            // a 32-byte value the ICS-23 proof does not commit to
            final byte[] otherValue = new byte[32];
            Arrays.fill(otherValue, (byte) 0x11);
            final var tampered = manifestEntry(chain)
                    .copyBuilder()
                    .value(Bytes.wrap(otherValue))
                    .build();
            final byte[] payload = manifestBundle(bundleStateProof(chain), tampered, preimage);
            final byte[] anchor = anchorBytes();

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("different value");
        }

        @Test
        void rejectsCommitmentNotMatchingPreimage() {
            final byte[] preimage = preimageOf(manifest(1L, SERVICE));
            // prove a 32-byte commitment that is not keccak256(preimage)
            final byte[] wrongCommitment = new byte[32];
            Arrays.fill(wrongCommitment, (byte) 0xAB);
            final var chain = chainWithManifestSlot(endpointManifestCommitmentSlot(), wrongCommitment);
            final byte[] payload = manifestBundle(bundleStateProof(chain), manifestEntry(chain), preimage);
            final byte[] anchor = anchorBytes();

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("does not match the proven commitment");
        }

        @Test
        void rejectsManifestWithUnknownField() {
            // Spec §1: reject a manifest carrying unrecognized fields. Commit to the tampered preimage
            // so the binding holds and the strict parse is what fails.
            final byte[] preimage = appendUnknownField(preimageOf(manifest(1L, SERVICE)));
            final var chain = chainWithManifestSlot(endpointManifestCommitmentSlot(), keccak256(preimage));
            final byte[] payload = manifestBundle(bundleStateProof(chain), manifestEntry(chain), preimage);
            final byte[] anchor = anchorBytes();

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("not a valid ClprEndpointManifest");
        }

        @Test
        void rejectsVersionZeroManifest() {
            final byte[] payload = validBundle(manifest(0L, SERVICE));
            final byte[] anchor = anchorBytes();

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("version is 0");
        }

        @Test
        void rejectsServiceAddressMismatch() {
            final byte[] payload = validBundle(manifest(1L, OTHER_SERVICE));
            final byte[] anchor = anchorBytes();

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("service_address does not match");
        }

        @Test
        void manifestOnlyBundleReturnsPreimageWithNoContentOrQueueMetadata() throws Exception {
            final var manifest = manifest(5L, SERVICE);
            final byte[] preimage = preimageOf(manifest);
            final byte[] payload = manifestOnlyBundle(keccak256(preimage), preimage);

            final var verified = SeiCometBftProofVerifier.verifyBundle(payload, anchorBytes());

            // No queue slots, no content — only the manifest is surfaced.
            assertThat(verified.bundleContentBytes()).isNull();
            assertThat(verified.queueMetadata()).isNull();
            assertThat(verified.newTrustAnchor()).isNull();
            assertThat(verified.blockHash32()).isNotNull();
            assertThat(verified.newEndpointManifestBytes()).isEqualTo(preimage);
            final var proven = ClprEndpointManifest.PROTOBUF.parse(
                    Bytes.wrap(verified.newEndpointManifestBytes()).toReadableSequentialData());
            assertThat(proven.version()).isEqualTo(5L);
            assertThat(proven.serviceAddress().toByteArray()).isEqualTo(SERVICE);
        }

        @Test
        void manifestOnlyBundleRejectsCommitmentNotMatchingPreimage() {
            final byte[] preimage = preimageOf(manifest(1L, SERVICE));
            // Prove a 32-byte commitment that is not keccak256(preimage); binding fails.
            final byte[] wrongCommitment = new byte[32];
            Arrays.fill(wrongCommitment, (byte) 0xCD);
            final byte[] payload = manifestOnlyBundle(wrongCommitment, preimage);
            final byte[] anchor = anchorBytes();

            assertThatThrownBy(() -> SeiCometBftProofVerifier.verifyBundle(payload, anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("does not match the proven commitment");
        }

        /**
         * Manifest-only recovery bundle (spec §8.1.4): a state proof over just the manifest slot (enough
         * to establish the store root), EMPTY bundle_content, and the slot-18 manifest proof — but NO
         * queue storage slots. Builds a chain carrying only the manifest slot, then strips it from
         * {@code state_proof.storage_proofs} (leaving them empty) while proving it via the top-level
         * {@code manifest_storage_proof}. This wire shape is not yet produced by the clpr-evm-endpoint
         * relay (cross-repo follow-up), only by this synthetic fixture.
         */
        private static byte[] manifestOnlyBundle(final byte[] commitment, final byte[] preimage) {
            final byte[][] onlyManifestSlot = new byte[][] {endpointManifestCommitmentSlot()};
            final byte[][] onlyManifestValue = new byte[][] {commitment};
            final var chain = SyntheticSeiChain.stateProof(
                    VALIDATORS, VALIDATORS, VALIDATORS, onlyManifestSlot, onlyManifestValue);
            final var manifestEntry = chain.stateProof().storageProofs().get(0);
            // Strip all queue slots: the state proof keeps only the multistore commitment to the store root.
            final var emptyQueueStateProof =
                    chain.stateProof().copyBuilder().storageProofs(List.of()).build();
            return ClprSeiBundlePayload.PROTOBUF
                    .toBytes(ClprSeiBundlePayload.newBuilder()
                            .stateProof(emptyQueueStateProof)
                            .bundleContent(Bytes.EMPTY)
                            .manifestStorageProof(manifestEntry)
                            .endpointManifest(Bytes.wrap(preimage))
                            .build())
                    .toByteArray();
        }
    }
}
