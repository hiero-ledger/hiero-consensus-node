// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures;

import static com.swirlds.common.test.fixtures.RandomUtils.nextInt;
import static com.swirlds.common.test.fixtures.RandomUtils.randomHash;
import static com.swirlds.common.test.fixtures.RandomUtils.randomHashBytes;
import static com.swirlds.common.test.fixtures.RandomUtils.randomInstant;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.swirlds.platform.state.PlatformStateModifier;
import com.swirlds.platform.system.BasicSoftwareVersion;
import com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade;
import com.swirlds.state.State;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.utility.CommonUtils;

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
            v.setCreationSoftwareVersion(new BasicSoftwareVersion(nextInt(1, 100)).getPbjSemanticVersion());
        });

        final List<MinimumJudgeInfo> minimumJudgeInfo = new LinkedList<>();
        for (int index = 0; index < 10; index++) {
            minimumJudgeInfo.add(new MinimumJudgeInfo(random.nextLong(), random.nextLong()));
        }
        platformStateFacade.setSnapshotTo(
                state,
                new ConsensusSnapshot(
                        random.nextLong(),
                        List.of(randomHashBytes(random), randomHashBytes(random), randomHashBytes(random)),
                        minimumJudgeInfo,
                        random.nextLong(),
                        CommonUtils.toPbjTimestamp(randomInstant(random))));

        return platformStateFacade.getWritablePlatformStateOf(state);
    }
}
