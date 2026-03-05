// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.model;

/**
 * Sealed interface for items that can be processed in the validation pipeline.
 *
 * <p>This interface enables the queue to accept both file-based data ({@link DiskDataItem})
 * and in-memory hash records ({@link MemoryHashItem}) while maintaining type safety.
 */
public sealed interface ValidationItem permits DiskDataItem, MemoryHashItem {

    /**
     * Checks if this item signals thread termination.
     *
     * @return true if this is a poison pill
     */
    boolean isPoisonPill();
}
