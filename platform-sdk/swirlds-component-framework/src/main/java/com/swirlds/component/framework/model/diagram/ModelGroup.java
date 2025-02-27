// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.diagram;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Describes a group of components that should be visualized together in a wiring diagram. Specified via strings since
 * this configuration is presumably read from the command line.
 *
 * @param name     the name of the group
 * @param elements the set of subcomponents in the group
 * @param collapse true if the group should be collapsed into a single box
 */
public record ModelGroup(@NonNull String name, @NonNull Set<String> elements, boolean collapse)
        implements Comparable<ModelGroup> {

    /**
     * Sorts groups by name.
     */
    @Override
    public int compareTo(@NonNull final ModelGroup that) {
        return name.compareTo(that.name);
    }
}
