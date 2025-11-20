package com.swirlds.platform.test.fixtures.graph;

import com.swirlds.platform.event.linking.ConsensusLinker;
import com.swirlds.platform.event.linking.NoOpLinkerLogsAndMetrics;
import com.swirlds.platform.internal.EventImpl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.junit.jupiter.api.Assertions;

public class SimpleGraph {
    private final List<PlatformEvent> events;
    private List<EventImpl> eventImpls;

    public SimpleGraph(final List<PlatformEvent> events) {
        this.events = events;
    }

    public void linkEvents(){
        if(eventImpls != null){
            throw new IllegalStateException("Events have already been linked.");
        }
        eventImpls = new ArrayList<>();
        final ConsensusLinker consensusLinker = new ConsensusLinker(NoOpLinkerLogsAndMetrics.getInstance());
        for (final PlatformEvent event : events) {
            final EventImpl linkedEvent = consensusLinker.linkEvent(event);
            Assertions.assertNotNull(linkedEvent);
            eventImpls.add(linkedEvent);
        }
        eventImpls = Collections.unmodifiableList(eventImpls);
    }

    public List<EventImpl> getImpls() {
        if (eventImpls == null) {
            throw new IllegalStateException("Events have not been linked yet. Call linkEvents() first.");
        }
        return eventImpls;
    }

    public List<EventImpl> getImpls(final int ... indices) {
        if (eventImpls == null) {
            throw new IllegalStateException("Events have not been linked yet. Call linkEvents() first.");
        }
        final List<EventImpl> selectedEvents = new ArrayList<>();
        for (final int index : indices) {
            selectedEvents.add(eventImpls.get(index));
        }
        return Collections.unmodifiableList(selectedEvents);

    }
}
