package com.swirlds.platform.consensus;

import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.EventDescriptorWrapper;

/**
 * Local consensus generation (cGen) is computed by the consensus algorithm and used to break ties on the ordering of
 * events that reach consensus during a round. The value of cGen is temporary
 */
public class LocalConsensusGeneration {
    private static final int GENERATION_UNDEFINED = 0;
    private static final int FIRST_GENERATION = 1;
    private static final Consumer<EventImpl> CLEAR_CGEN = e -> e.setCGen(GENERATION_UNDEFINED);

    public static void assignCGen(@NonNull final List<EventImpl> events) {
        events.sort(Comparator.comparingLong(e -> e.getBaseEvent().getNGen()));

        final Map<EventDescriptorWrapper, EventImpl> parentMap = new HashMap<>();
        for (final EventImpl event : events) {
            final int maxParentGen = event.getBaseEvent().getAllParents()
                    .stream()
                    .map(parentMap::get)
                    .mapToInt(LocalConsensusGeneration::getGeneration)
                    .max()
                    .orElse(GENERATION_UNDEFINED);
            event.setCGen(maxParentGen + 1);
            parentMap.put(event.getBaseEvent().getDescriptor(), event);
        }
    }

    public static void clearCGen(@NonNull final List<EventImpl> events) {
        events.forEach(CLEAR_CGEN);
    }

    private static int getGeneration(@Nullable final EventImpl event) {
        return event == null ? GENERATION_UNDEFINED : event.getCGen();
    }
}
