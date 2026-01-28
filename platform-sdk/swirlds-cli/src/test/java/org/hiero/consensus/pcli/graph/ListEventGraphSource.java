// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.graph;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * An {@link EventGraphSource} backed by a list of events.
 */
public class ListEventGraphSource implements EventGraphSource {

    private final Iterator<PlatformEvent> eventsIterator;

    /**
     * Creates a source that loads events from the given supplier.
     *
     * @param eventSupplier provides the list of events to iterate over
     */
    public ListEventGraphSource(@NonNull final Supplier<List<PlatformEvent>> eventSupplier) {
        this.eventsIterator = eventSupplier.get().iterator();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public PlatformEvent next() {
        return eventsIterator.next();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() {
        return eventsIterator.hasNext();
    }
}
