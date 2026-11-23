// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.MESSAGE_QUEUE_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprMessageKey;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.hapi.utils.blocks.StateProofVerifier;
import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
import com.hedera.node.app.service.clpr.impl.ClprProofTimestamp;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.service.clpr.impl.ClprThrottlesValidator;
import com.hedera.node.app.spi.state.BlockProvenSnapshot;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.BinaryState;
import com.swirlds.state.State;
import com.swirlds.state.binary.MerkleProof;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ClprStateProofManager} that drive the real manager, mocking only its collaborators.
 */
@ExtendWith(MockitoExtension.class)
class ClprStateProofManagerTest {

    @Mock
    private BlockProvenSnapshotProvider snapshotProvider;

    @Mock
    private TssVerifier tssVerifier;

    @Mock
    private BlockProvenSnapshot snapshot;

    // The real snapshot state implements both State (the declared return type) and BinaryState (checked
    // via instanceof), so the mock must do the same: a State that also implements BinaryState.
    @Mock(extraInterfaces = BinaryState.class)
    private State provenState;

    @Mock
    private State nonBinaryState;

    private BinaryState binaryState;
    private ClprStateProofManager subject;

    @BeforeEach
    void setUp() {
        subject = new ClprStateProofManager(
                snapshotProvider,
                tssVerifier,
                HederaTestConfigBuilder.createConfigProvider(),
                (proof, anchor) -> false);
        binaryState = (BinaryState) provenState;
    }

    @Test
    void proofBudgetReservesBothVerifierAbiAndPeerWireFraming() {
        final var peer =
                ClprThrottles.newBuilder().maxSyncBytes(4 * 1024 * 1024).build();
        assertThat(subject.maxBundlePayloadBytes(peer)).isEqualTo(126 * 1024L);
        assertThat(subject.maxBundlePayloadBytes(
                        peer.copyBuilder().maxSyncBytes(8192).build()))
                .isEqualTo(8192L - 114);
    }

    @ParameterizedTest
    @CsvSource({
        "131072, 129024, 65536", "98304, 96256, 55296", "65536, 63488, 22528",
        "43023, 40975, 15", "43008, 40960, 0", "1024, 0, 0"
    })
    void configuredCalldataBoundsProofsAndNewPayloads(
            final int calldata, final long proofBudget, final long payloadBudget) {
        final var config = new VersionedConfigImpl(
                HederaTestConfigBuilder.create()
                        .withValue("jumboTransactions.ethereumMaxCallDataSize", calldata)
                        .getOrCreateConfig(),
                0);
        final var manager = new ClprStateProofManager(snapshotProvider, tssVerifier, () -> config, (p, a) -> false);
        final var peer =
                ClprThrottles.newBuilder().maxSyncBytes(4 * 1024 * 1024).build();
        assertThat(manager.maxBundlePayloadBytes(peer)).isEqualTo(proofBudget);
        assertThat(ClprThrottlesValidator.senderPayloadBudget(config, ClprChannel.DEFAULT, peer.maxSyncBytes()))
                .isEqualTo(payloadBudget);
    }

    @Test
    @DisplayName("No signed snapshot yet -> null")
    void noSnapshotReturnsNull() {
        given(snapshotProvider.latestSnapshot()).willReturn(Optional.empty());
        assertThat(subject.buildManifestStateProofWithValue()).isNull();
    }

    @Test
    @DisplayName("Snapshot state is not a BinaryState -> null")
    void nonBinaryStateReturnsNull() {
        given(snapshotProvider.latestSnapshot()).willReturn(Optional.of(snapshot));
        given(snapshot.state()).willReturn(nonBinaryState);
        assertThat(subject.buildManifestStateProofWithValue()).isNull();
    }

    @Test
    @DisplayName("Happy path -> proven manifest paired with a non-empty proof from the same snapshot")
    void pairsProvenManifestWithProof() throws Exception {
        final var manifest = ClprEndpointManifest.newBuilder().version(5L).build();
        givenProvableSnapshot();
        given(binaryState.getSingleton(ENDPOINT_MANIFEST_STATE_ID))
                .willReturn(ClprEndpointManifest.PROTOBUF.toBytes(manifest));

        final var result = subject.buildManifestStateProofWithValue();

        assertThat(result).isNotNull();
        assertThat(result.manifest()).isEqualTo(manifest);
        assertThat(result.proof()).isNotEqualTo(Bytes.EMPTY);
        // The bytes are a real serialized StateProof carrying exactly the one singleton path.
        final var proof = StateProof.PROTOBUF.parse(result.proof().toReadableSequentialData());
        assertThat(proof.paths()).hasSize(1);
    }

    @Test
    void recoveryProofAuthenticatesTheSnapshotTimestamp() throws Exception {
        givenProvableSnapshot();
        given(binaryState.getSingleton(ENDPOINT_MANIFEST_STATE_ID)).willReturn(null);

        final var result = subject.buildManifestStateProofWithValue(true);
        assertThat(result).isNotNull();
        final var proof = StateProof.PROTOBUF.parseStrict(result.proof());
        assertThat(proof.paths()).hasSize(2);
        final var digest = StateProofVerifier.inferDigestType(proof).buildDigest();
        final var root =
                StateProofVerifier.computeBlockRootHashFromPath(proof.paths().getFirst(), digest);
        assertThat(ClprProofTimestamp.verifiedTimestamp(proof, root, digest)).isEqualTo(new Timestamp(1L, 0));
    }

    @Test
    @DisplayName("blockStream.digestType=SHA_256 -> proof still built correctly, hashing with SHA-256 end-to-end")
    void sha256ConfiguredBuildsValidProof() throws Exception {
        subject = new ClprStateProofManager(
                snapshotProvider,
                tssVerifier,
                configProviderWithDigestType(DigestType.SHA_256),
                (proof, anchor) -> false);
        final var manifest = ClprEndpointManifest.newBuilder().version(5L).build();
        givenProvableSnapshot();
        given(binaryState.getSingleton(ENDPOINT_MANIFEST_STATE_ID))
                .willReturn(ClprEndpointManifest.PROTOBUF.toBytes(manifest));

        final var result = subject.buildManifestStateProofWithValue();

        assertThat(result).isNotNull();
        assertThat(result.manifest()).isEqualTo(manifest);
        final var proof = StateProof.PROTOBUF.parse(result.proof().toReadableSequentialData());
        assertThat(proof.paths()).hasSize(1);
    }

    private static ConfigProvider configProviderWithDigestType(final DigestType digestType) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.digestType", digestType.name())
                .getOrCreateConfig();
        final var versioned = new VersionedConfigImpl(config, 0);
        return () -> versioned;
    }

    @Test
    @DisplayName("Manifest singleton unset -> proof paired with the DEFAULT manifest")
    void unsetSingletonReturnsDefaultManifest() {
        givenProvableSnapshot();
        given(binaryState.getSingleton(ENDPOINT_MANIFEST_STATE_ID)).willReturn(null);

        final var result = subject.buildManifestStateProofWithValue();

        assertThat(result).isNotNull();
        assertThat(result.manifest()).isEqualTo(ClprEndpointManifest.DEFAULT);
        assertThat(result.proof()).isNotEqualTo(Bytes.EMPTY);
    }

    @Test
    @DisplayName("Singleton leaf not present -> proof build fails -> null")
    void missingSingletonPathReturnsNull() {
        given(snapshotProvider.latestSnapshot()).willReturn(Optional.of(snapshot));
        given(snapshot.state()).willReturn(provenState);
        given(snapshot.blockTimestamp())
                .willReturn(Timestamp.newBuilder().seconds(1L).build());
        given(snapshot.path()).willReturn(MerklePath.DEFAULT);
        given(binaryState.getSingletonPath(ENDPOINT_MANIFEST_STATE_ID)).willReturn(-1L);

        assertThat(subject.buildManifestStateProofWithValue()).isNull();
    }

    @Test
    @DisplayName("Failure while building the proof is swallowed -> null")
    void buildFailureReturnsNull() {
        given(snapshotProvider.latestSnapshot()).willReturn(Optional.of(snapshot));
        given(snapshot.state()).willReturn(provenState);
        given(snapshot.blockTimestamp())
                .willReturn(Timestamp.newBuilder().seconds(1L).build());
        given(snapshot.path()).willReturn(MerklePath.DEFAULT);
        given(binaryState.getSingletonPath(ENDPOINT_MANIFEST_STATE_ID)).willThrow(new IllegalStateException("boom"));

        assertThat(subject.buildManifestStateProofWithValue()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "512,512,false,true,1,channel message,false",
        "2048,512,false,true,1,channel manifest timestamp message,false",
        "512,160,false,true,0,manifest timestamp,false",
        "512,160,true,true,0,manifest timestamp,false",
        "512,260,false,true,0,manifest timestamp,false",
        "512,320,false,true,1,channel message,false",
        "512,160,false,false,1,channel message,false",
        "320,512,true,true,0,none,false",
        "320,512,false,true,0,none,false",
        "160,512,true,true,0,none,false",
        "512,512,false,true,1,channel message,true",
        "2048,512,false,true,1,channel manifest message,true",
        "512,160,false,true,1,channel message,true",
        "512,160,true,true,1,channel message,true",
        "512,260,false,true,1,channel message,true",
        "512,160,false,false,1,channel message,true",
        "320,512,true,true,0,none,true",
        "320,512,false,true,0,none,true"
    })
    void fitsProofWithinPeerLimitWithOptionalManifest(
            final long maxSyncBytes,
            final int manifestLeafBytes,
            final boolean allowPureAck,
            final boolean includeManifest,
            final int expectedMessageCount,
            final String expectedPaths,
            final boolean compact)
            throws Exception {
        final var channelId = Bytes.wrap(new byte[32]);
        final var channelLeaf = Bytes.wrap(new byte[64]);
        final var messageLeaf = Bytes.wrap(new byte[128]);
        final var manifestLeaf = Bytes.wrap(new byte[manifestLeafBytes]);
        final var signature = Bytes.wrap(new byte[] {1});
        final var messageKey = new ClprMessageKey(channelId, 1L);
        if (!compact) {
            given(snapshotProvider.latestSnapshot()).willReturn(Optional.of(snapshot));
        }
        given(snapshot.state()).willReturn(provenState);
        given(snapshot.blockTimestamp())
                .willReturn(Timestamp.newBuilder().seconds(1L).build());
        given(snapshot.path()).willReturn(MerklePath.DEFAULT);
        given(snapshot.tssSignature()).willReturn(signature);
        if (!compact) {
            // Accept the signature even when no bundle is expected, so a null result must come from sizing.
            lenient().when(snapshot.ledgerId()).thenReturn(Bytes.EMPTY);
            lenient()
                    .when(tssVerifier.verifyTss(eq(Bytes.EMPTY), eq(signature), any()))
                    .thenReturn(true);
        }
        given(binaryState.getKvPath(CHANNELS_STATE_ID, ProtoBytes.PROTOBUF.toBytes(new ProtoBytes(channelId))))
                .willReturn(1L);
        lenient()
                .when(binaryState.getKvPath(MESSAGE_QUEUE_STATE_ID, ClprMessageKey.PROTOBUF.toBytes(messageKey)))
                .thenReturn(2L);
        given(binaryState.getMerkleProof(1L)).willReturn(new MerkleProof(channelLeaf, List.of(), List.of()));
        lenient().when(binaryState.getMerkleProof(2L)).thenReturn(new MerkleProof(messageLeaf, List.of(), List.of()));
        if (includeManifest) {
            given(binaryState.getSingletonPath(ENDPOINT_MANIFEST_STATE_ID)).willReturn(3L);
            given(binaryState.getMerkleProof(3L)).willReturn(new MerkleProof(manifestLeaf, List.of(), List.of()));
        }
        // Compact selection must use its outgoing envelope size for the manifest as well as messages.
        final long compactOverhead = compact ? 512L : 0L;
        final var throttles = ClprThrottles.newBuilder()
                .maxMessagesPerBundle(1)
                .maxSyncBytes(maxSyncBytes + compactOverhead)
                .build();

        final var bundle = compact
                ? subject.buildBundleProof(
                        snapshot,
                        channelId,
                        1L,
                        throttles,
                        allowPureAck,
                        includeManifest,
                        proof -> proof.protobufSize() + compactOverhead)
                : subject.buildBundleProof(channelId, 1L, throttles, allowPureAck, includeManifest);

        if (expectedPaths.equals("none")) {
            assertThat(bundle).isNull();
            return;
        }
        assertThat(bundle).isNotNull();
        assertThat(bundle.messageCount()).isEqualTo(expectedMessageCount);
        assertThat(bundle.lastMessageId()).isEqualTo(expectedMessageCount);
        final var proof = StateProof.PROTOBUF.parse(bundle.payload().toReadableSequentialData());
        final var leafNames = Map.of(channelLeaf, "channel", manifestLeaf, "manifest", messageLeaf, "message");
        assertThat(proof.paths())
                .extracting(path -> path.hasTimestampLeaf() ? "timestamp" : leafNames.get(path.stateItemLeaf()))
                .containsExactly(expectedPaths.split(" "));
        if (expectedPaths.contains("timestamp")) {
            final var manifestPath = proof.paths().stream()
                    .filter(path -> manifestLeaf.equals(path.stateItemLeaf()))
                    .findFirst()
                    .orElseThrow();
            final var digest = StateProofVerifier.inferDigestType(proof).buildDigest();
            final var root = StateProofVerifier.computeBlockRootHashFromPath(manifestPath, digest);
            assertThat(ClprProofTimestamp.verifiedTimestamp(proof, root, digest))
                    .isEqualTo(snapshot.blockTimestamp());
        }
        assertThat(proof.protobufSize() + compactOverhead)
                .isLessThanOrEqualTo(subject.maxBundlePayloadBytes(throttles));
        final var envelope = ClprSyncPayload.newBuilder()
                .channelId(channelId)
                .bundlePayload(bundle.payload())
                .build();
        assertThat((long) envelope.protobufSize()).isLessThanOrEqualTo(maxSyncBytes);
    }

    @Test
    void rejectsPeerSyncLimitThatCannotFitEnvelopeBeforeBuildingProof() {
        given(snapshotProvider.latestSnapshot()).willReturn(Optional.of(snapshot));
        given(snapshot.state()).willReturn(provenState);
        final var throttles =
                ClprThrottles.newBuilder().maxSyncBytes(Long.MIN_VALUE).build();

        assertThat(subject.buildBundleProof(Bytes.wrap(new byte[32]), 1L, throttles, true, true))
                .isNull();
    }

    /** Stubs the snapshot and BinaryState so buildSingletonProof produces a real StateProof. */
    private void givenProvableSnapshot() {
        givenSnapshotState();
        given(snapshot.tssSignature()).willReturn(Bytes.EMPTY);
        given(binaryState.getSingletonPath(ENDPOINT_MANIFEST_STATE_ID)).willReturn(1L);
        given(binaryState.getMerkleProof(1L)).willReturn(new MerkleProof(Bytes.EMPTY, List.of(), List.of()));
    }

    private void givenSnapshotState() {
        given(snapshotProvider.latestSnapshot()).willReturn(Optional.of(snapshot));
        given(snapshot.state()).willReturn(provenState);
        given(snapshot.blockTimestamp())
                .willReturn(Timestamp.newBuilder().seconds(1L).build());
        given(snapshot.path()).willReturn(MerklePath.DEFAULT);
    }
}
