// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import org.hiero.consensus.model.event.PlatformEvent;

/**
 * A functional interface for receiving events from the simulated network.
 */
@FunctionalInterface
public interface EventReceiver {
    /**
     * Receive an event from the simulated network.
     *
     * @param event the event to receive
     * @return true if the event was successfully received, false otherwise
     */
    boolean receiveEvent(PlatformEvent event);
}
