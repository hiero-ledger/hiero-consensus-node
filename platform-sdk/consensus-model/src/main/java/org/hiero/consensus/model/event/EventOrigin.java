// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.event;

/**
 * The origin of an event. Signifies how the event entered the system.
 */
public enum EventOrigin {
    /** The event was received through gossip. */
    GOSSIP,
    /** The event was read from trusted storage, such as a local disk. */
    TRUSTED_STORAGE,
    /**
     * The event was created by this runtime. This is not the same as an event created by a local node. Since an event
     * can be created, then written to disk or gossipped, then the process can be restarted and the event received
     * again.
     */
    RUNTIME_CREATED
}
