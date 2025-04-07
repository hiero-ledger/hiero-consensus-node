// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.orphan;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * An event that is missing one or more parents.
 *
 * @param orphan         the event that is missing one or more parents
 * @param missingParents the list of missing parents (ancient parents are not included)
 */
record OrphanedEvent(@NonNull PlatformEvent orphan, @NonNull List<EventDescriptorWrapper> missingParents) {}
