// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.analysis;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Set;

/**
 * A vertex in a wiring model.
 */
public interface ModelVertex extends Comparable<ModelVertex> {

    /**
     * Get the name of the vertex.
     *
     * @return the name
     */
    @NonNull
    String getName();

    /**
     * Get the type of task scheduler that corresponds to this vertex, or null if this vertex does not correspond to a
     * task scheduler.
     *
     * @return the type of task scheduler that corresponds to this vertex, or null if this vertex does not correspond to
     * a task scheduler
     */
    @NonNull
    TaskSchedulerType getType();

    /**
     * Get the hyperlink to the documentation for this vertex, or null if there is no documentation.
     *
     * @return the hyperlink to the documentation for this vertex, or null if there is no documentation
     */
    @Nullable
    String getHyperlink();

    /**
     * Get whether the insertion of this vertex may block until capacity is available.
     *
     * @return true if the insertion of this vertex may block until capacity is available
     */
    boolean isInsertionIsBlocking();

    /**
     * Get the outgoing edges of this vertex.
     *
     * @return the outgoing edges of this vertex
     */
    @NonNull
    Set<ModelEdge> getOutgoingEdges();

    /**
     * Get substituted inputs for this vertex.
     */
    @NonNull
    Set<String> getSubstitutedInputs();

    /**
     * Render this vertex in mermaid format. Used when generating a wiring diagram.
     *
     * @param sb           the string builder to render to
     * @param nameProvider provides short names for vertices
     * @param styleManager manages the styles of vertices
     */
    void render(
            @NonNull final StringBuilder sb,
            @NonNull final MermaidNameShortener nameProvider,
            @NonNull final MermaidStyleManager styleManager);

    /**
     * Sorts by name.
     */
    default int compareTo(@NonNull final ModelVertex that) {
        return this.getName().compareTo(that.getName());
    }

    /**
     * Set the depth of this vertex in the wiring diagram. Depth increases by 1 for every group that this vertex is
     * nested within.
     *
     * @param depth the depth of this vertex in the wiring diagram
     */
    void setDepth(int depth);
}
