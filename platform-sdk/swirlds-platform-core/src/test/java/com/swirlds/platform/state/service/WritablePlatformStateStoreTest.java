// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service;

import static com.swirlds.platform.state.service.PbjConverter.toPbjPlatformState;
import static com.swirlds.platform.state.service.PbjConverterTest.randomPlatformState;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_KEY;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomHash;
import static org.hiero.base.utility.test.fixtures.RandomUtils.nextInt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.hapi.platform.state.VirtualMapValue;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.platform.test.fixtures.virtualmap.VirtualMapUtils;
import com.swirlds.state.merkle.StateUtils;
import com.swirlds.state.merkle.disk.OnDiskWritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.virtualmap.VirtualMap;
import java.time.Instant;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritablePlatformStateStoreTest {

    @Mock
    private WritableStates writableStates;

    private WritablePlatformStateStore store;

    private Randotron randotron;
    private VirtualMap virtualMap;

    @BeforeEach
    void setUp() {
        randotron = Randotron.create();

        final String virtualMapLabel =
                "vm-" + WritablePlatformStateStoreTest.class.getSimpleName() + java.util.UUID.randomUUID();
        virtualMap = VirtualMapUtils.createVirtualMap(virtualMapLabel, 1);

        final Bytes key = StateUtils.getVirtualMapKeyForSingleton(PlatformStateService.NAME, PLATFORM_STATE_KEY);
        final VirtualMapValue value = StateUtils.getVirtualMapValue(
                PlatformStateService.NAME, PLATFORM_STATE_KEY, toPbjPlatformState(randomPlatformState(randotron)));

        virtualMap.put(key, value, VirtualMapValue.PROTOBUF);

        when(writableStates.<PlatformState>getSingleton(PLATFORM_STATE_KEY))
                .thenReturn(
                        new OnDiskWritableSingletonState<>(PlatformStateService.NAME, PLATFORM_STATE_KEY, virtualMap));
        store = new WritablePlatformStateStore(writableStates);
    }

    @Test
    void verifySetAllFrom() {
        final var platformState = randomPlatformState(randotron);
        store.setAllFrom(platformState);
        assertEquals(platformState.getCreationSoftwareVersion(), store.getCreationSoftwareVersion());
        assertEquals(platformState.getSnapshot().round(), store.getRound());
        assertEquals(platformState.getLegacyRunningEventHash(), store.getLegacyRunningEventHash());
        assertEquals(
                CommonUtils.fromPbjTimestamp(platformState.getSnapshot().consensusTimestamp()),
                store.getConsensusTimestamp());
        assertEquals(platformState.getRoundsNonAncient(), store.getRoundsNonAncient());
        assertEquals(platformState.getSnapshot(), store.getSnapshot());
        assertEquals(platformState.getFreezeTime(), store.getFreezeTime());
    }

    @Test
    void verifyCreationSoftwareVersion() {
        final var version = nextInt(1, 100);
        store.setCreationSoftwareVersion(
                SemanticVersion.newBuilder().major(version).build());
        assertEquals(version, store.getCreationSoftwareVersion().major());
    }

    @Test
    void verifyRound() {
        final var round = nextInt(1, 100);
        store.setRound(round);
        assertEquals(round, store.getRound());
    }

    @Test
    void verifyLegacyRunningEventHash() {
        final var hash = randomHash();
        store.setLegacyRunningEventHash(hash);
        assertEquals(hash, store.getLegacyRunningEventHash());
    }

    @Test
    void verifyConsensusTimestamp() {
        final var consensusTimestamp = Instant.now();
        store.setConsensusTimestamp(consensusTimestamp);
        assertEquals(consensusTimestamp, store.getConsensusTimestamp());
    }

    @Test
    void verifyRoundsNonAncient() {
        final var roundsNonAncient = nextInt(1, 100);
        store.setRoundsNonAncient(roundsNonAncient);
        assertEquals(roundsNonAncient, store.getRoundsNonAncient());
    }

    @Test
    void verifySnapshot() {
        final var platformState = randomPlatformState(randotron);
        store.setSnapshot(platformState.getSnapshot());
        assertEquals(platformState.getSnapshot(), store.getSnapshot());
    }

    @Test
    void verifyFreezeTime() {
        final var freezeTime = Instant.now();
        store.setFreezeTime(freezeTime);
        assertEquals(freezeTime, store.getFreezeTime());
    }

    @Test
    void verifyLastFrozenTime() {
        final var lastFrozenTime = Instant.now();
        store.setLastFrozenTime(lastFrozenTime);
        assertEquals(lastFrozenTime, store.getLastFrozenTime());
    }

    @AfterEach
    void tearDown() {
        virtualMap.release();
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }
}
