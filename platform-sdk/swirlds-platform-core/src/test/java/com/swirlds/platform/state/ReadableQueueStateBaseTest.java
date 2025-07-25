// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.swirlds.state.test.fixtures.ListReadableQueueState;
import com.swirlds.state.test.fixtures.ListWritableQueueState;
import com.swirlds.state.test.fixtures.StateTestBase;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class ReadableQueueStateBaseTest extends StateTestBase {
    @Test
    void stateKey() {
        final var subject =
                ListWritableQueueState.builder("FAKE_NAME", "FAKE_KEY").build();
        assertThat(subject.getStateKey()).isEqualTo("FAKE_KEY");
    }

    @Test
    void peekIsNullWhenEmpty() {
        // Given an empty queue
        final var subject =
                ListReadableQueueState.builder("FAKE_NAME", "FAKE_STATE").build();

        // When we peek
        final var element = subject.peek();

        // Then the element is null
        assertThat(element).isNull();
    }

    @Test
    void peekDoesNotRemove() {
        // Given a non-empty queue
        final var subject = readableSTEAMState();
        final var startingElements = new ArrayList<ProtoBytes>();
        subject.iterator().forEachRemaining(startingElements::add);

        // When we peek
        subject.peek();

        // None of the queue elements are removed
        final var endingElements = new ArrayList<ProtoBytes>();
        subject.iterator().forEachRemaining(endingElements::add);
        assertThat(startingElements).containsExactlyElementsOf(endingElements);
    }

    @Test
    void peekTwiceGivesSameElement() {
        // Given a non-empty queue
        final var subject = readableSTEAMState();

        // When we peek twice
        final var firstPeekResult = subject.peek();
        final var secondPeekResult = subject.peek();

        // The elements are the same element
        assertThat(firstPeekResult).isSameAs(secondPeekResult);
    }
}
