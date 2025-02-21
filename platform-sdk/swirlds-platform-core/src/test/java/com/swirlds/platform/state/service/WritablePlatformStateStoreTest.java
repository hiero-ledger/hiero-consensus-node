/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.state.service;

import static com.swirlds.common.test.fixtures.RandomUtils.nextInt;
import static com.swirlds.common.test.fixtures.RandomUtils.randomHash;
import static com.swirlds.platform.state.service.PbjConverter.toPbjPlatformState;
import static com.swirlds.platform.state.service.PbjConverterTest.randomAddressBook;
import static com.swirlds.platform.state.service.PbjConverterTest.randomPlatformState;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_KEY;
import static com.swirlds.platform.test.fixtures.config.ConfigUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.hedera.hapi.platform.state.PlatformState;
import com.swirlds.common.crypto.DigestType;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.platform.system.BasicSoftwareVersion;
import com.swirlds.state.merkle.StateUtils;
import com.swirlds.state.merkle.disk.OnDiskWritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.virtualmap.VirtualMap;
import java.time.Instant;
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

    @BeforeEach
    void setUp() {
        randotron = Randotron.create();

        final MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        final var tableConfig =
                new MerkleDbTableConfig((short) 1, DigestType.SHA_384, 1, merkleDbConfig.hashesRamToDiskThreshold());
        final var virtualMapLabel = "VirtualMap";
        final var dsBuilder = new MerkleDbDataSourceBuilder(tableConfig, CONFIGURATION);
        final var virtualMap = new VirtualMap(virtualMapLabel, dsBuilder, CONFIGURATION);

        virtualMap.put(
                StateUtils.getVirtualMapKey(PlatformStateService.NAME, PLATFORM_STATE_KEY),
                toPbjPlatformState(randomPlatformState(randotron)),
                PlatformState.PROTOBUF);

        when(writableStates.<PlatformState>getSingleton(PLATFORM_STATE_KEY))
                .thenReturn(new OnDiskWritableSingletonState<>(
                        PlatformStateService.NAME, PLATFORM_STATE_KEY, PlatformState.PROTOBUF, virtualMap));
        store = new WritablePlatformStateStore(writableStates, (version) -> new BasicSoftwareVersion(version.major()));
    }

    @Test
    void verifySetAllFrom() {
        final var platformState = randomPlatformState(randotron);
        store.setAllFrom(platformState);
        assertEquals(
                platformState.getCreationSoftwareVersion().getPbjSemanticVersion(),
                store.getCreationSoftwareVersion().getPbjSemanticVersion());
        assertEquals(platformState.getAddressBook(), store.getAddressBook());
        assertEquals(platformState.getPreviousAddressBook(), store.getPreviousAddressBook());
        assertEquals(platformState.getSnapshot().round(), store.getRound());
        assertEquals(platformState.getLegacyRunningEventHash(), store.getLegacyRunningEventHash());
        assertEquals(platformState.getSnapshot().consensusTimestamp(), store.getConsensusTimestamp());
        assertEquals(platformState.getRoundsNonAncient(), store.getRoundsNonAncient());
        assertEquals(platformState.getSnapshot(), store.getSnapshot());
        assertEquals(platformState.getFreezeTime(), store.getFreezeTime());
        assertEquals(
                platformState.getFirstVersionInBirthRoundMode().getPbjSemanticVersion(),
                store.getFirstVersionInBirthRoundMode().getPbjSemanticVersion());
        assertEquals(platformState.getLastRoundBeforeBirthRoundMode(), store.getLastRoundBeforeBirthRoundMode());
        assertEquals(
                platformState.getLowestJudgeGenerationBeforeBirthRoundMode(),
                store.getLowestJudgeGenerationBeforeBirthRoundMode());
    }

    @Test
    void verifyCreationSoftwareVersion() {
        final var version = nextInt(1, 100);
        store.setCreationSoftwareVersion(new BasicSoftwareVersion(version));
        assertEquals(
                version,
                store.getCreationSoftwareVersion().getPbjSemanticVersion().major());
    }

    @Test
    void verifyAddressBook() {
        final var addressBook = randomAddressBook(randotron);
        store.setAddressBook(addressBook);
        assertEquals(addressBook, store.getAddressBook());
    }

    @Test
    void verifyPreviousAddressBook() {
        final var addressBook = randomAddressBook(randotron);
        store.setPreviousAddressBook(addressBook);
        assertEquals(addressBook, store.getPreviousAddressBook());
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

    @Test
    void verifyFirstVersionInBirthRoundMode() {
        final var version = nextInt(1, 100);
        store.setFirstVersionInBirthRoundMode(new BasicSoftwareVersion(version));
        assertEquals(
                version,
                store.getFirstVersionInBirthRoundMode().getPbjSemanticVersion().major());
    }

    @Test
    void verifyLastRoundBeforeBirthRoundMode() {
        final var round = nextInt(1, 100);
        store.setLastRoundBeforeBirthRoundMode(round);
        assertEquals(round, store.getLastRoundBeforeBirthRoundMode());
    }

    @Test
    void verifyLowestJudgeGenerationBeforeBirthRoundMode() {
        final var generation = nextInt(1, 100);
        store.setLowestJudgeGenerationBeforeBirthRoundMode(generation);
        assertEquals(generation, store.getLowestJudgeGenerationBeforeBirthRoundMode());
    }
}
