// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.events;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Random;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;

public class GenerationCalculatorTests {

    @NonNull
    private PlatformEvent buildEvent(
            @NonNull final Random random,
            @NonNull final SemanticVersion softwareVersion,
            final long generation,
            final long birthRound) {

        final NodeId creatorId = NodeId.of(random.nextLong(1, 10));
        final PlatformEvent selfParent = new TestingEventBuilder(random)
                .setCreatorId(creatorId)
                .setBirthRound(random.nextLong(birthRound - 2, birthRound + 1)) /* realistic range */
                .build();

        final PlatformEvent event = new TestingEventBuilder(random)
                .setSoftwareVersion(softwareVersion)
                .setCreatorId(creatorId)
                .setBirthRound(birthRound)
                .setSelfParent(selfParent)
                /* chose parent generation to yield desired self generation */
                .overrideSelfParentGeneration(generation - 1)
                .build();

        new DefaultEventHasher().hashEvent(event);

        return event;
    }

    // TODO
}
