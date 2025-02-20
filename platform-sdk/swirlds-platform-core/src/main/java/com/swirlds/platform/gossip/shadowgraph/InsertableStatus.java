// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

/**
 * A status representing the ability of the {@link Shadowgraph} to insert an event.
 */
public enum InsertableStatus {
    /** The event can be inserted into the shadow graph. */
    INSERTABLE,
    /** The event cannot be inserted into the shadow graph because it is null. */
    NULL_EVENT,
    /** The event cannot be inserted into the shadow graph because it is already in the shadow graph. */
    DUPLICATE_SHADOW_EVENT,
    /** The event cannot be inserted into the shadow graph because it belongs to an expired generation. */
    EXPIRED_EVENT
}
