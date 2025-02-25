// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.orphan;

import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.system.events.EventDescriptorWrapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * An event that is missing one or more parents.
 *
 * @param orphan         the event that is missing one or more parents
 * @param missingParents the list of missing parents (ancient parents are not included)
 */
record OrphanedEvent(@NonNull PlatformEvent orphan, @NonNull List<EventDescriptorWrapper> missingParents) {}
