// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.sync;

import com.swirlds.platform.gossip.shadowgraph.ShadowEvent;
import com.swirlds.platform.test.fixtures.event.TestingEventBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Random;
import org.hiero.consensus.model.event.PlatformEvent;

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

        final TestingEventBuilder eventBuilder = new TestingEventBuilder(random);
        final PlatformEvent platformEvent = eventBuilder
                .setSelfParent(selfParent == null ? null : selfParent.getEvent())
                .setOtherParent(otherParent == null ? null : otherParent.getEvent())
                .build();

        return new ShadowEvent(platformEvent, selfParent, otherParent);
    }
}
