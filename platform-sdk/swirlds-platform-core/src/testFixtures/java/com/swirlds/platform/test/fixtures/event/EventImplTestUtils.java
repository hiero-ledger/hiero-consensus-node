// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event;

import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Utility methods for creating {@link EventImpl} instances for testing.
 */
public class EventImplTestUtils {
    /**
     * Hidden constructor
     */
    private EventImplTestUtils() {}

    /**
     * Create an {@link EventImpl} with the given {@link TestingEventBuilder} as a starting point, a self parent, and
     * an other parent.
     * <p>
     * The {@link TestingEventBuilder} passed into this method shouldn't have parents set. This method will set the
     * parents on the builder, and will also set the parents on the {@link EventImpl} that is created.
     *
     * @param eventBuilder the {@link TestingEventBuilder} to use
     * @param selfParent         the self parent to use
     * @param otherParent        the other parent to use
     * @return the created {@link EventImpl}
     */
    public static EventImpl createEventImpl(
            @NonNull final TestingEventBuilder eventBuilder,
            @Nullable final EventImpl selfParent,
            @Nullable final EventImpl otherParent) {

        final PlatformEvent selfParentPlatformEvent = selfParent == null ? null : selfParent.getBaseEvent();
        final PlatformEvent otherParentPlatformEvent = otherParent == null ? null : otherParent.getBaseEvent();

        final PlatformEvent platformEvent = eventBuilder
                .setSelfParent(selfParentPlatformEvent)
                .setOtherParent(otherParentPlatformEvent)
                .build();

        return new EventImpl(platformEvent, selfParent, otherParent);
    }
}
