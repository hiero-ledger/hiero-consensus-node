// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static com.swirlds.platform.test.fixtures.state.FakeConsensusStateEventHandler.CONFIGURATION;
import static com.swirlds.platform.test.fixtures.state.FakeConsensusStateEventHandler.FAKE_CONSENSUS_STATE_EVENT_HANDLER;
import static com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.platform.metrics.StateMetrics;
import com.swirlds.platform.test.fixtures.state.TestNewMerkleStateRoot;
import com.swirlds.state.State;
import com.swirlds.virtualmap.VirtualMap;
import org.hiero.consensus.model.crypto.DigestType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class StateEventHandlerManagerUtilsTests {

    @BeforeEach
    void setup() {}

    @Test
    void testFastCopyIsMutable() {
        final MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        final var tableConfig = new MerkleDbTableConfig(
                (short) 1, DigestType.SHA_384, 100_000, merkleDbConfig.hashesRamToDiskThreshold());
        final var virtualMapLabel = "VirtualMap-StateEventHandlerManagerUtilsTests";
        final var dsBuilder = new MerkleDbDataSourceBuilder(tableConfig, CONFIGURATION);
        final var virtualMap = new VirtualMap(virtualMapLabel, dsBuilder, CONFIGURATION);
        final MerkleNodeState state = new TestNewMerkleStateRoot(virtualMap);
        FAKE_CONSENSUS_STATE_EVENT_HANDLER.initPlatformState(state);
        state.getRoot().reserve();
        final StateMetrics stats = mock(StateMetrics.class);
        final State result = SwirldStateManagerUtils.fastCopy(
                state, stats, SemanticVersion.newBuilder().major(1).build(), TEST_PLATFORM_STATE_FACADE);

        assertFalse(result.isImmutable(), "The copy state should be mutable.");
        assertEquals(
                1,
                state.getRoot().getReservationCount(),
                "Fast copy should not change the reference count of the state it copies.");
        assertEquals(
                1,
                state.getRoot().getReservationCount(),
                "Fast copy should return a new state with a reference count of 1.");
    }
}
