// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.events;

import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Performs special migration on events during the birth round migration pathway.
 */
@FunctionalInterface
public interface BirthRoundMigrationShim {
    /**
     * Migrate an event's birth round, if needed.
     *
     * @param event the event to migrate
     * @return the migrated event
     */
    @InputWireLabel("PlatformEvent")
    @NonNull
    PlatformEvent migrateEvent(@NonNull PlatformEvent event);
}
