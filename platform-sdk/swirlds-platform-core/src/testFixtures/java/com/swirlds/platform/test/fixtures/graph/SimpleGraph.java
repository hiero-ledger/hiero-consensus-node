package com.swirlds.platform.test.fixtures.graph;

import com.swirlds.platform.event.linking.ConsensusLinker;
import com.swirlds.platform.event.linking.NoOpLinkerLogsAndMetrics;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.junit.jupiter.api.Assertions;

public class SimpleGraph {
    private final List<PlatformEvent> events;
    private final List<EventImpl> eventImpls;

    public SimpleGraph(final List<PlatformEvent> events) {
        this.events = events;
        final List<EventImpl> eventImpls = new ArrayList<>();
        final ConsensusLinker consensusLinker = new ConsensusLinker(NoOpLinkerLogsAndMetrics.getInstance());
        for (final PlatformEvent event : events) {
            final EventImpl linkedEvent = consensusLinker.linkEvent(event);
            Assertions.assertNotNull(linkedEvent);
            if(event.getConsensusTimestamp() != null){
                linkedEvent.setConsensus(true);
            }
            eventImpls.add(linkedEvent);
        }
        this.eventImpls = Collections.unmodifiableList(eventImpls);
    }

    public @NonNull List<EventImpl> getImpls() {
        return eventImpls;
    }

    public @NonNull List<EventImpl> getImpls(final int ... indices) {
        final List<EventImpl> selectedEvents = new ArrayList<>();
        for (final int index : indices) {
            selectedEvents.add(eventImpls.get(index));
        }
        return Collections.unmodifiableList(selectedEvents);

    }

    public @NonNull EventImpl getImpl(final int index) {
        return eventImpls.get(index);
    }
}
