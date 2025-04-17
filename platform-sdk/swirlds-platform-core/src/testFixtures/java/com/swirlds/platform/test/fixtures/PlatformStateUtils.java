// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures;

import static java.util.Arrays.asList;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomHash;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomHashBytes;
import static org.hiero.base.utility.test.fixtures.RandomUtils.nextInt;
import static org.hiero.base.utility.test.fixtures.RandomUtils.randomInstant;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.JudgeId;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.swirlds.platform.state.PlatformStateModifier;
import com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade;
import com.swirlds.state.State;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import org.hiero.base.utility.CommonUtils;

public final class PlatformStateUtils {

    private PlatformStateUtils() {}

    /**
     * Generate a randomized PlatformState object. Values contained internally may be nonsensical.
     */
    public static PlatformStateModifier randomPlatformState(State state, TestPlatformStateFacade platformState) {
        return randomPlatformState(new Random(), state, platformState);
    }

    /**
     * Generate a randomized PlatformState object. Values contained internally may be nonsensical.
     */
    public static PlatformStateModifier randomPlatformState(
            final Random random, State state, TestPlatformStateFacade platformStateFacade) {

        platformStateFacade.bulkUpdateOf(state, v -> {
            v.setLegacyRunningEventHash(randomHash(random));
            v.setRound(random.nextLong());
            v.setConsensusTimestamp(randomInstant(random));
            v.setCreationSoftwareVersion(
                    SemanticVersion.newBuilder().major(nextInt(1, 100)).build());
        });

        final List<MinimumJudgeInfo> minimumJudgeInfo = new LinkedList<>();
        for (int index = 0; index < 10; index++) {
            minimumJudgeInfo.add(new MinimumJudgeInfo(random.nextLong(), random.nextLong()));
        }
        final var judges = asList(
                new JudgeId(0L, randomHashBytes(random)),
                new JudgeId(1L, randomHashBytes(random)),
                new JudgeId(2L, randomHashBytes(random)));
        platformStateFacade.setSnapshotTo(
                state,
                ConsensusSnapshot.newBuilder()
                        .round(random.nextLong())
                        .judgeIds(judges)
                        .minimumJudgeInfoList(minimumJudgeInfo)
                        .nextConsensusNumber(random.nextLong())
                        .consensusTimestamp(CommonUtils.toPbjTimestamp(randomInstant(random)))
                        .build());

        return platformStateFacade.getWritablePlatformStateOf(state);
    }
}
