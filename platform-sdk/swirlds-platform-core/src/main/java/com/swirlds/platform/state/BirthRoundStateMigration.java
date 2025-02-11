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

import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.swirlds.common.merkle.crypto.MerkleCryptoFactory;
import com.swirlds.platform.consensus.ConsensusSnapshot;
import com.swirlds.platform.event.AncientMode;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.SoftwareVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A utility for migrating the state when birth round mode is first enabled.
 */
public final class BirthRoundStateMigration {

    private static final Logger logger = LogManager.getLogger(BirthRoundStateMigration.class);

    private BirthRoundStateMigration() {}

    /**
     * Perform required state changes for the migration to birth round mode. This method is a no-op if it is not yet
     * time to migrate, or if the migration has already been completed.
     *
     * @param initialState the initial state the platform is starting with
     * @param ancientMode  the current ancient mode
     * @param appVersion   the current application version
     */
    public static void modifyStateForBirthRoundMigration(
            @NonNull final SignedState initialState,
            @NonNull final AncientMode ancientMode,
            @NonNull final SoftwareVersion appVersion,
            @NonNull final PlatformStateFacade platformStateFacade) {

        if (ancientMode == AncientMode.GENERATION_THRESHOLD) {
            if (platformStateFacade.firstVersionInBirthRoundModeOf(initialState.getState()) != null) {
                throw new IllegalStateException(
                        "Cannot revert to generation mode after birth round migration has been completed.");
            }

            logger.info(
                    STARTUP.getMarker(), "Birth round state migration is not yet needed, still in generation mode.");
            return;
        }

        final PlatformMerkleStateRoot state = initialState.getState();
        final boolean alreadyMigrated = platformStateFacade.firstVersionInBirthRoundModeOf(state) != null;
        if (alreadyMigrated) {
            // Birth round migration was completed at a prior time, no action needed.
            logger.info(STARTUP.getMarker(), "Birth round state migration has already been completed.");
            return;
        }

        final long lastRoundBeforeMigration = platformStateFacade.roundOf(state);

        final ConsensusSnapshot consensusSnapshot =
                Objects.requireNonNull(platformStateFacade.consensusSnapshotOf(state));
        final List<MinimumJudgeInfo> judgeInfoList = consensusSnapshot.getMinimumJudgeInfoList();
        final long lowestJudgeGenerationBeforeMigration =
                judgeInfoList.getLast().minimumJudgeAncientThreshold();

        logger.info(
                STARTUP.getMarker(),
                "Birth round state migration in progress. First version in birth round mode: {}, "
                        + "last round before migration: {}, lowest judge generation before migration: {}",
                appVersion,
                lastRoundBeforeMigration,
                lowestJudgeGenerationBeforeMigration);

        platformStateFacade.bulkUpdateOf(state, v -> {
            v.setFirstVersionInBirthRoundMode(appVersion);
            v.setLastRoundBeforeBirthRoundMode(lastRoundBeforeMigration);
            v.setLowestJudgeGenerationBeforeBirthRoundMode(lowestJudgeGenerationBeforeMigration);
        });

        final List<MinimumJudgeInfo> modifiedJudgeInfoList = new ArrayList<>(judgeInfoList.size());
        for (final MinimumJudgeInfo judgeInfo : judgeInfoList) {
            modifiedJudgeInfoList.add(new MinimumJudgeInfo(judgeInfo.round(), lastRoundBeforeMigration));
        }
        final ConsensusSnapshot modifiedConsensusSnapshot = new ConsensusSnapshot(
                consensusSnapshot.round(),
                consensusSnapshot.judgeHashes(),
                modifiedJudgeInfoList,
                consensusSnapshot.nextConsensusNumber(),
                consensusSnapshot.consensusTimestamp());
        platformStateFacade.setSnapshotTo(state, modifiedConsensusSnapshot);

        state.invalidateHash();
        MerkleCryptoFactory.getInstance().digestTreeSync(state);
    }
}
