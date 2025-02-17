/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.spi.ids;

import com.hedera.node.app.hapi.utils.EntityType;

/**
 * Provides a way to update entity number counts.
 */
public interface WritableEntityCounters extends ReadableEntityCounters {
    /**
     * Decrements the entity type counter for the given entity type.
     * Since entity counters are used to determine the size of the state, when an entity is removed,
     * the counter must be decremented.
     * This method is called when a remove operation is performed on a store.
     *
     * @param entityType the type of entity for which to decrement the number
     */
    void decrementEntityTypeCounter(EntityType entityType);

    /**
     * Increments the entity type counter for the given entity type.
     * This is called when a new entity is created.
     *
     * @param entityType the type of entity for which to increment the number
     */
    void incrementEntityTypeCount(final EntityType entityType);

    /**
     * Adjusts the entity count for the given entity type by the given delta.
     * @param entityType the type of entity for which to adjust the count
     * @param delta the delta to adjust the count by
     */
    void adjustEntityCount(final EntityType entityType, final long delta);
}
