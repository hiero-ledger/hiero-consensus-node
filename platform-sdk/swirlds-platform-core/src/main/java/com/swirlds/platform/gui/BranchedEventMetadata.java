// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui;

/**
 * Metadata for an event that is part of a branched event.
 * <p>
 * This metadata contains the index of the branch and the generation of the event.
 */
public record BranchedEventMetadata(Integer branchIndex, Long generation) {

    /**
     * Get the index of the branch that the linked event is on.
     */
    public Integer getBranchIndex() {
        return branchIndex;
    }

    /**
     * Get the generation of the event linked to this metadata.
     */
    public Long getGeneration() {
        return generation;
    }
}
