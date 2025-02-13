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

package com.swirlds.platform.state;

import static com.swirlds.common.merkle.utility.MerkleUtils.rehashTree;
import static com.swirlds.common.test.fixtures.RandomUtils.getRandomPrintSeed;
import static com.swirlds.common.test.fixtures.RandomUtils.randomHash;
import static com.swirlds.common.test.fixtures.RandomUtils.randomInstant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.swirlds.common.crypto.Hash;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.platform.consensus.ConsensusSnapshot;
import com.swirlds.platform.event.AncientMode;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.BasicSoftwareVersion;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BirthRoundStateMigrationTests {

    private PlatformStateFacade platformStateFacade;

    @BeforeEach
    void setUp() {
        MerkleDb.resetDefaultInstancePath();
        platformStateFacade = new PlatformStateFacade(version -> new BasicSoftwareVersion(version.major()));
    }

    @AfterEach
    void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    @NonNull
    private SignedState generateSignedState(@NonNull final Random random) {

        final long round = random.nextLong(1, 1_000_000);

        final List<Hash> judgeHashes = new ArrayList<>();
        final int judgeHashCount = random.nextInt(5, 10);
        for (int i = 0; i < judgeHashCount; i++) {
            judgeHashes.add(randomHash(random));
        }

        final Instant consensusTimestamp = randomInstant(random);

        final long nextConsensusNumber = random.nextLong(0, Long.MAX_VALUE);

        final List<MinimumJudgeInfo> minimumJudgeInfoList = new ArrayList<>();
        long generation = random.nextLong(1, 1_000_000);
        for (int i = 0; i < 26; i++) {
            final long judgeRound = round - 25 + i;
            minimumJudgeInfoList.add(new MinimumJudgeInfo(judgeRound, generation));
            generation += random.nextLong(1, 100);
        }

        final ConsensusSnapshot snapshot = new ConsensusSnapshot(
                round, judgeHashes, minimumJudgeInfoList, nextConsensusNumber, consensusTimestamp);

        return new RandomSignedStateGenerator(random)
                .setConsensusSnapshot(snapshot)
                .setRound(round)
                .build();
    }

    @Test
    void generationModeTest() {
        final Random random = getRandomPrintSeed();
        final SignedState signedState = generateSignedState(random);
        final Hash originalHash = signedState.getState().getHash();

        final SoftwareVersion previousSoftwareVersion =
                platformStateFacade.creationSoftwareVersionOf(signedState.getState());

        final SoftwareVersion newSoftwareVersion = createNextVersion(previousSoftwareVersion);

        BirthRoundStateMigration.modifyStateForBirthRoundMigration(
                signedState, AncientMode.GENERATION_THRESHOLD, newSoftwareVersion, platformStateFacade);

        assertEquals(originalHash, signedState.getState().getHash());

        // Rehash the state, just in case
        rehashTree(signedState.getState());

        assertEquals(originalHash, signedState.getState().getHash());
    }

    @Test
    void alreadyMigratedTest() {
        final Random random = getRandomPrintSeed();

        final SignedState signedState = generateSignedState(random);

        final SoftwareVersion previousSoftwareVersion =
                platformStateFacade.creationSoftwareVersionOf(signedState.getState());

        final SoftwareVersion newSoftwareVersion = createNextVersion(previousSoftwareVersion);

        platformStateFacade.bulkUpdateOf(signedState.getState(), v -> {
            v.setLastRoundBeforeBirthRoundMode(signedState.getRound() - 100);
            v.setFirstVersionInBirthRoundMode(previousSoftwareVersion);
            v.setLowestJudgeGenerationBeforeBirthRoundMode(100);
        });
        rehashTree(signedState.getState());
        final Hash originalHash = signedState.getState().getHash();

        BirthRoundStateMigration.modifyStateForBirthRoundMigration(
                signedState, AncientMode.BIRTH_ROUND_THRESHOLD, newSoftwareVersion, platformStateFacade);

        assertEquals(originalHash, signedState.getState().getHash());

        // Rehash the state, just in case
        rehashTree(signedState.getState());

        assertEquals(originalHash, signedState.getState().getHash());
    }

    private static SoftwareVersion createNextVersion(SoftwareVersion previousSoftwareVersion) {
        return new BasicSoftwareVersion(
                previousSoftwareVersion.getPbjSemanticVersion().major() + 1);
    }

    @Test
    void migrationTest() {
        final Random random = getRandomPrintSeed();
        final SignedState signedState = generateSignedState(random);
        final Hash originalHash = signedState.getState().getHash();

        final SoftwareVersion previousSoftwareVersion =
                platformStateFacade.creationSoftwareVersionOf(signedState.getState());

        final SoftwareVersion newSoftwareVersion = createNextVersion(previousSoftwareVersion);

        final long lastRoundMinimumJudgeGeneration = platformStateFacade
                .consensusSnapshotOf(signedState.getState())
                .getMinimumJudgeInfoList()
                .getLast()
                .minimumJudgeAncientThreshold();

        BirthRoundStateMigration.modifyStateForBirthRoundMigration(
                signedState, AncientMode.BIRTH_ROUND_THRESHOLD, newSoftwareVersion, platformStateFacade);

        assertNotEquals(originalHash, signedState.getState().getHash());

        // We expect these fields to be populated at the migration boundary
        assertEquals(
                newSoftwareVersion.getPbjSemanticVersion(),
                platformStateFacade
                        .firstVersionInBirthRoundModeOf(signedState.getState())
                        .getPbjSemanticVersion());
        assertEquals(
                lastRoundMinimumJudgeGeneration,
                platformStateFacade.lowestJudgeGenerationBeforeBirthRoundModeOf(signedState.getState()));
        assertEquals(
                signedState.getRound(), platformStateFacade.lastRoundBeforeBirthRoundModeOf(signedState.getState()));

        // All of the judge info objects should now be using a birth round equal to the round of the state
        for (final MinimumJudgeInfo minimumJudgeInfo :
                platformStateFacade.consensusSnapshotOf(signedState.getState()).getMinimumJudgeInfoList()) {
            assertEquals(signedState.getRound(), minimumJudgeInfo.minimumJudgeAncientThreshold());
        }
    }
}
