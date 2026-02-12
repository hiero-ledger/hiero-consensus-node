// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertAllDatabasesClosed;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.binary.MerkleProof;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateImpl;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.WritableQueueState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.merkle.MerkleTestBase;
import com.swirlds.virtualmap.VirtualMap;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test that exercises {@link StateProofBuilder} and {@link StateProofVerifier}
 * against the real {@code State} API using a {@link com.swirlds.state.merkle.VirtualMapState} fixture.
 */
class StateProofIntegrationTest extends MerkleTestBase {

    private VirtualMapState state;

    /**
     * Build a realistic {@link com.swirlds.state.merkle.VirtualMapState} instance with three different state types (queue,
     * singleton, kv-map) so the integration test can exercise heterogeneous proofs and verify that
     * the builder handles mixed path structures coming from the real state API.
     */
    @BeforeEach
    void setUp() {
        setupConstructableRegistry();
        setupFruitVirtualMap();
        setupSingletonCountry();
        setupSteamQueue();

        state = new VirtualMapStateImpl(CONFIGURATION, new NoOpMetrics());
        state.initializeState(fruitMetadata);
        state.initializeState(countryMetadata);
        state.initializeState(steamMetadata);

        final VirtualMap virtualMap = state.getRoot();
        addKvState(virtualMap, fruitMetadata, A_KEY, APPLE);
        addKvState(virtualMap, fruitMetadata, B_KEY, BANANA);
        addSingletonState(virtualMap, countryMetadata, GHANA);

        final WritableStates writableStates = state.getWritableStates(FIRST_SERVICE);
        final WritableQueueState<ProtoBytes> queueState = writableStates.getQueue(STEAM_STATE_ID);
        queueState.add(ART);
        queueState.add(BIOLOGY);
        queueState.add(CHEMISTRY);
        ((CommittableWritableStates) writableStates).commit();

        // Ensure the state tree is hashed so proofs can be generated.
        state.getHash();
    }

    @AfterEach
    void tearDown() {
        if (state != null) {
            state.release();
        }
        if (fruitVirtualMap != null && fruitVirtualMap.getReservationCount() > -1) {
            fruitVirtualMap.release();
        }
        assertAllDatabasesClosed();
    }

    /**
     * Build proofs for a queue element, a singleton value, and a kv-map entry; merge them into a single
     * {@link StateProof}; and confirm the verifier can reconstruct the root hash produced by the state.
     * This mirrors how the production code will bundle heterogeneous paths coming from a real state snapshot.
     */
    @Test
    void buildsAndVerifiesStateProofFromRealState() {
        final long mapPath = state.getKvPath(FRUIT_STATE_ID, ProtoBytes.PROTOBUF.toBytes(A_KEY));
        final long singletonPath = state.getSingletonPath(COUNTRY_STATE_ID);
        final long queuePath = state.getQueueElementPath(STEAM_STATE_ID, ProtoBytes.PROTOBUF.toBytes(ART));

        final MerkleProof mapProof = state.getMerkleProof(mapPath);
        final MerkleProof singletonProof = state.getMerkleProof(singletonPath);
        final MerkleProof queueProof = state.getMerkleProof(queuePath);

        assertThat(mapProof).isNotNull();
        assertThat(singletonProof).isNotNull();
        assertThat(queueProof).isNotNull();

        final StateProofBuilder builder = StateProofBuilder.newBuilder()
                .addProof(mapProof)
                .addProof(singletonProof)
                .addProof(queueProof);
        final var stateProof = builder.build();

        final byte[] expectedRoot = state.getHash().copyToByteArray();
        assertThat(builder.aggregatedRootHash()).isEqualTo(expectedRoot);
        assertThat(stateProof.signedBlockProof().blockSignature()).isEqualTo(Bytes.wrap(expectedRoot));

        assertThat(StateProofVerifier.verify(stateProof)).isTrue();
        assertThat(StateProofVerifier.verifyRootHashForTest(stateProof, expectedRoot))
                .isTrue();
    }
}
