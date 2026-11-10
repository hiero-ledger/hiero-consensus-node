// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.ANCHOR_PERIOD;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.CODE_HASH;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.FORK_VERSION;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.GENESIS_VALIDATORS_ROOT;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.SERVICE_ADDRESS;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.bundle;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.zeroBranch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.state.clpr.EthereumPosLedgerConfigurationPayload;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.BundlePayload.MessageDelivery;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.Committee;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.ConfigBuilder;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.DeliveryBuilder;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.RotationBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests the structural checks of {@link PayloadDecoder} directly. Each malformed payload starts from a valid one built by
 * {@link EthereumBundleFixtures} and changes a single RLP item.
 */
class PayloadDecoderTest {

    private static final SyncCommitteePreset MAINNET = SyncCommitteePreset.MAINNET;

    private static final Committee COMMITTEE = Committee.of(MAINNET, 1);
    private static final Committee NEXT_COMMITTEE = Committee.of(MAINNET, 2);

    // Positions of the items the tests change, in the wire layout PayloadDecoder documents.
    private static final int ROTATION_INDEX_FINALITY_BRANCH = 4;
    private static final int ROTATION_INDEX_NEXT_COMMITTEE = 5;
    private static final int ROTATION_INDEX_NEXT_COMMITTEE_BRANCH = 6;
    private static final int DELIVERY_INDEX_ATTESTED_HEADER = 0;
    private static final int DELIVERY_INDEX_SIGNATURE_SLOT = 1;
    private static final int DELIVERY_INDEX_NON_SIGNER_PROOFS = 3;
    private static final int DELIVERY_INDEX_FINALIZED_EXECUTION = 4;
    private static final int DELIVERY_INDEX_FINALITY_BRANCH = 5;
    private static final int DELIVERY_INDEX_STORAGE_PROOF = 7;
    private static final int DELIVERY_INDEX_BUNDLE_CONTENT = 8;
    private static final int HEADER_INDEX_STATE_ROOT = 3;
    private static final int FINALIZED_EXECUTION_INDEX_BRANCH = 1;
    private static final int NEXT_COMMITTEE_INDEX_PUBKEYS_ROOT = 0;

    @Nested
    class DecodeBundle {

        @Test
        @DisplayName("given a valid bundle with one rotation, then decodeBundle reads the rotation and the delivery")
        void givenValidBundle_thenDecodesRotationAndDelivery() {
            final RotationBuilder rotation = rotation();
            final DeliveryBuilder delivery = delivery();

            final BundlePayload bundle =
                    PayloadDecoder.decodeBundle(bundle(List.of(rotation.build()), delivery.build()), MAINNET);

            assertThat(bundle.rotations()).hasSize(1);
            assertThat(bundle.rotations().getFirst().finalizedHeader().slot()).isEqualTo(rotation.finalizedSlot);
            final MessageDelivery decoded = bundle.delivery();
            assertThat(decoded.signedHeader().attestedHeader().slot()).isEqualTo(delivery.attestedSlot);
            assertThat(decoded.signatureSlot()).isEqualTo(delivery.attestedSlot + 1);
            assertThat(decoded.signedHeader().nonSignerProofs()).hasSize(2);
            assertThat(decoded.storageProof()).hasSize(5);
            assertThat(decoded.bundleContent()).isEqualTo(delivery.bundleContent);
            assertThat(decoded.manifestStorageProof()).isNull();
            assertThat(decoded.manifestPreimage()).isNull();
        }

        @Test
        @DisplayName("given rotations that are not an RLP list, then decodeBundle rejects the payload")
        void givenRotationsNotList_thenThrowsProofException() {
            final byte[] payload = Rlp.encodeList(
                    List.of(Rlp.encodeBytes(new byte[] {1}), delivery().build()));

            assertRejected(payload, "rotations is not an RLP list");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("shortBranches")
        @DisplayName("given a branch one node short, then decodeBundle rejects the payload")
        void givenBranchOneNodeShort_thenThrowsProofException(
                final String branch, final byte[] payload, final String expectedMessage) {
            assertRejected(payload, expectedMessage);
        }

        /** Every SSZ branch of the bundle, one node short, with the message that rejects it. */
        static Stream<Arguments> shortBranches() {
            final byte[] rotation = rotation().build();
            final byte[] delivery = delivery().build();
            final byte[] finalizedExecution = item(delivery, DELIVERY_INDEX_FINALIZED_EXECUTION);
            return Stream.of(
                    Arguments.of(
                            "rotation finality branch",
                            bundle(List.of(withShortBranch(rotation, ROTATION_INDEX_FINALITY_BRANCH)), delivery),
                            "rotations[0].finalityBranch must have 9 nodes, got 8"),
                    Arguments.of(
                            "rotation next-committee branch",
                            bundle(List.of(withShortBranch(rotation, ROTATION_INDEX_NEXT_COMMITTEE_BRANCH)), delivery),
                            "rotations[0].nextCommitteeBranch must have 11 nodes, got 10"),
                    Arguments.of(
                            "delivery finality branch",
                            bundle(withShortBranch(delivery, DELIVERY_INDEX_FINALITY_BRANCH)),
                            "delivery.finalityBranch must have 9 nodes, got 8"),
                    Arguments.of(
                            "execution block hash branch",
                            bundle(withItem(
                                    delivery,
                                    DELIVERY_INDEX_FINALIZED_EXECUTION,
                                    withShortBranch(finalizedExecution, FINALIZED_EXECUTION_INDEX_BRANCH))),
                            "delivery.finalizedExecution.executionBranch must have 11 nodes, got 10"));
        }

        @Test
        @DisplayName("given an attested header of 4 fields, then decodeBundle rejects the payload")
        void givenHeaderOfFourFields_thenThrowsProofException() {
            final byte[] delivery = delivery().build();
            final byte[] header = withoutLastItem(item(delivery, DELIVERY_INDEX_ATTESTED_HEADER));

            assertRejected(
                    bundle(withItem(delivery, DELIVERY_INDEX_ATTESTED_HEADER, header)),
                    "expected delivery.attestedHeader to be an RLP list of 5 items, got 4 items");
        }

        @Test
        @DisplayName("given a 31-byte state root, then decodeBundle rejects the payload")
        void givenShortStateRoot_thenThrowsProofException() {
            final byte[] delivery = delivery().build();
            final byte[] header = withItem(
                    item(delivery, DELIVERY_INDEX_ATTESTED_HEADER),
                    HEADER_INDEX_STATE_ROOT,
                    Rlp.encodeBytes(new byte[31]));

            assertRejected(
                    bundle(withItem(delivery, DELIVERY_INDEX_ATTESTED_HEADER, header)),
                    "delivery.attestedHeader.stateRoot must be 32 bytes, got 31");
        }

        @Test
        @DisplayName("given a signature slot with a leading zero byte, then decodeBundle rejects the payload")
        void givenNonCanonicalSignatureSlot_thenThrowsProofException() {
            final byte[] delivery =
                    withItem(delivery().build(), DELIVERY_INDEX_SIGNATURE_SLOT, Rlp.encodeBytes(new byte[] {0, 1}));

            assertRejected(bundle(delivery), "delivery.signatureSlot has a non-canonical leading zero byte");
        }

        @Test
        @DisplayName("given bundle content that is an RLP list, then decodeBundle rejects the payload")
        void givenBundleContentList_thenThrowsProofException() {
            final byte[] delivery =
                    withItem(delivery().build(), DELIVERY_INDEX_BUNDLE_CONTENT, Rlp.encodeList(List.of()));

            assertRejected(bundle(delivery), "delivery.bundleContent must be an RLP string, not a list");
        }

        @Test
        @DisplayName("given non-signer proofs that are not an RLP list, then decodeBundle rejects the payload")
        void givenNonSignerProofsNotList_thenThrowsProofException() {
            final byte[] delivery =
                    withItem(delivery().build(), DELIVERY_INDEX_NON_SIGNER_PROOFS, Rlp.encodeBytes(new byte[] {1}));

            assertRejected(bundle(delivery), "delivery.nonSignerProofs is not an RLP list");
        }

        @Test
        @DisplayName("given a next committee with a 48-byte aggregate, then decodeBundle rejects the payload")
        void givenCompressedNextAggregate_thenThrowsProofException() {
            final byte[] rotation = rotation().build();
            final byte[] nextCommittee = Rlp.encodeList(List.of(
                    item(item(rotation, ROTATION_INDEX_NEXT_COMMITTEE), NEXT_COMMITTEE_INDEX_PUBKEYS_ROOT),
                    Rlp.encodeBytes(NEXT_COMMITTEE.compressedAggregate)));

            assertRejected(
                    bundle(
                            List.of(withItem(rotation, ROTATION_INDEX_NEXT_COMMITTEE, nextCommittee)),
                            delivery().build()),
                    "rotations[0].nextCommittee.aggregatePubkey must be 128 bytes, got 48");
        }

        @Test
        @DisplayName("given a next committee with a 31-byte pubkeys root, then decodeBundle rejects the payload")
        void givenShortPubkeysRoot_thenThrowsProofException() {
            final byte[] rotation = rotation().build();
            final byte[] nextCommittee = withItem(
                    item(rotation, ROTATION_INDEX_NEXT_COMMITTEE),
                    NEXT_COMMITTEE_INDEX_PUBKEYS_ROOT,
                    Rlp.encodeBytes(new byte[31]));

            assertRejected(
                    bundle(
                            List.of(withItem(rotation, ROTATION_INDEX_NEXT_COMMITTEE, nextCommittee)),
                            delivery().build()),
                    "rotations[0].nextCommittee.pubkeysRoot must be 32 bytes, got 31");
        }

        @Test
        @DisplayName("given a finalized execution of 2 items, then decodeBundle rejects the payload")
        void givenFinalizedExecutionOfTwoItems_thenThrowsProofException() {
            final byte[] delivery = delivery().build();
            final byte[] finalizedExecution = withoutLastItem(item(delivery, DELIVERY_INDEX_FINALIZED_EXECUTION));

            assertRejected(
                    bundle(withItem(delivery, DELIVERY_INDEX_FINALIZED_EXECUTION, finalizedExecution)),
                    "delivery.finalizedExecution [finalizedHeader, executionBranch, blockHeaderRlp] to be an RLP list of"
                            + " 3 items, got 2 items");
        }

        @Test
        @DisplayName(
                "given a storage proof entry that is not a [key, proof] list, then decodeBundle rejects the payload")
        void givenMalformedStorageEntry_thenThrowsProofException() {
            final byte[] storageProof = Rlp.encodeList(List.of(Rlp.encodeBytes(new byte[32])));
            final byte[] delivery = withItem(delivery().build(), DELIVERY_INDEX_STORAGE_PROOF, storageProof);

            assertRejected(bundle(delivery), "delivery.storageProof[0] is not a [key, proof[]] RLP list");
        }
    }

    @Nested
    class DecodeConfig {

        /**
         * A config of ANCHOR_PERIOD: COMMITTEE signs the update proving NEXT_COMMITTEE.
         */
        private static ConfigBuilder config() {
            return new ConfigBuilder(MAINNET, ANCHOR_PERIOD, COMMITTEE, NEXT_COMMITTEE);
        }

        private static void assertConfigRejected(final byte[] payload, final String message) {
            assertThatThrownBy(() -> PayloadDecoder.decodeConfig(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining(message);
        }

        @Test
        @DisplayName("given a valid mainnet config, then decodeConfig reads the bootstrap, the update, the domain and"
                + " the code hash")
        void givenValidMainnetConfig_thenDecodesEveryField() {
            final ConfigBuilder builder = config();

            final PeerLedgerConfigPayload payload = PayloadDecoder.decodeConfig(builder.serialize());

            assertThat(payload.preset()).isEqualTo(MAINNET);
            assertThat(payload.slot()).isEqualTo(builder.anchorSlot);
            assertThat(payload.current().pubkeys48().length).isEqualTo(MAINNET.committeeSize());
            assertThat(payload.current().pubkeys48()[0]).isEqualTo(COMMITTEE.compressedKeys[0]);
            assertThat(payload.current().aggregatePubkey48()).isEqualTo(COMMITTEE.compressedAggregate);
            assertThat(payload.anchorHeader().slot()).isEqualTo(builder.anchorSlot);
            assertThat(payload.anchorHeader().stateRoot32()).isEqualTo(builder.anchorStateRoot());
            assertThat(payload.currentBranch().length).isEqualTo(11);
            assertThat(payload.updateHeader().slot()).isEqualTo(builder.updateSlot);
            assertThat(payload.syncAggregate().bits()).isEqualTo(builder.bits);
            assertThat(payload.syncAggregate().signature()).isEqualTo(builder.signature);
            assertThat(payload.next().pubkeys48()[0]).isEqualTo(NEXT_COMMITTEE.compressedKeys[0]);
            assertThat(payload.next().aggregatePubkey48()).isEqualTo(NEXT_COMMITTEE.compressedAggregate);
            assertThat(payload.nextBranch().length).isEqualTo(11);
            assertThat(payload.genesisValidatorsRoot32()).isEqualTo(GENESIS_VALIDATORS_ROOT);
            assertThat(payload.forkVersion4()).isEqualTo(FORK_VERSION);
            assertThat(payload.codeHash32()).isEqualTo(CODE_HASH);
            assertThat(payload.ledgerConfiguration().serviceAddress().toByteArray())
                    .isEqualTo(SERVICE_ADDRESS);
        }

        @Test
        @DisplayName("given a 47-byte aggregate key, then decodeConfig rejects the payload")
        void givenShortAggregatePubkey_thenThrowsProofException() {
            final EthereumPosLedgerConfigurationPayload config = config().build();
            final byte[] payload = serialize(config.copyBuilder()
                    .syncCommittee(config.syncCommitteeOrThrow()
                            .copyBuilder()
                            .aggregatePubkey(Bytes.wrap(new byte[47]))
                            .build())
                    .build());

            assertConfigRejected(payload, "sync_committee.aggregate_pubkey must be 48 bytes, got 47");
        }

        @Test
        @DisplayName("given a current-committee branch one node short, then decodeConfig rejects the payload")
        void givenShortCurrentCommitteeBranch_thenThrowsProofException() {
            final ConfigBuilder builder = config();
            builder.currentBranchOverride = Arrays.copyOf(zeroBranch(11), 10);

            assertConfigRejected(builder.serialize(), "current_sync_committee_branch must have 11 nodes, got 10");
        }

        @Test
        @DisplayName("given a next-committee branch with a 31-byte sibling, then decodeConfig rejects the payload")
        void givenShortNextCommitteeBranchSibling_thenThrowsProofException() {
            final ConfigBuilder builder = config();
            builder.nextBranchOverride = zeroBranch(11);
            builder.nextBranchOverride[0] = new byte[31];

            assertConfigRejected(builder.serialize(), "next_sync_committee_branch[0] must be 32 bytes, got 31");
        }

        @Test
        @DisplayName("given a compressed 96-byte update signature, then decodeConfig rejects the payload")
        void givenCompressedUpdateSignature_thenThrowsProofException() {
            final ConfigBuilder builder = config();
            builder.signature = new byte[96];

            assertConfigRejected(builder.serialize(), "sync_aggregate.signature must be 256 bytes, got 96");
        }

        @Test
        @DisplayName("given an anchor header with a 31-byte state root, then decodeConfig rejects the payload")
        void givenShortAnchorHeaderStateRoot_thenThrowsProofException() {
            final EthereumPosLedgerConfigurationPayload config = config().build();
            final byte[] payload = serialize(config.copyBuilder()
                    .anchorHeader(config.anchorHeaderOrThrow()
                            .copyBuilder()
                            .stateRoot(Bytes.wrap(new byte[31]))
                            .build())
                    .build());

            assertConfigRejected(payload, "anchor_header.state_root must be 32 bytes, got 31");
        }

        private static byte[] serialize(final EthereumPosLedgerConfigurationPayload payload) {
            return EthereumPosLedgerConfigurationPayload.PROTOBUF
                    .toBytes(payload)
                    .toByteArray();
        }
    }

    // ── helpers ──

    private static RotationBuilder rotation() {
        return new RotationBuilder(MAINNET, ANCHOR_PERIOD, COMMITTEE, NEXT_COMMITTEE);
    }

    private static DeliveryBuilder delivery() {
        return new DeliveryBuilder(MAINNET, ANCHOR_PERIOD + 1, NEXT_COMMITTEE);
    }

    private static void assertRejected(final byte[] bundlePayload, final String message) {
        assertThatThrownBy(() -> PayloadDecoder.decodeBundle(bundlePayload, MAINNET))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining(message);
    }

    /** The encoded item at {@code index} of the RLP list {@code list}. */
    private static byte[] item(final byte[] list, final int index) {
        return Rlp.decodeOne(list).children().get(index).raw();
    }

    /** {@code list} with its item at {@code index} replaced by the encoded {@code item}. */
    private static byte[] withItem(final byte[] list, final int index, final byte[] item) {
        final List<byte[]> items = items(list);
        items.set(index, item);
        return Rlp.encodeList(items);
    }

    /** {@code list} without its last item. */
    private static byte[] withoutLastItem(final byte[] list) {
        final List<byte[]> items = items(list);
        items.removeLast();
        return Rlp.encodeList(items);
    }

    /** {@code list} with the branch at {@code index} one node short. */
    private static byte[] withShortBranch(final byte[] list, final int index) {
        return withItem(list, index, withoutLastItem(item(list, index)));
    }

    private static List<byte[]> items(final byte[] list) {
        return new ArrayList<>(
                Rlp.decodeOne(list).children().stream().map(Rlp.Item::raw).toList());
    }
}
