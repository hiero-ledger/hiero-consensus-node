// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.gui;

import com.swirlds.platform.event.PlatformEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Interface for classes that provide events for the GUI
 */
public interface GuiEventProvider {
    /**
     * Provide a list of events
     *
     * @param numberOfEvents the number of events to provide
     * @return the list of events
     */
    @NonNull
    List<PlatformEvent> provideEvents(final int numberOfEvents);

    /**
     * Reset the provider
     */
    void reset();
}
