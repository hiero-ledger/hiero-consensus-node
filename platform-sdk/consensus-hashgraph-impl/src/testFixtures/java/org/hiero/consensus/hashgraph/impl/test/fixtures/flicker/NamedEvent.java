// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * One built event, paired with the name its fixture knows it by.
 * <p>
 * This is what a graph hands to {@link FlickerIntake#add(String, PlatformEvent)}. The name is local to one fixture and
 * means nothing outside it; it exists so assertions and failure messages can say {@code b4} rather than quote a hash.
 *
 * @param name  the fixture's name for the event
 * @param event the built event
 */
public record NamedEvent(@NonNull String name, @NonNull PlatformEvent event) {}
