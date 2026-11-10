// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.PbjTestUtils.appendUnknownField;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.ANCHOR_PERIOD;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.CHANNEL_CONTEXT;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.CHANNEL_ID;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.EMPTY_TRIE_ROOT;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.FORK_VERSION;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.SERVICE_ADDRESS;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.accountLeafProof;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.anchor;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.bitsWithout;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.bundle;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.channelSlots;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.channelSlotsAnd;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.firstSlotOf;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.manifestCommitmentSlot;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.nonSignerEntries;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.signingRoot;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.slotKey;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.storageEntry;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.zeroBranch;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.deterministicBytes;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.foldBranch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.EthereumPosBeaconBlockHeader;
import com.hedera.hapi.node.state.clpr.EthereumPosLedgerConfigurationPayload;
import com.hedera.hapi.node.state.clpr.EthereumPosSyncAggregate;
import com.hedera.hapi.node.state.clpr.EthereumPosSyncCommittee;
import com.hedera.hapi.node.state.clpr.EthereumPosTrustAnchor;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.ProvenQueueMetadata;
import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.Committee;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.ConfigBuilder;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.DeliveryBuilder;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumBundleFixtures.RotationBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EthereumSyncCommitteeProofVerifierTest {

    private static final SyncCommitteePreset MAINNET = SyncCommitteePreset.MAINNET;
    private static final SyncCommitteePreset MINIMAL = SyncCommitteePreset.MINIMAL;

    // The committees of periods ANCHOR_PERIOD to ANCHOR_PERIOD + 3.
    private static final Committee COMMITTEE = Committee.of(MAINNET, 1);
    private static final Committee NEXT_COMMITTEE = Committee.of(MAINNET, 2);
    private static final Committee COMMITTEE_AFTER_NEXT = Committee.of(MAINNET, 3);
    private static final Committee FOURTH_COMMITTEE = Committee.of(MAINNET, 4);

    private static final Committee MINIMAL_COMMITTEE = Committee.of(MINIMAL, 5);
    private static final Committee MINIMAL_NEXT_COMMITTEE = Committee.of(MINIMAL, 6);
    private static final Committee MINIMAL_COMMITTEE_AFTER_NEXT = Committee.of(MINIMAL, 7);

    /** Trusts COMMITTEE for ANCHOR_PERIOD and NEXT_COMMITTEE for the period after. */
    private static final byte[] TRUST_ANCHOR = anchor(MAINNET, ANCHOR_PERIOD, COMMITTEE, NEXT_COMMITTEE);

    private static final byte[] MINIMAL_TRUST_ANCHOR =
            anchor(MINIMAL, ANCHOR_PERIOD, MINIMAL_COMMITTEE, MINIMAL_NEXT_COMMITTEE);

    /**
     * Position of {@code signatureSlot} in the delivery list.
     */
    private static final int DELIVERY_INDEX_SIGNATURE_SLOT = 1;

    private final RecordingBls bls = new RecordingBls();

    private final EthereumSyncCommitteeProofVerifier verifier = new EthereumSyncCommitteeProofVerifier(bls);

    /**
     * A {@link BlsSignatureVerifier} that records its inputs and returns a configurable result.
     */
    private static final class RecordingBls implements BlsSignatureVerifier {

        boolean result = true;
        byte[] lastAggregatePubkey;
        List<byte[]> lastNonSignerPubkeys;
        byte[] lastMessage;
        byte[] lastSignature;

        /** The key {@link #validatePubkeys} rejects; every other key is valid. */
        byte[] invalidPubkey;

        final List<byte[]> validatedPubkeys = new ArrayList<>();

        @Override
        public boolean verifySyncAggregate(
                final byte @NonNull [] aggregatePubkey,
                final @NonNull List<byte[]> nonSignerPubkeys,
                final byte @NonNull [] message,
                final byte @NonNull [] signature) {
            lastAggregatePubkey = aggregatePubkey;
            lastNonSignerPubkeys = nonSignerPubkeys;
            lastMessage = message;
            lastSignature = signature;
            return result;
        }

        @Override
        public boolean validatePubkeys(final @NonNull List<byte[]> pubkeys) {
            validatedPubkeys.addAll(pubkeys);
            return pubkeys.stream().noneMatch(pubkey -> Arrays.equals(pubkey, invalidPubkey));
        }
    }

    private static DeliveryBuilder delivery() {
        return new DeliveryBuilder(MAINNET, ANCHOR_PERIOD, COMMITTEE);
    }

    private VerifiedBundle verify(final byte[] bundlePayload) {
        return verify(bundlePayload, TRUST_ANCHOR);
    }

    private VerifiedBundle verify(final byte[] bundlePayload, final byte[] trustAnchor) {
        return verifier.verifyBundle(bundlePayload, trustAnchor, CHANNEL_CONTEXT);
    }

    private void assertRejected(final byte[] bundlePayload, final String message) {
        assertThatThrownBy(() -> verify(bundlePayload))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining(message);
    }

    @Nested
    @DisplayName("verifyBundle: decoding")
    class BundleDecoding {

        @Test
        @DisplayName("given a null argument, then verifyBundle throws NullPointerException")
        void givenNullArgument_thenThrowsNullPointerException() {
            final byte[] payload = bundle(delivery().build());
            assertThatThrownBy(() -> verifier.verifyBundle(null, TRUST_ANCHOR, CHANNEL_CONTEXT))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> verifier.verifyBundle(payload, null, CHANNEL_CONTEXT))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> verifier.verifyBundle(payload, TRUST_ANCHOR, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("given bytes that are not RLP, then verifyBundle rejects the payload")
        void givenGarbagePayload_thenThrowsProofException() {
            assertRejected(new byte[] {(byte) 0xFF, 0x01}, "payload is not a valid RLP item");
        }

        @Test
        @DisplayName("given a top-level list of 3 items, then verifyBundle rejects the payload")
        void givenThreeTopLevelItems_thenThrowsProofException() {
            final byte[] payload =
                    Rlp.encodeList(List.of(Rlp.encodeList(List.of()), delivery().build(), Rlp.encodeList(List.of())));
            assertRejected(payload, "payload [rotations, delivery] to be an RLP list of 2 items, got 3 items");
        }

        @Test
        @DisplayName("given a delivery without a signature slot, then verifyBundle rejects the payload")
        void givenDeliveryWithoutSignatureSlot_thenThrowsProofException() {
            final List<byte[]> items = deliveryItems(delivery().build());
            items.remove(DELIVERY_INDEX_SIGNATURE_SLOT);
            assertRejected(bundle(Rlp.encodeList(items)), "delivery to be an RLP list of 11 items, got 10 items");
        }

        @Test
        @DisplayName("given a signature slot longer than 8 bytes, then verifyBundle rejects the payload")
        void givenSignatureSlotLongerThanEightBytes_thenThrowsProofException() {
            final List<byte[]> items = deliveryItems(delivery().build());
            final byte[] nineBytes = new byte[9];
            nineBytes[0] = 1;
            items.set(DELIVERY_INDEX_SIGNATURE_SLOT, Rlp.encodeBytes(nineBytes));
            assertRejected(bundle(Rlp.encodeList(items)), "delivery.signatureSlot is longer than 8 bytes (9)");
        }

        private static List<byte[]> deliveryItems(final byte[] delivery) {
            return new ArrayList<>(Rlp.decodeOne(delivery).children().stream()
                    .map(Rlp.Item::raw)
                    .toList());
        }

        @Test
        @DisplayName("given a rotation of 6 items, then verifyBundle rejects the payload")
        void givenRotationOfSixItems_thenThrowsProofException() {
            final Rlp.Item rotation =
                    Rlp.decodeOne(new RotationBuilder(MAINNET, ANCHOR_PERIOD, COMMITTEE, NEXT_COMMITTEE).build());
            final List<byte[]> sixItems = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                sixItems.add(rotation.children().get(i).raw());
            }
            assertRejected(
                    bundle(List.of(Rlp.encodeList(sixItems)), delivery().build()),
                    "rotations[0] to be an RLP list of 7 items, got 6 items");
        }

        @Test
        @DisplayName("given minimal-preset bits under a mainnet anchor, then verifyBundle rejects the payload")
        void givenBitsOfAnotherPreset_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            builder.bits = bitsWithout(MINIMAL);
            builder.nonSignerEntries = List.of();
            assertRejected(bundle(builder.build()), "delivery.syncAggregate.bits must be 64 bytes, got 4");
        }

        @Test
        @DisplayName("given a compressed 96-byte signature, then verifyBundle rejects the payload")
        void givenCompressedSignature_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            builder.signature = new byte[96];
            assertRejected(bundle(builder.build()), "delivery.syncAggregate.signature must be 256 bytes, got 96");
        }

        @Test
        @DisplayName("given a non-signer entry with too few siblings, then verifyBundle rejects the payload")
        void givenShortNonSignerEntry_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            builder.nonSignerEntries = nonSignerEntries(COMMITTEE, builder.bits).stream()
                    .map(entry -> Arrays.copyOf(entry, entry.length - 32))
                    .toList();
            assertRejected(bundle(builder.build()), "delivery.nonSignerProofs[0] must be 416 bytes, got 384");
        }

        @Test
        @DisplayName("given a storage proof of one entry, then verifyBundle rejects the payload")
        void givenStorageProofOfOneEntry_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            builder.storageEntries = List.of(storageEntry(slotKey(1), new byte[0][]));
            assertRejected(bundle(builder.build()), "delivery.storageProof has 1 entries");
        }

        @Test
        @DisplayName("given a manifest proof without a preimage, then verifyBundle rejects the payload")
        void givenManifestProofWithoutPreimage_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            builder.manifestProof = Rlp.encodeList(List.of(storageEntry(manifestCommitmentSlot(), new byte[0][])));
            assertRejected(bundle(builder.build()), "must be both present or both empty");
        }

        @Test
        @DisplayName("given a manifest proof of two entries, then verifyBundle rejects the payload")
        void givenManifestProofOfTwoEntries_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            final byte[] entry = storageEntry(manifestCommitmentSlot(), new byte[0][]);
            builder.manifestProof = Rlp.encodeList(List.of(entry, entry));
            builder.manifestPreimage = new byte[] {0x08, 0x01};
            assertRejected(bundle(builder.build()), "delivery.manifestProof must hold at most one entry, got 2");
        }
    }

    @Nested
    @DisplayName("verifyBundle: trust anchor and channel context")
    class AnchorAndContext {

        @Test
        @DisplayName("given an anchor cut short, then verifyBundle rejects it")
        void givenTruncatedAnchor_thenThrowsProofException() {
            assertThatThrownBy(() ->
                            verify(bundle(delivery().build()), Arrays.copyOf(TRUST_ANCHOR, TRUST_ANCHOR.length - 1)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("trustAnchor is not a valid EthereumPosTrustAnchor");
        }

        @Test
        @DisplayName("given an anchor with an unknown preset code, then verifyBundle rejects it")
        void givenUnknownPresetCode_thenThrowsProofException() throws ParseException {
            final byte[] anchor = EthereumPosTrustAnchor.PROTOBUF
                    .toBytes(EthereumPosTrustAnchor.PROTOBUF
                            .parse(Bytes.wrap(TRUST_ANCHOR))
                            .copyBuilder()
                            .preset(7)
                            .build())
                    .toByteArray();
            assertThatThrownBy(() -> verify(bundle(delivery().build()), anchor))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("unknown sync committee preset code 7");
        }

        @Test
        @DisplayName("given a channel context without the service address, then verifyBundle rejects it")
        void givenChannelContextWithoutServiceAddress_thenThrowsProofException() {
            assertThatThrownBy(() -> verifier.verifyBundle(bundle(delivery().build()), TRUST_ANCHOR, CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("channelContext must be 52 bytes");
        }
    }

    @Nested
    @DisplayName("verifyBundle: sync-committee signature")
    class Signature {

        @Test
        @DisplayName("given 341 of 512 participants, then verifyBundle rejects the signature")
        void givenParticipationBelowSupermajority_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            builder.bits = bitsWithout(MAINNET, IntStream.range(0, 171).toArray());
            assertRejected(bundle(builder.build()), "participation 341/512 is below the 2/3 supermajority");
        }

        @Test
        @DisplayName("given 342 of 512 participants, then verifyBundle accepts the signature")
        void givenParticipationAtSupermajority_thenVerifies() {
            final DeliveryBuilder builder = delivery();
            builder.bits = bitsWithout(MAINNET, IntStream.range(0, 170).toArray());
            assertThat(verify(bundle(builder.build())).beaconBlockRoot32()).isEqualTo(builder.attestedRoot());
        }

        @Test
        @DisplayName("given 21 of 32 participants under a minimal anchor, then verifyBundle rejects the signature")
        void givenMinimalPresetBelowSupermajority_thenThrowsProofException() {
            final DeliveryBuilder builder = new DeliveryBuilder(MINIMAL, ANCHOR_PERIOD, MINIMAL_COMMITTEE);
            builder.bits = bitsWithout(MINIMAL, IntStream.range(0, 11).toArray());
            assertThatThrownBy(() -> verify(bundle(builder.build()), MINIMAL_TRUST_ANCHOR))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("participation 21/32 is below the 2/3 supermajority");
        }

        @Test
        @DisplayName("given 22 of 32 participants under a minimal anchor, then verifyBundle accepts the signature")
        void givenMinimalPresetAtSupermajority_thenVerifies() {
            final DeliveryBuilder builder = new DeliveryBuilder(MINIMAL, ANCHOR_PERIOD, MINIMAL_COMMITTEE);
            builder.bits = bitsWithout(MINIMAL, IntStream.range(0, 10).toArray());
            final VerifiedBundle verified = verify(bundle(builder.build()), MINIMAL_TRUST_ANCHOR);
            assertThat(verified.beaconBlockRoot32()).isEqualTo(builder.attestedRoot());
        }

        @Test
        @DisplayName("given the BLS check fails, then verifyBundle rejects the signature naming the header, the"
                + " participation and the fork version")
        void givenBlsRejects_thenThrowsProofException() {
            bls.result = false;
            final DeliveryBuilder builder = delivery();

            assertRejected(
                    bundle(builder.build()),
                    "sync aggregate BLS signature verification failed: headerSlot="
                            + builder.attestedSlot + ", headerRoot=0x"
                            + HexFormat.of().formatHex(builder.attestedRoot())
                            + ", participants=510/512, nonSigners=2, forkVersion=0x"
                            + HexFormat.of().formatHex(FORK_VERSION));
        }

        @Test
        @DisplayName("given a valid delivery, then BLS receives the committee aggregate, the non-signers, the signing"
                + " root and the signature")
        void givenValidDelivery_thenBlsReceivesAggregateNonSignersSigningRootAndSignature() {
            final DeliveryBuilder builder = delivery();
            builder.signature = deterministicBytes(256, 0x5A);

            verify(bundle(builder.build()));

            assertThat(bls.lastAggregatePubkey).isEqualTo(COMMITTEE.uncompressedAggregate);
            assertThat(bls.lastNonSignerPubkeys)
                    .containsExactly(COMMITTEE.uncompressedKeys[1], COMMITTEE.uncompressedKeys[2]);
            assertThat(bls.lastMessage).isEqualTo(signingRoot(builder.attestedRoot()));
            assertThat(bls.lastSignature).isEqualTo(builder.signature);
        }

        @Test
        @DisplayName("given full participation, then BLS receives no non-signers")
        void givenFullParticipation_thenBlsReceivesNoNonSigners() {
            final DeliveryBuilder builder = delivery();
            builder.bits = bitsWithout(MAINNET);

            verify(bundle(builder.build()));

            assertThat(bls.lastNonSignerPubkeys).isEmpty();
        }
    }

    @Nested
    @DisplayName("verifyBundle: signature slot")
    class SignatureSlot {

        @Test
        @DisplayName("given a header attested in the last slot of the previous period and signed in the trusted period,"
                + " then verifyBundle verifies it")
        void givenHeaderAttestedInPreviousPeriodAndSignedInTrustedPeriod_thenVerifies() {
            final DeliveryBuilder builder = delivery();
            builder.signatureSlot = firstSlotOf(MAINNET, ANCHOR_PERIOD);
            builder.attestedSlot = builder.signatureSlot - 1;
            builder.finalizedSlot = builder.attestedSlot - 10;

            final VerifiedBundle verified = verify(bundle(builder.build()));

            assertThat(verified.beaconBlockRoot32()).isEqualTo(builder.attestedRoot());
        }

        @Test
        @DisplayName("given a header signed in the next period by the next committee, then verifyBundle verifies it"
                + " without a rotation")
        void givenHeaderSignedInNextPeriodByNextCommittee_thenVerifiesWithoutRotation() {
            final DeliveryBuilder builder = new DeliveryBuilder(MAINNET, ANCHOR_PERIOD + 1, NEXT_COMMITTEE);

            final VerifiedBundle verified = verify(bundle(builder.build()));

            assertThat(verified.beaconBlockRoot32()).isEqualTo(builder.attestedRoot());
            assertThat(verified.nextTrustAnchor()).isNull();
            assertThat(bls.lastAggregatePubkey).isEqualTo(NEXT_COMMITTEE.uncompressedAggregate);
        }

        @Test
        @DisplayName("given a header attested in the last slot of the trusted period and signed in the next, then"
                + " verifyBundle checks it against the next committee")
        void givenSignatureSlotInNextPeriod_thenChecksNextCommittee() {
            final DeliveryBuilder builder = new DeliveryBuilder(MAINNET, ANCHOR_PERIOD, NEXT_COMMITTEE);
            builder.signatureSlot = firstSlotOf(MAINNET, ANCHOR_PERIOD + 1);
            builder.attestedSlot = builder.signatureSlot - 1;

            verify(bundle(builder.build()));

            assertThat(bls.lastAggregatePubkey).isEqualTo(NEXT_COMMITTEE.uncompressedAggregate);
        }

        @Test
        @DisplayName("given a signature in the next period with the current committee's non-signers, then verifyBundle"
                + " rejects it")
        void givenNextPeriodSignatureWithCurrentCommitteeNonSigners_thenThrowsProofException() {
            final DeliveryBuilder builder = new DeliveryBuilder(MAINNET, ANCHOR_PERIOD + 1, COMMITTEE);
            assertRejected(
                    bundle(builder.build()),
                    "non-signer pubkey at committee index 1 is not the trusted committee's member at that index");
        }

        @Test
        @DisplayName("given a signature slot two periods ahead, then verifyBundle rejects it")
        void givenSignatureSlotTwoPeriodsAhead_thenThrowsProofException() {
            final DeliveryBuilder builder = new DeliveryBuilder(MAINNET, ANCHOR_PERIOD + 2, COMMITTEE_AFTER_NEXT);
            assertRejected(
                    bundle(builder.build()),
                    "the trust anchor trusts committees for periods 1000 and 1001, not for period 1002");
        }

        @Test
        @DisplayName("given a signature slot in the previous period, then verifyBundle rejects it")
        void givenSignatureSlotInPreviousPeriod_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            builder.signatureSlot = firstSlotOf(MAINNET, ANCHOR_PERIOD) - 1;
            builder.attestedSlot = builder.signatureSlot - 1;
            builder.finalizedSlot = builder.attestedSlot - 10;
            assertRejected(
                    bundle(builder.build()),
                    "the trust anchor trusts committees for periods 1000 and 1001, not for period 999");
        }

        @ParameterizedTest(name = "signature slot = attested slot - {0}")
        @ValueSource(longs = {0, 1})
        @DisplayName("given a signature slot not after the attested slot, then verifyBundle rejects it")
        void givenSignatureSlotNotAfterAttestedSlot_thenThrowsProofException(final long lag) {
            final DeliveryBuilder builder = delivery();
            builder.signatureSlot = builder.attestedSlot - lag;
            assertRejected(bundle(builder.build()), "is not after the attested header slot");
        }
    }

    @Nested
    @DisplayName("verifyBundle: non-signer proofs")
    class NonSignerProofs {

        @Test
        @DisplayName("given fewer proofs than clear bits, then verifyBundle rejects the signature")
        void givenFewerProofsThanClearBits_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            builder.nonSignerEntries = List.of(COMMITTEE.nonSignerEntry(1));
            assertRejected(
                    bundle(builder.build()), "expected 2 non-signer proofs (one per clear participation bit), got 1");
        }

        @Test
        @DisplayName("given a forged non-signer key on a genuine branch, then verifyBundle rejects it")
        void givenForgedNonSignerKey_thenThrowsProofException() {
            // The q = aggregate − K attack: a relay-chosen key passed as a non-signer must fail membership.
            final DeliveryBuilder builder = delivery();
            final byte[] forged = COMMITTEE.nonSignerEntry(1);
            System.arraycopy(NEXT_COMMITTEE.uncompressedKeys[1], 0, forged, 0, 128);
            builder.nonSignerEntries = List.of(forged, COMMITTEE.nonSignerEntry(2));
            assertRejected(
                    bundle(builder.build()),
                    "non-signer pubkey at committee index 1 is not the trusted committee's member at that index");
        }

        @Test
        @DisplayName("given proofs in descending index order, then verifyBundle rejects them")
        void givenProofsOutOfOrder_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            builder.nonSignerEntries = List.of(COMMITTEE.nonSignerEntry(2), COMMITTEE.nonSignerEntry(1));
            assertRejected(bundle(builder.build()), "non-signer pubkey at committee index 1");
        }

        @Test
        @DisplayName("given a non-signer key with non-zero padding, then verifyBundle rejects it")
        void givenNonSignerKeyWithNonZeroPadding_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            final byte[] padded = COMMITTEE.nonSignerEntry(1);
            padded[0] = 1;
            builder.nonSignerEntries = List.of(padded, COMMITTEE.nonSignerEntry(2));
            assertRejected(
                    bundle(builder.build()), "non-signer pubkey at committee index 1 has non-zero EIP-2537 padding");
        }

        @Test
        @DisplayName("given a member's x with an off-curve y of the same sign, then verifyBundle rejects the key")
        void givenNonSignerKeyOffTheCurve_thenThrowsProofException() {
            // The key compresses to the member's committed key, so it passes membership unless the curve is checked.
            final DeliveryBuilder builder = delivery();
            final byte[] offCurve = COMMITTEE.nonSignerEntry(1);
            offCurve[127] ^= 1;
            builder.nonSignerEntries = List.of(offCurve, COMMITTEE.nonSignerEntry(2));
            assertRejected(
                    bundle(builder.build()), "non-signer pubkey at committee index 1 is not a point on the curve");
        }
    }

    @Nested
    @DisplayName("verifyBundle: finality")
    class Finality {

        @Test
        @DisplayName("given a finalized slot after the attested slot, then verifyBundle rejects it")
        void givenFinalizedSlotAfterAttestedSlot_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            builder.finalizedSlot = builder.attestedSlot + 1;
            assertRejected(bundle(builder.build()), "is after the attested header slot");
        }

        @Test
        @DisplayName(
                "given a finality branch that does not fold to the attested state root, then verifyBundle rejects it")
        void givenWrongFinalityBranch_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            builder.finalityBranchOverride = zeroBranch(9);
            builder.finalityBranchOverride[0] = deterministicBytes(32, 0x99);
            assertRejected(
                    bundle(builder.build()), "finality branch does not verify against the attested header stateRoot");
        }

        @Test
        @DisplayName("given a rejected delivery, then the rejection names the anchor and the delivery's slots")
        void givenRejectedDelivery_thenMessageCarriesBundleContext() {
            final DeliveryBuilder builder = delivery();
            builder.finalityBranchOverride = zeroBranch(9);
            builder.finalityBranchOverride[0] = deterministicBytes(32, 0x99);
            assertThatThrownBy(() -> verify(bundle(builder.build())))
                    .isInstanceOf(ProofException.class)
                    .hasMessage("EthereumSyncCommitteeProofVerifier: bundle rejected (anchorPeriod=" + ANCHOR_PERIOD
                            + ", preset=MAINNET, rotations=0, signatureSlot=" + (builder.attestedSlot + 1)
                            + ", attestedSlot=" + builder.attestedSlot
                            + ", finalizedSlot=" + builder.finalizedSlot
                            + "): finality branch does not verify against the attested header stateRoot");
        }
    }

    @Nested
    @DisplayName("verifyBundle: execution state")
    class ExecutionState {

        @Test
        @DisplayName("given a block header the finalized body does not commit to, then verifyBundle rejects it")
        void givenUncommittedBlockHeader_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            builder.finalizedBodyRootOverride = deterministicBytes(32, 0x77);
            assertRejected(bundle(builder.build()), "execution block hash branch does not verify");
        }

        @Test
        @DisplayName("given a block header committed only by the attested body, then verifyBundle rejects it")
        void givenBlockHeaderCommittedOnlyByAttestedBody_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            builder.attestedBodyRoot =
                    foldBranch(MiscCryptoUtils.keccak256DigestOf(builder.blockHeaderRlp()), zeroBranch(11), 808);
            builder.finalizedBodyRootOverride = deterministicBytes(32, 0x78);
            assertRejected(bundle(builder.build()), "execution block hash branch does not verify");
        }

        @Test
        @DisplayName("given a block header of three items, then verifyBundle rejects it")
        void givenBlockHeaderWithoutStateRoot_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            builder.blockHeaderRlpOverride = Rlp.encodeList(List.of(
                    Rlp.encodeBytes(new byte[32]), Rlp.encodeBytes(new byte[32]), Rlp.encodeBytes(new byte[20])));
            assertRejected(bundle(builder.build()), "execution block header is not an RLP list with a stateRoot");
        }

        @Test
        @DisplayName("given a block header whose state root is 31 bytes, then verifyBundle rejects it")
        void givenShortStateRoot_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            builder.blockHeaderRlpOverride = Rlp.encodeList(List.of(
                    Rlp.encodeBytes(new byte[32]),
                    Rlp.encodeBytes(new byte[32]),
                    Rlp.encodeBytes(new byte[20]),
                    Rlp.encodeBytes(new byte[31])));
            assertRejected(bundle(builder.build()), "execution block header stateRoot is not 32 bytes");
        }
    }

    @Nested
    @DisplayName("verifyBundle: account and Channel slots")
    class AccountAndStorage {

        @Test
        @DisplayName("given an account proof for another address, then verifyBundle rejects it")
        void givenAccountOfAnotherAddress_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            final byte[][] account = accountLeafProof(deterministicBytes(20, 0x11), EMPTY_TRIE_ROOT);
            builder.executionStateRoot = account[0];
            builder.accountProofNodes = new byte[][] {account[1]};
            assertRejected(bundle(builder.build()), "contract account is absent from state trie");
        }

        @Test
        @DisplayName("given an account whose code hash is not the pinned one, then verifyBundle rejects it")
        void givenAccountWithAnotherCodeHash_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery().withAccountCodeHash(deterministicBytes(32, 0xC1));
            assertRejected(
                    bundle(builder.build()),
                    "contract account code hash does not match the code hash pinned in the trust anchor");
        }

        @Test
        @DisplayName("given the Channel slots of another channel, then verifyBundle rejects the storage proof")
        void givenSlotsOfAnotherChannel_thenThrowsProofException() {
            final DeliveryBuilder builder =
                    delivery().withStorage(EMPTY_TRIE_ROOT, new byte[0][], channelSlots(deterministicBytes(32, 0x01)));
            assertRejected(
                    bundle(builder.build()), "proven storage is missing the channel's STATUS_AND_NEXT_MESSAGE_ID slot");
        }

        @Test
        @DisplayName("given a Channel slot proven twice, then verifyBundle rejects the storage proof")
        void givenDuplicateSlot_thenThrowsProofException() {
            final byte[][] slots = channelSlots(CHANNEL_ID);
            final DeliveryBuilder builder =
                    delivery().withStorage(EMPTY_TRIE_ROOT, new byte[0][], channelSlotsAnd(CHANNEL_ID, slots[2]));
            assertRejected(bundle(builder.build()), "duplicate proven slot");
        }

        @Test
        @DisplayName("given a sixth slot, then its proven value is the last message running hash")
        void givenSixthSlot_thenItsValueIsTheLastMessageRunningHash() {
            final byte[] runningHash = deterministicBytes(32, 0xAB);
            final DeliveryBuilder builder = delivery()
                    .withSingleSlotStorage(slotKey(0x50), runningHash, channelSlotsAnd(CHANNEL_ID, slotKey(0x50)));

            final ProvenQueueMetadata metadata = verify(bundle(builder.build())).queueMetadata();

            assertThat(metadata.lastMessageRunningHash()).isEqualTo(runningHash);
            assertThat(metadata.sentRunningHash()).isEqualTo(new byte[32]);
        }

        @Test
        @DisplayName("given five slots, then the last message running hash is zero")
        void givenFiveSlots_thenLastMessageRunningHashIsZero() {
            assertThat(verify(bundle(delivery().build())).queueMetadata().lastMessageRunningHash())
                    .isEqualTo(new byte[32]);
        }

        @Test
        @DisplayName("given a proven sentRunningHash slot, then the queue metadata carries it")
        void givenProvenSentRunningHash_thenQueueMetadataCarriesIt() {
            final byte[] sentRunningHash = deterministicBytes(32, 0xC1);
            final byte[][] slots = channelSlots(CHANNEL_ID);
            final DeliveryBuilder builder = delivery().withSingleSlotStorage(slots[2], sentRunningHash, slots);

            assertThat(verify(bundle(builder.build())).queueMetadata().sentRunningHash())
                    .isEqualTo(sentRunningHash);
        }

        @Test
        @DisplayName("given a proven endpointManifestVersion slot, then the queue metadata carries it")
        void givenProvenManifestVersion_thenQueueMetadataCarriesIt() {
            final byte[] word = new byte[32];
            ByteBuffer.wrap(word, 24, 8).putLong(77L);
            final byte[][] slots = channelSlots(CHANNEL_ID);
            final DeliveryBuilder builder = delivery().withSingleSlotStorage(slots[4], word, slots);

            assertThat(verify(bundle(builder.build())).queueMetadata().endpointManifestVersion())
                    .isEqualTo(77L);
        }

        @Test
        @DisplayName("given a valid delivery, then verifyBundle returns the attested root and the verbatim content")
        void givenValidDelivery_thenReturnsAttestedRootAndContent() {
            final DeliveryBuilder builder = delivery();
            builder.bundleContent = new byte[] {0x0A, 0x0B, 0x0C};

            final VerifiedBundle verified = verify(bundle(builder.build()));

            assertThat(verified.beaconBlockRoot32()).isEqualTo(builder.attestedRoot());
            assertThat(verified.bundleContentBytes()).isEqualTo(builder.bundleContent);
            assertThat(verified.nextTrustAnchor()).isNull();
            assertThat(verified.nextTrustAnchorId()).isNull();
            assertThat(verified.newEndpointManifest()).isNull();
        }
    }

    @Nested
    @DisplayName("verifyBundle: committee rotation")
    class Rotation {

        /** The rotation the default anchor accepts: of period ANCHOR_PERIOD + 1, proving COMMITTEE_AFTER_NEXT. */
        private static RotationBuilder rotation() {
            return new RotationBuilder(MAINNET, ANCHOR_PERIOD + 1, NEXT_COMMITTEE, COMMITTEE_AFTER_NEXT);
        }

        @Test
        @DisplayName("given one rotation, then verifyBundle returns the anchor one period later")
        void givenOneRotation_thenReturnsSuccessorAnchorAndId() {
            final byte[] delivery = new DeliveryBuilder(MAINNET, ANCHOR_PERIOD + 2, COMMITTEE_AFTER_NEXT).build();

            final VerifiedBundle verified = verify(bundle(List.of(rotation().build()), delivery));

            assertThat(verified.nextTrustAnchor())
                    .isEqualTo(anchor(MAINNET, ANCHOR_PERIOD + 1, NEXT_COMMITTEE, COMMITTEE_AFTER_NEXT));
            assertThat(verified.nextTrustAnchorId())
                    .isEqualTo(ByteBuffer.allocate(8).putLong(ANCHOR_PERIOD + 1).array());
        }

        @Test
        @DisplayName("given one rotation, then a delivery signed by either committee of the new window verifies")
        void givenOneRotation_thenDeliveryOfNewCurrentPeriodVerifies() {
            final byte[] delivery = new DeliveryBuilder(MAINNET, ANCHOR_PERIOD + 1, NEXT_COMMITTEE).build();

            final VerifiedBundle verified = verify(bundle(List.of(rotation().build()), delivery));

            assertThat(verified.nextTrustAnchorId())
                    .isEqualTo(ByteBuffer.allocate(8).putLong(ANCHOR_PERIOD + 1).array());
        }

        @Test
        @DisplayName("given two rotations, then verifyBundle advances the anchor two periods")
        void givenTwoRotations_thenAdvancesTwoPeriods() {
            final byte[] second =
                    new RotationBuilder(MAINNET, ANCHOR_PERIOD + 2, COMMITTEE_AFTER_NEXT, FOURTH_COMMITTEE).build();
            final byte[] delivery = new DeliveryBuilder(MAINNET, ANCHOR_PERIOD + 3, FOURTH_COMMITTEE).build();

            final VerifiedBundle verified = verify(bundle(List.of(rotation().build(), second), delivery));

            assertThat(verified.nextTrustAnchor())
                    .isEqualTo(anchor(MAINNET, ANCHOR_PERIOD + 2, COMMITTEE_AFTER_NEXT, FOURTH_COMMITTEE));
        }

        @Test
        @DisplayName("given a minimal-preset rotation, then verifyBundle advances the minimal anchor")
        void givenMinimalPresetRotation_thenAdvancesMinimalAnchor() {
            final byte[] rotation = new RotationBuilder(
                            MINIMAL, ANCHOR_PERIOD + 1, MINIMAL_NEXT_COMMITTEE, MINIMAL_COMMITTEE_AFTER_NEXT)
                    .build();
            final byte[] delivery =
                    new DeliveryBuilder(MINIMAL, ANCHOR_PERIOD + 2, MINIMAL_COMMITTEE_AFTER_NEXT).build();

            final VerifiedBundle verified = verify(bundle(List.of(rotation), delivery), MINIMAL_TRUST_ANCHOR);

            assertThat(verified.nextTrustAnchor())
                    .isEqualTo(
                            anchor(MINIMAL, ANCHOR_PERIOD + 1, MINIMAL_NEXT_COMMITTEE, MINIMAL_COMMITTEE_AFTER_NEXT));
        }

        @Test
        @DisplayName("given a rotation of the anchor's current period, then verifyBundle rejects it")
        void givenRotationOfCurrentPeriod_thenThrowsProofException() {
            final byte[] rotation = new RotationBuilder(MAINNET, ANCHOR_PERIOD, COMMITTEE, NEXT_COMMITTEE).build();
            assertRejected(
                    bundle(List.of(rotation), delivery().build()),
                    "rotation attested header slot 8192040 is in period 1000, expected period 1001");
        }

        @Test
        @DisplayName("given a rotation signed by the current committee, then verifyBundle rejects it")
        void givenRotationSignedByCurrentCommittee_thenThrowsProofException() {
            final byte[] rotation =
                    new RotationBuilder(MAINNET, ANCHOR_PERIOD + 1, COMMITTEE, COMMITTEE_AFTER_NEXT).build();
            assertRejected(
                    bundle(List.of(rotation), delivery().build()),
                    "non-signer pubkey at committee index 3 is not the trusted committee's member at that index");
        }

        @Test
        @DisplayName("given a rotation whose finalized header is in the previous period, then verifyBundle rejects it")
        void givenFinalizedHeaderInPreviousPeriod_thenThrowsProofException() {
            final RotationBuilder rotation = rotation();
            rotation.finalizedSlot = firstSlotOf(MAINNET, ANCHOR_PERIOD + 1) - 10;
            assertRejected(
                    bundle(List.of(rotation.build()), delivery().build()),
                    "rotation finalized header slot 8200182 is in period 1000, expected period 1001");
        }

        @Test
        @DisplayName("given a next-committee branch that does not fold to the attested state root, then verifyBundle"
                + " rejects it")
        void givenWrongNextCommitteeBranch_thenThrowsProofException() {
            final RotationBuilder rotation = rotation();
            rotation.nextCommitteeBranchOverride = zeroBranch(11);
            assertRejected(
                    bundle(List.of(rotation.build()), delivery().build()),
                    "next sync committee branch does not verify against the header stateRoot");
        }

        @Test
        @DisplayName("given a next committee whose aggregate has non-zero padding, then verifyBundle rejects it")
        void givenNextAggregateWithNonZeroPadding_thenThrowsProofException() {
            final RotationBuilder rotation = rotation();
            rotation.nextAggregate = COMMITTEE_AFTER_NEXT.uncompressedAggregate.clone();
            rotation.nextAggregate[70] = 1;
            assertRejected(
                    bundle(List.of(rotation.build()), delivery().build()),
                    "nextCommittee.aggregatePubkey has non-zero EIP-2537 padding");
        }

        @Test
        @DisplayName("given one rotation, then verifyBundle validates the aggregate of the committee it proves")
        void givenOneRotation_thenValidatesProvenCommitteeAggregate() {
            final byte[] delivery = new DeliveryBuilder(MAINNET, ANCHOR_PERIOD + 2, COMMITTEE_AFTER_NEXT).build();

            verify(bundle(List.of(rotation().build()), delivery));

            assertThat(bls.validatedPubkeys).containsExactly(COMMITTEE_AFTER_NEXT.uncompressedAggregate);
        }

        @Test
        @DisplayName("given a next committee whose aggregate is not a valid public key, then verifyBundle rejects it")
        void givenNextAggregateNotValidPubkey_thenThrowsProofException() {
            bls.invalidPubkey = COMMITTEE_AFTER_NEXT.uncompressedAggregate;
            final byte[] delivery = new DeliveryBuilder(MAINNET, ANCHOR_PERIOD + 2, COMMITTEE_AFTER_NEXT).build();

            assertRejected(
                    bundle(List.of(rotation().build()), delivery),
                    "next sync committee aggregate pubkey 0x"
                            + HexFormat.of().formatHex(COMMITTEE_AFTER_NEXT.compressedAggregate)
                            + " is not a valid BLS public key");
        }

        @Test
        @DisplayName("given a delivery after a rotation still in the rotated-out period, then verifyBundle rejects it")
        void givenDeliveryInRotatedOutPeriod_thenThrowsProofException() {
            assertRejected(
                    bundle(List.of(rotation().build()), delivery().build()),
                    "the trust anchor trusts committees for periods 1001 and 1002, not for period 1000");
        }

        @Test
        @DisplayName("given a delivery whose non-signers belong to the rotated-out committee, then verifyBundle rejects"
                + " it")
        void givenDeliveryNonSignersOfRotatedOutCommittee_thenThrowsProofException() {
            final DeliveryBuilder delivery = new DeliveryBuilder(MAINNET, ANCHOR_PERIOD + 2, COMMITTEE_AFTER_NEXT);
            delivery.nonSignerEntries = nonSignerEntries(COMMITTEE, delivery.bits);
            assertRejected(
                    bundle(List.of(rotation().build()), delivery.build()),
                    "non-signer pubkey at committee index 1 is not the trusted committee's member at that index");
        }
    }

    @Nested
    @DisplayName("verifyBundle: endpoint manifest (spec §4.9)")
    class EndpointManifest {

        @ParameterizedTest(name = "{0} endpoints")
        @ValueSource(ints = {0, 1, 3})
        @DisplayName("given a manifest update, then verifyBundle returns the reconstructed manifest")
        void givenManifestUpdate_thenReturnsReconstructedManifest(final int endpointCount) {
            final byte[] preimage = serialize(manifest(2L, endpointCount, SERVICE_ADDRESS));
            final DeliveryBuilder builder = delivery()
                    .withManifest(preimage, MiscCryptoUtils.keccak256DigestOf(preimage), manifestCommitmentSlot());

            assertThat(verify(bundle(builder.build())).newEndpointManifest())
                    .isEqualTo(manifest(2L, endpointCount, SERVICE_ADDRESS));
        }

        @Test
        @DisplayName("given a preimage that does not hash to the proven commitment, then verifyBundle rejects it")
        void givenCommitmentMismatch_thenThrowsProofException() {
            final byte[] preimage = serialize(manifest(2L, 1, SERVICE_ADDRESS));
            final DeliveryBuilder builder =
                    delivery().withManifest(preimage, deterministicBytes(32, 0x42), manifestCommitmentSlot());
            assertRejected(bundle(builder.build()), "endpoint-manifest preimage does not match the proven commitment");
        }

        @Test
        @DisplayName("given a manifest proof for another slot, then verifyBundle rejects it")
        void givenProofForAnotherSlot_thenThrowsProofException() {
            final byte[] preimage = serialize(manifest(2L, 1, SERVICE_ADDRESS));
            final DeliveryBuilder builder =
                    delivery().withManifest(preimage, MiscCryptoUtils.keccak256DigestOf(preimage), slotKey(0x13));
            assertRejected(
                    bundle(builder.build()), "manifest storage proof is not for the endpoint-manifest commitment slot");
        }

        @Test
        @DisplayName("given the commitment slot is absent from the storage trie, then verifyBundle rejects it")
        void givenCommitmentSlotAbsent_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            builder.manifestProof = Rlp.encodeList(List.of(storageEntry(manifestCommitmentSlot(), new byte[0][])));
            builder.manifestPreimage = serialize(manifest(2L, 1, SERVICE_ADDRESS));
            assertRejected(bundle(builder.build()), "endpoint-manifest commitment slot absent from the storage trie");
        }

        @Test
        @DisplayName("given a manifest of version 0, then verifyBundle rejects it")
        void givenVersionZero_thenThrowsProofException() {
            final byte[] preimage = serialize(manifest(0L, 1, SERVICE_ADDRESS));
            final DeliveryBuilder builder = delivery()
                    .withManifest(preimage, MiscCryptoUtils.keccak256DigestOf(preimage), manifestCommitmentSlot());
            assertRejected(bundle(builder.build()), "endpoint-manifest version is 0");
        }

        @Test
        @DisplayName("given a manifest for another service address, then verifyBundle rejects it")
        void givenAnotherServiceAddress_thenThrowsProofException() {
            final byte[] preimage = serialize(manifest(2L, 1, deterministicBytes(20, 0x12)));
            final DeliveryBuilder builder = delivery()
                    .withManifest(preimage, MiscCryptoUtils.keccak256DigestOf(preimage), manifestCommitmentSlot());
            assertRejected(
                    bundle(builder.build()),
                    "endpoint-manifest service_address does not match the channel's service address");
        }

        @Test
        @DisplayName("given a manifest with an unknown field, then verifyBundle rejects it")
        void givenUnknownField_thenThrowsProofException() {
            final byte[] preimage = appendUnknownField(serialize(manifest(2L, 1, SERVICE_ADDRESS)));
            final DeliveryBuilder builder = delivery()
                    .withManifest(preimage, MiscCryptoUtils.keccak256DigestOf(preimage), manifestCommitmentSlot());
            assertRejected(bundle(builder.build()), "manifest preimage is not a valid ClprEndpointManifest");
        }

        @Test
        @DisplayName("given only a manifest update, then verifyBundle returns it with absent queue metadata")
        void givenManifestOnlyBundle_thenReturnsManifestAndAbsentMetadata() {
            final byte[] preimage = serialize(manifest(3L, 1, SERVICE_ADDRESS));
            final DeliveryBuilder builder = delivery()
                    .withManifest(preimage, MiscCryptoUtils.keccak256DigestOf(preimage), manifestCommitmentSlot());
            builder.storageEntries = List.of();
            builder.bundleContent = new byte[0];

            final VerifiedBundle verified = verify(bundle(builder.build()));

            assertThat(verified.newEndpointManifest()).isEqualTo(manifest(3L, 1, SERVICE_ADDRESS));
            assertThat(verified.queueMetadata().nextMessageId()).isZero();
            assertThat(verified.bundleContentBytes()).isEmpty();
        }

        @Test
        @DisplayName("given no storage proof, no content and no manifest, then verifyBundle rejects the bundle")
        void givenEmptyBundle_thenThrowsProofException() {
            final DeliveryBuilder builder = delivery();
            builder.storageEntries = List.of();
            builder.bundleContent = new byte[0];
            assertRejected(
                    bundle(builder.build()),
                    "bundle has no queue metadata, no content, and no endpoint-manifest advance");
        }
    }

    @Nested
    @DisplayName("verifyConfigPayload(...)")
    class VerifyConfigPayload {

        /** A config of ANCHOR_PERIOD: COMMITTEE signs the update proving NEXT_COMMITTEE. */
        private static ConfigBuilder config() {
            return new ConfigBuilder(MAINNET, ANCHOR_PERIOD, COMMITTEE, NEXT_COMMITTEE);
        }

        private void assertConfigRejected(final byte[] payload, final String message) {
            assertThatThrownBy(() -> verifier.verifyConfigPayload(payload))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining(message);
        }

        @Test
        @DisplayName("given bytes that are not the payload protobuf, then verifyConfigPayload rejects them")
        void givenGarbagePayload_thenThrowsProofException() {
            assertConfigRejected(
                    new byte[] {(byte) 0xFF, (byte) 0xFF},
                    "configPayload is not a valid EthereumPosLedgerConfigurationPayload");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
                strings = {
                    "sync_committee",
                    "ledger_configuration",
                    "anchor_header",
                    "update_header",
                    "sync_aggregate",
                    "next_sync_committee"
                })
        @DisplayName("given a config without a required message field, then verifyConfigPayload rejects the payload")
        void givenMissingMessageField_thenThrowsProofException(final String field) {
            final var builder = config().build().copyBuilder();
            switch (field) {
                case "sync_committee" -> builder.syncCommittee((EthereumPosSyncCommittee) null);
                case "ledger_configuration" -> builder.ledgerConfiguration((ClprLedgerConfiguration) null);
                case "anchor_header" -> builder.anchorHeader((EthereumPosBeaconBlockHeader) null);
                case "update_header" -> builder.updateHeader((EthereumPosBeaconBlockHeader) null);
                case "sync_aggregate" -> builder.syncAggregate((EthereumPosSyncAggregate) null);
                default -> builder.nextSyncCommittee((EthereumPosSyncCommittee) null);
            }
            assertConfigRejected(serialize(builder.build()), "configPayload has no " + field);
        }

        @Test
        @DisplayName("given a committee of 64 members, then verifyConfigPayload rejects the payload")
        void givenUnsupportedCommitteeSize_thenThrowsProofException() {
            final var config = config().build();
            final byte[] payload = serialize(config.copyBuilder()
                    .syncCommittee(config.syncCommitteeOrThrow()
                            .copyBuilder()
                            .pubkeys(config.syncCommitteeOrThrow().pubkeys().subList(0, 64))
                            .build())
                    .build());
            assertConfigRejected(payload, "unsupported sync committee size 64");
        }

        @Test
        @DisplayName("given a next committee of another preset's size, then verifyConfigPayload rejects the payload")
        void givenNextCommitteeOfAnotherSize_thenThrowsProofException() {
            final var config = config().build();
            final byte[] payload = serialize(config.copyBuilder()
                    .nextSyncCommittee(config.nextSyncCommitteeOrThrow()
                            .copyBuilder()
                            .pubkeys(config.nextSyncCommitteeOrThrow().pubkeys().subList(0, 32))
                            .build())
                    .build());
            assertConfigRejected(payload, "next_sync_committee has 32 keys; the MAINNET preset expects 512");
        }

        @Test
        @DisplayName("given an uncompressed member key, then verifyConfigPayload rejects the payload")
        void givenUncompressedKey_thenThrowsProofException() {
            final var config = config().build();
            final List<Bytes> keys =
                    new ArrayList<>(config.syncCommitteeOrThrow().pubkeys());
            keys.set(5, Bytes.wrap(COMMITTEE.uncompressedKeys[5]));
            final byte[] payload = serialize(config.copyBuilder()
                    .syncCommittee(config.syncCommitteeOrThrow()
                            .copyBuilder()
                            .pubkeys(keys)
                            .build())
                    .build());
            assertConfigRejected(payload, "sync_committee.pubkeys[5] must be 48 bytes, got 128");
        }

        @Test
        @DisplayName("given a 3-byte fork version, then verifyConfigPayload rejects the payload")
        void givenShortForkVersion_thenThrowsProofException() {
            assertConfigRejected(
                    serialize(config().build()
                            .copyBuilder()
                            .forkVersion(Bytes.wrap(new byte[3]))
                            .build()),
                    "fork_version must be 4 bytes, got 3");
        }

        @Test
        @DisplayName("given a 31-byte genesis validators root, then verifyConfigPayload rejects the payload")
        void givenShortGenesisValidatorsRoot_thenThrowsProofException() {
            assertConfigRejected(
                    serialize(config().build()
                            .copyBuilder()
                            .genesisValidatorsRoot(Bytes.wrap(new byte[31]))
                            .build()),
                    "genesis_validators_root must be 32 bytes, got 31");
        }

        @Test
        @DisplayName("given a 31-byte code hash, then verifyConfigPayload rejects the payload")
        void givenShortCodeHash_thenThrowsProofException() {
            final ConfigBuilder builder = config();
            builder.codeHash = new byte[31];
            assertConfigRejected(builder.serialize(), "code_hash must be 32 bytes, got 31");
        }

        @Test
        @DisplayName("given a 3-byte service address, then verifyConfigPayload rejects the payload")
        void givenShortServiceAddress_thenThrowsProofException() {
            final var config = config().build();
            final byte[] payload = serialize(config.copyBuilder()
                    .ledgerConfiguration(config.ledgerConfigurationOrThrow()
                            .copyBuilder()
                            .serviceAddress(Bytes.wrap(new byte[3]))
                            .build())
                    .build());
            assertConfigRejected(payload, "ledger_configuration.service_address must be 20 bytes, got 3");
        }

        @Test
        @DisplayName("given an unknown field, then verifyConfigPayload rejects the payload")
        void givenUnknownField_thenThrowsProofException() {
            assertConfigRejected(
                    appendUnknownField(config().serialize()),
                    "configPayload is not a valid EthereumPosLedgerConfigurationPayload");
        }

        @Test
        @DisplayName("given a slot other than the anchor header's, then verifyConfigPayload rejects the payload")
        void givenSlotOtherThanAnchorHeaderSlot_thenThrowsProofException() {
            final ConfigBuilder builder = config();
            builder.slot = builder.anchorSlot + 1;
            assertConfigRejected(
                    builder.serialize(), "configPayload slot 8192034 is not the anchor header slot 8192033");
        }

        @Test
        @DisplayName("given an update header in the next period, then verifyConfigPayload rejects the payload")
        void givenUpdateHeaderInNextPeriod_thenThrowsProofException() {
            final ConfigBuilder builder = config();
            builder.updateSlot = firstSlotOf(MAINNET, ANCHOR_PERIOD + 1) + 1;
            assertConfigRejected(
                    builder.serialize(), "update header slot 8200193 is in period 1001, expected period 1000");
        }

        @Test
        @DisplayName("given a current-committee branch that does not fold to the anchor state root, then"
                + " verifyConfigPayload rejects the payload")
        void givenWrongCurrentCommitteeBranch_thenThrowsProofException() {
            final ConfigBuilder builder = config();
            builder.currentBranchOverride = zeroBranch(11);
            builder.currentBranchOverride[0] = deterministicBytes(32, 0x99);
            assertConfigRejected(
                    builder.serialize(), "current sync committee branch does not verify against the header stateRoot");
        }

        @Test
        @DisplayName("given a next-committee branch that does not fold to the update state root, then"
                + " verifyConfigPayload rejects the payload")
        void givenWrongNextCommitteeBranch_thenThrowsProofException() {
            final ConfigBuilder builder = config();
            builder.nextBranchOverride = zeroBranch(11);
            builder.nextBranchOverride[0] = deterministicBytes(32, 0x99);
            assertConfigRejected(
                    builder.serialize(), "next sync committee branch does not verify against the header stateRoot");
        }

        @Test
        @DisplayName("given 341 of 512 participants in the update signature, then verifyConfigPayload rejects the"
                + " payload")
        void givenUpdateParticipationBelowSupermajority_thenThrowsProofException() {
            final ConfigBuilder builder = config();
            builder.bits = bitsWithout(MAINNET, IntStream.range(0, 171).toArray());
            assertConfigRejected(builder.serialize(), "participation 341/512 is below the 2/3 supermajority");
        }

        @Test
        @DisplayName("given a valid config, then BLS receives the current aggregate, the decompressed non-signers, the"
                + " update's signing root and the signature")
        void givenValidConfig_thenBlsReceivesCurrentCommitteeUpdateSignature() {
            final ConfigBuilder builder = config();
            builder.signature = deterministicBytes(256, 0x5B);

            verifier.verifyConfigPayload(builder.serialize());

            assertThat(bls.lastAggregatePubkey).isEqualTo(COMMITTEE.uncompressedAggregate);
            assertThat(bls.lastNonSignerPubkeys)
                    .containsExactly(COMMITTEE.uncompressedKeys[3], COMMITTEE.uncompressedKeys[17]);
            assertThat(bls.lastMessage).isEqualTo(signingRoot(builder.updateRoot()));
            assertThat(bls.lastSignature).isEqualTo(builder.signature);
        }

        @Test
        @DisplayName("given a valid config, then verifyConfigPayload validates both committees' aggregates")
        void givenValidConfig_thenValidatesBothAggregates() {
            verifier.verifyConfigPayload(config().serialize());

            assertThat(bls.validatedPubkeys)
                    .containsExactly(COMMITTEE.uncompressedAggregate, NEXT_COMMITTEE.uncompressedAggregate);
        }

        @Test
        @DisplayName("given a current committee whose aggregate is not a valid public key, then verifyConfigPayload"
                + " rejects the payload")
        void givenCurrentAggregateNotValidPubkey_thenThrowsProofException() {
            bls.invalidPubkey = COMMITTEE.uncompressedAggregate;
            assertConfigRejected(
                    config().serialize(),
                    "current sync committee aggregate pubkey 0x"
                            + HexFormat.of().formatHex(COMMITTEE.compressedAggregate)
                            + " is not a valid BLS public key");
        }

        @Test
        @DisplayName("given a next committee whose aggregate is not a valid public key, then verifyConfigPayload"
                + " rejects the payload")
        void givenNextAggregateNotValidPubkey_thenThrowsProofException() {
            bls.invalidPubkey = NEXT_COMMITTEE.uncompressedAggregate;
            assertConfigRejected(
                    config().serialize(),
                    "next sync committee aggregate pubkey 0x"
                            + HexFormat.of().formatHex(NEXT_COMMITTEE.compressedAggregate)
                            + " is not a valid BLS public key");
        }

        @Test
        @DisplayName("given the BLS check of the update fails, then verifyConfigPayload rejects the payload")
        void givenUpdateBlsRejects_thenThrowsProofException() {
            bls.result = false;
            assertConfigRejected(config().serialize(), "sync aggregate BLS signature verification failed");
        }

        @Test
        @DisplayName("given a mainnet config, then verifyConfigPayload derives the anchor of both committees and the"
                + " code hash")
        void givenMainnetConfig_thenDerivesAnchorOfBothCommittees() {
            final ConfigBuilder builder = config();

            final VerifiedConfig verified = verifier.verifyConfigPayload(builder.serialize());

            final ClprLedgerConfiguration ledger = verified.ledgerConfiguration();
            assertThat(ledger.initialTrustAnchor().toByteArray())
                    .isEqualTo(anchor(MAINNET, ANCHOR_PERIOD, COMMITTEE, NEXT_COMMITTEE));
            assertThat(ledger.initialTrustAnchorId().toByteArray())
                    .isEqualTo(ByteBuffer.allocate(8).putLong(ANCHOR_PERIOD).array());
            assertThat(ledger.chainId()).isEqualTo("eip155:1");
            assertThat(ledger.serviceAddress().toByteArray()).isEqualTo(SERVICE_ADDRESS);
            assertThat(verified.slot()).isEqualTo(builder.anchorSlot);
        }

        @Test
        @DisplayName("given a minimal config, then verifyConfigPayload derives a minimal anchor")
        void givenMinimalConfig_thenDerivesMinimalAnchor() {
            final ConfigBuilder builder =
                    new ConfigBuilder(MINIMAL, ANCHOR_PERIOD, MINIMAL_COMMITTEE, MINIMAL_NEXT_COMMITTEE);

            final VerifiedConfig verified = verifier.verifyConfigPayload(builder.serialize());

            assertThat(verified.ledgerConfiguration().initialTrustAnchor().toByteArray())
                    .isEqualTo(MINIMAL_TRUST_ANCHOR);
        }

        private static byte[] serialize(final EthereumPosLedgerConfigurationPayload payload) {
            return EthereumPosLedgerConfigurationPayload.PROTOBUF
                    .toBytes(payload)
                    .toByteArray();
        }
    }

    private static byte[] serialize(final ClprEndpointManifest manifest) {
        return ClprEndpointManifest.PROTOBUF.toBytes(manifest).toByteArray();
    }

    /**
     * A manifest bound to {@code serviceAddress} at {@code version} with {@code endpointCount} placeholder endpoints.
     */
    private static ClprEndpointManifest manifest(
            final long version, final int endpointCount, final byte[] serviceAddress) {
        final List<ClprEndpoint> endpoints = new ArrayList<>();
        for (int i = 0; i < endpointCount; i++) {
            endpoints.add(ClprEndpoint.newBuilder()
                    .tlsCertificate(Bytes.wrap(deterministicBytes(48, 0x30 + i)))
                    .build());
        }
        return ClprEndpointManifest.newBuilder()
                .version(version)
                .serviceAddress(Bytes.wrap(serviceAddress))
                .endpoints(endpoints)
                .build();
    }
}
