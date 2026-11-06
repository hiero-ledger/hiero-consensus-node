// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.MESSAGE_QUEUE_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprMessageKey;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
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
                snapshotProvider, tssVerifier, HederaTestConfigBuilder.createConfigProvider());
        binaryState = (BinaryState) provenState;
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
    @DisplayName("blockStream.digestType=SHA_256 -> proof still built correctly, hashing with SHA-256 end-to-end")
    void sha256ConfiguredBuildsValidProof() throws Exception {
        subject = new ClprStateProofManager(
                snapshotProvider, tssVerifier, configProviderWithDigestType(DigestType.SHA_256));
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
    @CsvSource({"512,false,1,2", "2048,false,1,3", "320,true,0,1", "160,true,0,0"})
    void fitsProofWithinPeerLimitWithOptionalManifest(
            final long maxSyncBytes,
            final boolean allowPureAck,
            final int expectedMessageCount,
            final int expectedPathCount)
            throws Exception {
        final var channelId = Bytes.wrap(new byte[32]);
        final var channelLeaf = Bytes.wrap(new byte[64]);
        final var messageLeaf = Bytes.wrap(new byte[128]);
        final var manifestLeaf = Bytes.wrap(new byte[512]);
        final var signature = Bytes.wrap(new byte[] {1});
        final var messageKey = new ClprMessageKey(channelId, 1L);
        givenSnapshotState();
        given(snapshot.tssSignature()).willReturn(signature);
        if (expectedPathCount > 0) {
            given(snapshot.ledgerId()).willReturn(Bytes.EMPTY);
            given(tssVerifier.verifyTss(eq(Bytes.EMPTY), eq(signature), any())).willReturn(true);
        }
        given(binaryState.getKvPath(CHANNELS_STATE_ID, ProtoBytes.PROTOBUF.toBytes(new ProtoBytes(channelId))))
                .willReturn(1L);
        given(binaryState.getKvPath(MESSAGE_QUEUE_STATE_ID, ClprMessageKey.PROTOBUF.toBytes(messageKey)))
                .willReturn(2L);
        given(binaryState.getSingletonPath(ENDPOINT_MANIFEST_STATE_ID)).willReturn(3L);
        given(binaryState.getMerkleProof(1L)).willReturn(new MerkleProof(channelLeaf, List.of(), List.of()));
        given(binaryState.getMerkleProof(2L)).willReturn(new MerkleProof(messageLeaf, List.of(), List.of()));
        given(binaryState.getMerkleProof(3L)).willReturn(new MerkleProof(manifestLeaf, List.of(), List.of()));
        final var throttles = ClprThrottles.newBuilder()
                .maxMessagesPerBundle(1)
                .maxSyncBytes(maxSyncBytes)
                .build();

        final var bundle = subject.buildBundleProof(channelId, 1L, throttles, allowPureAck, true);

        if (expectedPathCount == 0) {
            assertThat(bundle).isNull();
            return;
        }
        assertThat(bundle).isNotNull();
        assertThat(bundle.messageCount()).isEqualTo(expectedMessageCount);
        assertThat(bundle.lastMessageId()).isEqualTo(expectedMessageCount);
        final var proof = StateProof.PROTOBUF.parse(bundle.payload().toReadableSequentialData());
        assertThat(proof.paths()).hasSize(expectedPathCount);
        assertThat(proof.paths().getFirst().stateItemLeaf()).isEqualTo(channelLeaf);
        assertThat(proof.paths().getLast().stateItemLeaf())
                .isEqualTo(expectedMessageCount == 0 ? channelLeaf : messageLeaf);
        final var envelope = ClprSyncPayload.newBuilder()
                .channelId(channelId)
                .bundlePayload(bundle.payload())
                .build();
        assertThat((long) envelope.protobufSize()).isLessThanOrEqualTo(maxSyncBytes);
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
