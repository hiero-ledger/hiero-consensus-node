// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.Event;

/**
 * A consumer that will be called when a stale self event is detected
 */
@FunctionalInterface
public interface StaleEventConsumer {

    /**
     * Processes a stale event.
     *
     * @param event the stale event that needs to be processed
     */
    void processStaleEvent(@NonNull Event event);
}
