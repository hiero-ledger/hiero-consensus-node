// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.analysis;

import static org.hiero.base.utility.NonCryptographicHashing.hash32;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

/**
 * A directed edge between to vertices.
 */
public class ModelEdge implements Comparable<ModelEdge> {

    private ModelVertex source;
    private ModelVertex destination;
    private final String label;
    private final boolean insertionIsBlocking;
    private final boolean manual;

    /**
     * Constructor.
     *
     * @param source              the source vertex
     * @param destination         the destination vertex
     * @param label               the label of the edge, if a label is not needed for an edge then holds the value ""
     * @param insertionIsBlocking true if the insertion of this edge may block until capacity is available
     * @param manual              true if this edge has been manually added to the diagram, false if this edge
     *                            represents something tracked by the wiring framework
     */
    public ModelEdge(
            @NonNull final ModelVertex source,
            @NonNull final ModelVertex destination,
            @NonNull final String label,
            final boolean insertionIsBlocking,
            final boolean manual) {

        this.source = Objects.requireNonNull(source);
        this.destination = Objects.requireNonNull(destination);
        this.label = Objects.requireNonNull(label);
        this.insertionIsBlocking = insertionIsBlocking;
        this.manual = manual;
    }

    /**
     * Get the source vertex.
     *
     * @return the source vertex
     */
    @NonNull
    public ModelVertex getSource() {
        return source;
    }

    /**
     * Set the source vertex.
     *
     * @param source the source vertex
     */
    public void setSource(@NonNull final StandardVertex source) {
        this.source = Objects.requireNonNull(source);
    }

    /**
     * Get the destination vertex.
     *
     * @return the destination vertex
     */
    @NonNull
    public ModelVertex getDestination() {
        return destination;
    }

    /**
     * Set the destination vertex.
     *
     * @param destination the destination vertex
     */
    public void setDestination(@NonNull final StandardVertex destination) {
        this.destination = Objects.requireNonNull(destination);
    }

    /**
     * Get the label of the edge.
     *
     * @return the label of the edge
     */
    @NonNull
    public String getLabel() {
        return label;
    }

    /**
     * Get whether or not the insertion of this edge may block until capacity is available.
     *
     * @return true if the insertion of this edge may block until capacity is available
     */
    public boolean isInsertionIsBlocking() {
        return insertionIsBlocking;
    }

    @Override
    public boolean equals(@Nullable final Object obj) {
        if (obj instanceof final ModelEdge that) {
            return this.source.equals(that.source)
                    && this.destination.equals(that.destination)
                    && this.label.equals(that.label);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return hash32(source.hashCode(), destination.hashCode(), label.hashCode());
    }

    /**
     * Useful for looking at a model in a debugger.
     */
    @Override
    public String toString() {
        return source + " --" + label + "-->" + (insertionIsBlocking ? "" : ">") + " " + destination;
    }

    /**
     * Sorts first by source, then by destination, then by label.
     */
    @Override
    public int compareTo(@NonNull final ModelEdge that) {
        if (!this.source.equals(that.source)) {
            return this.source.compareTo(that.source);
        }
        if (!this.destination.equals(that.destination)) {
            return this.destination.compareTo(that.destination);
        }
        return this.label.compareTo(that.label);
    }

    /**
     * Get the character for the outgoing end of this edge.
     */
    @NonNull
    private String getArrowCharacter() {
        if (manual) {
            return "o";
        } else {
            return ">";
        }
    }

    /**
     * Render this edge to a string builder.
     *
     * @param sb           the string builder to render to
     * @param nameProvider provides short names for vertices
     */
    public void render(@NonNull final StringBuilder sb, @NonNull final MermaidNameShortener nameProvider) {

        final String sourceName = nameProvider.getShortVertexName(source.getName());
        sb.append(sourceName).append(" ");

        if (insertionIsBlocking) {
            if (label.isEmpty()) {
                sb.append("--");
            } else {
                sb.append("-- \"").append(label).append("\" --");
            }
        } else {
            if (label.isEmpty()) {
                sb.append("-.-");
            } else {
                sb.append("-. \"").append(label).append("\" .-");
            }
        }

        sb.append(getArrowCharacter()).append(" ");

        final String destinationName = nameProvider.getShortVertexName(destination.getName());
        sb.append(destinationName).append("\n");
    }
}
