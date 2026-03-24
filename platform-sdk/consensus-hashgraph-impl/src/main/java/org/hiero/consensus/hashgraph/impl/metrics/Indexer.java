package org.hiero.consensus.hashgraph.impl.metrics;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.hashgraph.impl.EventImpl;

public class Indexer {
    public static final long FIRST_INDEX = 1L;
    public static final long NO_INDEX = -1L;
    public static final long NO_DIFF = -1L;
    private long nextIndex = FIRST_INDEX;
    private EventImpl lastEventAdded = null;

    public void eventAdded(@NonNull final EventImpl event){
        event.setIndex(nextIndex);
        nextIndex++;
        lastEventAdded = event;
    }

    public long diffWithLatestEvent(@NonNull final EventImpl event){
        if (lastEventAdded == null) {
            return NO_DIFF;
        }
        return lastEventAdded.getIndex() - event.getIndex();
    }

    public @Nullable EventImpl getLastEventAdded() {
        return lastEventAdded;
    }
}
