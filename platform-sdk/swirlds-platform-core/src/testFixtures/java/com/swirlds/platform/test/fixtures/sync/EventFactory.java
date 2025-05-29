// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.sync;

import com.swirlds.platform.gossip.shadowgraph.ShadowEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Stream;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;

/**
 * A simple, deterministic factory for Event instances
 */
public class EventFactory {
    public static ShadowEvent makeShadow(@NonNull final Random random) {
        return makeShadow(random, null);
    }

    public static ShadowEvent makeShadow(@NonNull final Random random, final ShadowEvent selfParent) {
        return makeShadow(random, selfParent, null);
    }

    public static ShadowEvent makeShadow(
            @NonNull final Random random, final ShadowEvent selfParent, final ShadowEvent otherParent) {

        final long maxParentsBirthRound = Stream.of(selfParent, otherParent)
                .filter(Objects::nonNull)
                .map(ShadowEvent::getEvent)
                .mapToLong(PlatformEvent::getBirthRound)
                .max()
                .orElse(ConsensusConstants.ROUND_FIRST - 1);
        final TestingEventBuilder eventBuilder = new TestingEventBuilder(random);
        final PlatformEvent platformEvent = eventBuilder
                .setSelfParent(selfParent == null ? null : selfParent.getEvent())
                .setOtherParent(otherParent == null ? null : otherParent.getEvent())
                .setBirthRound(maxParentsBirthRound + 1)
                .build();

        return new ShadowEvent(platformEvent, selfParent, otherParent);
    }
}
