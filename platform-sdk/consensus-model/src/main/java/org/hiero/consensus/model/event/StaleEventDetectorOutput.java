// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.event;

/**
 * Describes the different types of output that the stale event detector produces.
 */
public enum StaleEventDetectorOutput {
    /**
     * A self event that was just created. Type is {@link PlatformEvent}.
     */
    SELF_EVENT,
    /**
     * A self event that has gone stale. Type is {@link PlatformEvent}.
     */
    STALE_SELF_EVENT
}
