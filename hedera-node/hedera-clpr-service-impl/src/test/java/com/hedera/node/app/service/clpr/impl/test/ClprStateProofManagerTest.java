// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.spi.state.BlockProvenSnapshot;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.BinaryState;
import com.swirlds.state.State;
import com.swirlds.state.binary.MerkleProof;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ClprStateProofManager#buildManifestStateProofWithValue()} that drive the real
 * manager, mocking only its collaborators.
 */
@ExtendWith(MockitoExtension.class)
class ClprStateProofManagerTest {

    @Mock
    private BlockProvenSnapshotProvider snapshotProvider;

    @Mock
    private TssVerifier tssVerifier;

    @Mock
    private ConfigProvider configProvider;

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
        subject = new ClprStateProofManager(snapshotProvider, tssVerifier, configProvider);
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

    /** Stubs the snapshot and BinaryState so buildSingletonProof produces a real StateProof. */
    private void givenProvableSnapshot() {
        given(snapshotProvider.latestSnapshot()).willReturn(Optional.of(snapshot));
        given(snapshot.state()).willReturn(provenState);
        given(snapshot.blockTimestamp())
                .willReturn(Timestamp.newBuilder().seconds(1L).build());
        given(snapshot.path()).willReturn(MerklePath.DEFAULT);
        given(snapshot.tssSignature()).willReturn(Bytes.EMPTY);
        given(binaryState.getSingletonPath(ENDPOINT_MANIFEST_STATE_ID)).willReturn(1L);
        given(binaryState.getMerkleProof(1L)).willReturn(new MerkleProof(Bytes.EMPTY, List.of(), List.of()));
    }
}
