// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event;

import static com.swirlds.platform.test.fixtures.event.EventImplTestUtils.createEventImpl;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.utils.EqualsVerifier;
import java.util.List;
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
}
