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

package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.Predicate;

/**
 * A writable queue of elements.
 *
 * @param <E> The type of element held in the queue.
 */
public interface WritableQueueState<E> extends ReadableQueueState<E> {

    /**
     * Adds the given element to the end of the queue.
     *
     * @param element The element to add.
     */
    void add(@NonNull E element);

    /**
     * Retrieves and removes the element at the head of the queue, or returns null if the queue is empty.
     *
     * @return The element at the head of the queue, or null if the queue is empty.
     */
    @Nullable
    default E poll() {
        return removeIf(e -> true);
    }

    /**
     * Retrieves and removes the element at the head of the queue if, and only if, the predicate returns true when
     * executed with the head element.
     * @param predicate A function that returns true if the supplied element meets the criteria to be removed
     * @return The head of the queue, or null if the queue is empty or the predicate returns false.
     */
    @Nullable
    E removeIf(@NonNull Predicate<E> predicate);
}
