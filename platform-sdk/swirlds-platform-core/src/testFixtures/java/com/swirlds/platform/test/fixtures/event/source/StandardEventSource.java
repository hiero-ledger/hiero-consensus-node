// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.source;

import com.swirlds.common.test.fixtures.TransactionGenerator;
import com.swirlds.platform.internal.EventImpl;
import java.util.LinkedList;
import java.util.Random;

/**
 * An event source that simulates a standard, honest node.
 */
public class StandardEventSource extends AbstractEventSource {

    private LinkedList<EventImpl> latestEvents;

    public StandardEventSource(final boolean useFakeHashes) {
        this(useFakeHashes, DEFAULT_TRANSACTION_GENERATOR);
    }

    public StandardEventSource(final boolean useFakeHashes, final TransactionGenerator transactionGenerator) {
        super(useFakeHashes, transactionGenerator);
        latestEvents = new LinkedList<>();
    }

    public StandardEventSource() {
        this(true);
    }

    private StandardEventSource(final StandardEventSource that) {
        super(that);
        latestEvents = new LinkedList<>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StandardEventSource copy() {
        return new StandardEventSource(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() {
        super.reset();
        latestEvents = new LinkedList<>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventImpl getRecentEvent(final Random random, final int index) {
        if (latestEvents.size() == 0) {
            return null;
        }

        if (index >= latestEvents.size()) {
            return latestEvents.getLast();
        }

        return latestEvents.get(index);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setLatestEvent(final Random random, final EventImpl event) {
        latestEvents.addFirst(event);
        pruneEventList(latestEvents);
    }
}
