// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.analysis;

import static com.swirlds.component.framework.model.internal.analysis.WiringFlowchart.GROUP_COLOR;
import static com.swirlds.component.framework.model.internal.analysis.WiringFlowchart.TEXT_COLOR;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A vertex that represents a nexted group of vertices.
 */
public class GroupVertex implements ModelVertex {

    /**
     * The name of the vertex.
     */
    private final String name;

    /**
     * The outgoing edges of this vertex.
     */
    private final Set<ModelEdge> outgoingEdges = new HashSet<>();

    /**
     * Vertices that are contained within this group.
     */
    private final List<ModelVertex> subVertices;

    private int depth;
    private final Set<String> substitutedInputs = new HashSet<>();

    public GroupVertex(@NonNull final String name, @NonNull final List<ModelVertex> subVertices) {

        this.name = Objects.requireNonNull(name);
        this.subVertices = Objects.requireNonNull(subVertices);

        for (final ModelVertex vertex : subVertices) {
            vertex.setDepth(1);
        }
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getName() {
        return name;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public TaskSchedulerType getType() {
        return TaskSchedulerType.DIRECT;
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public String getHyperlink() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isInsertionIsBlocking() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Set<ModelEdge> getOutgoingEdges() {
        return outgoingEdges;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Set<String> getSubstitutedInputs() {
        return substitutedInputs;
    }

    /**
     * Get the vertices that are contained within this group.
     *
     * @return the vertices that are contained within this group
     */
    public List<ModelVertex> getSubVertices() {
        return subVertices;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDepth(final int depth) {
        this.depth = depth;
        for (final ModelVertex vertex : subVertices) {
            vertex.setDepth(depth + 1);
        }
    }

    /**
     * Generate the style for this vertex.
     *
     * @return the style for this vertex
     */
    @NonNull
    private String generateStyle() {
        final int baseRedValue = Integer.parseInt(String.valueOf(GROUP_COLOR.charAt(0)), 16);
        final int baseGreenValue = Integer.parseInt(String.valueOf(GROUP_COLOR.charAt(1)), 16);
        final int baseBlueValue = Integer.parseInt(String.valueOf(GROUP_COLOR.charAt(2)), 16);

        final int redValue = Math.min(0xF, baseRedValue + depth * 2);
        final int greenValue = Math.min(0xF, baseGreenValue + depth * 2);
        final int blueValue = Math.min(0xF, baseBlueValue + depth * 2);

        final String color = String.format("%X%X%X", redValue, greenValue, blueValue);

        return "fill:#" + color + ",stroke:#" + TEXT_COLOR + ",stroke-width:2px";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void render(
            @NonNull final StringBuilder sb,
            @NonNull final MermaidNameShortener nameProvider,
            @NonNull final MermaidStyleManager styleManager) {
        final String shortName = nameProvider.getShortVertexName(name);
        styleManager.registerStyle(shortName, generateStyle());

        sb.append("subgraph ").append(shortName).append("[\"").append(name).append("\"]\n");
        subVertices.stream().sorted().forEachOrdered(vertex -> vertex.render(sb, nameProvider, styleManager));
        sb.append("end\n");
    }
}
