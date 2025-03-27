// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.event;

import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.event.GossipEvent;
import java.util.List;

public final class ParentMigrationUtils {
    private ParentMigrationUtils() {}

    public static boolean areParentsPopulatedCorrectly(final GossipEvent event) {
        return event.parents().isEmpty() || event.eventCore().parents().isEmpty();
    }

    public static List<EventDescriptor> getParents(final GossipEvent event) {
        return !event.parents().isEmpty() ? event.parents() : event.eventCore().parents();
    }
}
