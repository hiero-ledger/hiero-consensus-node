/*
 * Copyright (C) 2021-2025 Hedera Hashgraph, LLC
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

package com.swirlds.platform.test;

import static com.swirlds.common.test.fixtures.RandomUtils.nextInt;
import static com.swirlds.common.test.fixtures.RandomUtils.randomHash;
import static com.swirlds.common.test.fixtures.RandomUtils.randomInstant;

import com.swirlds.platform.consensus.ConsensusSnapshot;
import com.swirlds.platform.state.MinimumJudgeInfo;
import com.swirlds.platform.state.PlatformStateModifier;
import com.swirlds.platform.system.BasicSoftwareVersion;
import com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade;
import com.swirlds.state.State;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

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
            v.setCreationSoftwareVersion(new BasicSoftwareVersion(nextInt(1, 100)));
        });

        final List<MinimumJudgeInfo> minimumJudgeInfo = new LinkedList<>();
        for (int index = 0; index < 10; index++) {
            minimumJudgeInfo.add(new MinimumJudgeInfo(random.nextLong(), random.nextLong()));
        }
        platformStateFacade.setSnapshotTo(
                state,
                new ConsensusSnapshot(
                        random.nextLong(),
                        List.of(randomHash(random), randomHash(random), randomHash(random)),
                        minimumJudgeInfo,
                        random.nextLong(),
                        randomInstant(random)));

        return platformStateFacade.getWritablePlatformStateOf(state);
    }
}
