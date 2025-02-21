/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
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

package com.swirlds.platform.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.state.test.fixtures.ListReadableQueueState;
import com.swirlds.state.test.fixtures.ListWritableQueueState;
import com.swirlds.state.test.fixtures.StateTestBase;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class ReadableQueueStateBaseTest<E> extends StateTestBase {
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
        final var startingElements = new ArrayList<String>();
        subject.iterator().forEachRemaining(startingElements::add);

        // When we peek
        subject.peek();

        // None of the queue elements are removed
        final var endingElements = new ArrayList<String>();
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
