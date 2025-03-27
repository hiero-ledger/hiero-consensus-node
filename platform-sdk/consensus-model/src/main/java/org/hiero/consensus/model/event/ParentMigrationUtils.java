// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.event;

import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.event.GossipEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;

/**
 * Utility class for migrating parent data from {@link EventCore} to {@link GossipEvent}.
 */
public final class ParentMigrationUtils {
    private ParentMigrationUtils() {}

    /**
     * Checks if the parent data is populated correctly.
     *
     * @param event the event to check
     * @return true if the parent data is populated correctly, false otherwise
     */
    public static boolean areParentsPopulatedCorrectly(@NonNull final GossipEvent event) {
        Objects.requireNonNull(event);
        return event.parents().isEmpty() || event.eventCore().parents().isEmpty();
    }

    /**
     * Gets the parents of the given event.
     *
     * @param event the event to get the parents of
     * @return the parents of the given event
     */
    @NonNull
    public static List<EventDescriptor> getParents(@NonNull final GossipEvent event) {
        Objects.requireNonNull(event);
        return !event.parents().isEmpty() ? event.parents() : event.eventCore().parents();
    }
}
