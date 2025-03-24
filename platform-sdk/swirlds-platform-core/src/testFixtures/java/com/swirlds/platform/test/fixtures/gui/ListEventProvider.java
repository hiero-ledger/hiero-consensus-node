// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.gui;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Provides events for the GUI by using a list of events
 */
public class ListEventProvider implements GuiEventProvider {
    private final List<PlatformEvent> events;
    private int index;

    /**
     * Constructor
     *
     * @param events the list of events
     */
    public ListEventProvider(@NonNull final List<PlatformEvent> events) {
        Objects.requireNonNull(events);
        this.events = events;
        this.index = 0;
    }

    @Override
    public @NonNull List<PlatformEvent> provideEvents(final int numberOfEvents) {
        if (index >= events.size()) {
            return List.of();
        }
        final int toIndex = Math.min(index + numberOfEvents, events.size());
        final List<PlatformEvent> list = events.subList(index, toIndex);
        index = toIndex;
        return list;
    }

    @Override
    public void reset() {
        index = 0;
    }
}
