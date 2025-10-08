// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.platform.state.PlatformStateAccessor;
import com.swirlds.platform.test.fixtures.state.MerkleTestBase;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.virtualmap.VirtualMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HederaVirtualMapStateTest extends MerkleTestBase {

    private HederaVirtualMapState virtualMapState;

    /**
     * Start with an empty Virtual Map State, but with the "fruit" map and metadata created and ready to
     * be added.
     */
    @BeforeEach
    void setUp() {
        virtualMapState = new HederaVirtualMapState(CONFIGURATION, new NoOpMetrics());
        setupFruitVirtualMap();
        setupSingletonCountry();
        setupSteamQueue();

        // adding queue state via State API, to init the QueueState
        virtualMapState.initializeState(steamMetadata);
        final var writableStates = virtualMapState.getWritableStates(FIRST_SERVICE);
        writableStates.getQueue(STEAM_STATE_ID).add(ART);
        ((CommittableWritableStates) writableStates).commit();
        virtualMapState.init(
                new FakeTime(),
                new NoOpMetrics(),
                mock(MerkleCryptography.class),
                () -> PlatformStateAccessor.GENESIS_ROUND);
    }

    @Test
    @DisplayName("Checking the content of getInfoJson")
    void testGetInfoJson() {
        // adding k/v and singleton states directly to the virtual map
        final var virtualMap = (VirtualMap) virtualMapState.getRoot();
        addKvState(fruitVirtualMap, fruitMetadata, A_KEY, APPLE);
        addKvState(fruitVirtualMap, fruitMetadata, B_KEY, BANANA);
        addSingletonState(virtualMap, countryMetadata, GHANA);

        // Given a State with the fruit and animal and country states
        virtualMapState.initializeState(fruitMetadata);
        virtualMapState.initializeState(countryMetadata);
        virtualMapState.initializeState(steamMetadata);
        // adding queue state via State API, to init the QueueState
        final var writableStates = virtualMapState.getWritableStates(FIRST_SERVICE);
        writableStates.getQueue(STEAM_STATE_ID).add(ART);
        ((CommittableWritableStates) writableStates).commit();

        // hash the state
        virtualMapState.getHash();

        // Then we can check the content of getInfoJson
        final String infoJson = virtualMapState.getInfoJson();
        assertThat(infoJson)
                .isEqualTo("{" + "\"Queues (Queue States)\":"
                        + "{\"First-Service." + STEAM_STATE_KEY + "\":{\"head\":1,\"path\":5,\"tail\":3}},"
                        + "\"VirtualMapMetadata\":{\"firstLeafPath\":3,\"lastLeafPath\":6},"
                        + "\"Singletons\":"
                        + "{\"First-Service." + COUNTRY_STATE_KEY
                        + "\":{\"path\":4,\"mnemonic\":\"cushion-bright-early-flight\"}}}");
    }

    @AfterEach
    void tearDown() {
        virtualMapState.release();
        fruitVirtualMap.release();
    }
}
