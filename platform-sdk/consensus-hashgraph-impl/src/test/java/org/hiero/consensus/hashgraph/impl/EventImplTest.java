// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.platform.test.fixtures.utils.EqualsVerifier;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.junit.jupiter.api.Test;

public class EventImplTest {

    @Test
    void validateEqualsHashCodeCompareTo() {
        final List<EventImpl> list = EqualsVerifier.generateObjects(
                random -> createEventImpl(
                        new TestingEventBuilder(random),
                        createEventImpl(new TestingEventBuilder(random), null, null),
                        createEventImpl(new TestingEventBuilder(random), null, null)),
                new long[] {1, 1, 2});
        assertTrue(EqualsVerifier.verifyEqualsHashCode(list.get(0), list.get(1), list.get(2)));
    }

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
    private static EventImpl createEventImpl(
            @NonNull final TestingEventBuilder eventBuilder,
            @Nullable final EventImpl selfParent,
            @Nullable final EventImpl otherParent) {

        final PlatformEvent selfParentPlatformEvent = selfParent == null ? null : selfParent.getBaseEvent();
        final PlatformEvent otherParentPlatformEvent = otherParent == null ? null : otherParent.getBaseEvent();

        final PlatformEvent platformEvent = eventBuilder
                .setSelfParent(selfParentPlatformEvent)
                .setOtherParent(otherParentPlatformEvent)
                .build();

        return new EventImpl(
                platformEvent,
                Stream.of(selfParent, otherParent).filter(Objects::nonNull).toList());
    }
}
