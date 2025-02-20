// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.analysis;

import static com.swirlds.component.framework.model.internal.analysis.ModelVertexMetaType.SCHEDULER;
import static com.swirlds.component.framework.model.internal.analysis.ModelVertexMetaType.SUBSTITUTION;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.CONCURRENT;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.DIRECT;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.DIRECT_THREADSAFE;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.SEQUENTIAL;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.SEQUENTIAL_THREAD;

import com.swirlds.component.framework.model.diagram.ModelEdgeSubstitution;
import com.swirlds.component.framework.model.diagram.ModelGroup;
import com.swirlds.component.framework.model.diagram.ModelManualLink;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A readable wiring flowchart.
 */
public class WiringFlowchart {

    public static final String SCHEDULER_COLOR = "135f12";
    public static final String DIRECT_SCHEDULER_COLOR = "12305f";
    public static final String TEXT_COLOR = "000";
    public static final String GROUP_COLOR = "9cf";
    public static final String SUBSTITUTION_COLOR = "5f1212";

    private final MermaidNameShortener nameProvider = new MermaidNameShortener();
    private final MermaidStyleManager styleManager = new MermaidStyleManager();

    /**
     * A map from vertex name to vertex.
     */
    private final Map<String, ModelVertex> vertexMap;

    /**
     * Draws a mermaid flowchart from the given wiring model.
     *
     * @param modelVertexMap a map from vertex name to vertex
     * @param substitutions  a list of edge substitutions to perform
     * @param groups         a list of groups to create
     * @param manualLinks    a list of manual links to draw
     */
    public WiringFlowchart(
            @NonNull final Map<String, ModelVertex> modelVertexMap,
            @NonNull final List<ModelEdgeSubstitution> substitutions,
            @NonNull final List<ModelGroup> groups,
            @NonNull final List<ModelManualLink> manualLinks) {

        Objects.requireNonNull(modelVertexMap);

        vertexMap = copyVertexMap(modelVertexMap);
        addManualLinks(manualLinks);
        substituteEdges(substitutions);
        handleGroups(groups);
    }

    /**
     * Do a deep copy of the vertex map. Allows the local copy to be modified without affecting the original.
     *
     * @return a deep copy of the vertex map
     */
    @NonNull
    private Map<String, ModelVertex> copyVertexMap(@NonNull final Map<String, ModelVertex> original) {
        final Map<String, ModelVertex> copy = new HashMap<>();

        // First, copy the vertices without copying the edges.
        // We should only encounter StandardVertex instances here.
        for (final ModelVertex vertex : original.values()) {
            if (!(vertex instanceof StandardVertex)) {
                throw new IllegalStateException("Encountered a vertex that is not a StandardVertex");
            }
            final StandardVertex vertexCopy = new StandardVertex(
                    vertex.getName(),
                    vertex.getType(),
                    SCHEDULER,
                    vertex.getHyperlink(),
                    vertex.isInsertionIsBlocking());

            copy.put(vertex.getName(), vertexCopy);
        }

        // Next, copy the edges.
        for (final ModelVertex vertex : original.values()) {
            for (final ModelEdge edge : vertex.getOutgoingEdges()) {

                final ModelVertex source = copy.get(edge.getSource().getName());
                final ModelVertex destination = copy.get(edge.getDestination().getName());

                final ModelEdge edgeCopy =
                        new ModelEdge(source, destination, edge.getLabel(), edge.isInsertionIsBlocking(), false);

                source.getOutgoingEdges().add(edgeCopy);
            }
        }

        return copy;
    }

    /**
     * Add manual links to the flowchart.
     *
     * @param manualLinks the manual links to add
     */
    private void addManualLinks(@NonNull final List<ModelManualLink> manualLinks) {
        for (final ModelManualLink link : manualLinks) {
            final ModelVertex source = vertexMap.get(link.source());
            final ModelVertex destination = vertexMap.get(link.target());

            if (source == null) {
                throw new IllegalStateException("Source vertex " + link.source() + " does not exist.");
            }

            if (destination == null) {
                throw new IllegalStateException("Destination vertex " + link.target() + " does not exist.");
            }

            final ModelEdge edge = new ModelEdge(source, destination, link.label(), false, true);
            source.getOutgoingEdges().add(edge);
        }
    }

    /**
     * Find all edges that need to be substituted and perform the substitution.
     */
    private void substituteEdges(@NonNull final List<ModelEdgeSubstitution> substitutions) {
        for (final ModelEdgeSubstitution substitution : substitutions) {
            substituteEdge(substitution);
        }
    }

    /**
     * Perform a single edge substitution.
     */
    private void substituteEdge(@NonNull final ModelEdgeSubstitution substitution) {
        // First, create a new vertex that will represent the destination of the substitution.
        final StandardVertex substitutedVertex =
                new StandardVertex(substitution.substitution(), DIRECT, SUBSTITUTION, null, true);
        vertexMap.put(substitution.substitution(), substitutedVertex);

        // Next, cause all substituted edges to point to this new vertex.
        for (final ModelVertex vertex : vertexMap.values()) {
            if (!substitution.source().equals(vertex.getName())) {
                // Only replace edges with the given source.
                continue;
            }

            final HashSet<ModelEdge> uniqueEdges = new HashSet<>();

            for (final ModelEdge edge : vertex.getOutgoingEdges()) {
                if (!substitution.edge().equals(edge.getLabel())) {
                    // Only replace destinations for edges with the given label.
                    uniqueEdges.add(edge);
                    continue;
                }

                edge.getDestination().getSubstitutedInputs().add(substitution.substitution());
                edge.setDestination(substitutedVertex);
                uniqueEdges.add(edge);
            }

            vertex.getOutgoingEdges().clear();
            vertex.getOutgoingEdges().addAll(uniqueEdges);
        }
    }

    /**
     * Handle groups in the order provided.
     */
    private void handleGroups(@NonNull final List<ModelGroup> groups) {
        for (final ModelGroup group : groups) {
            final GroupVertex groupVertex = createGroup(group);
            if (group.collapse()) {
                collapseGroup(groupVertex);
            }
        }
    }

    /**
     * Collect all vertices that are contained within the given group and create a new vertex that represents the
     * group.
     *
     * @param group the group to create a vertex for
     * @return the new vertex
     */
    private GroupVertex createGroup(@NonNull final ModelGroup group) {
        // Collect all vertices that are contained within the group.
        final List<ModelVertex> subVertices = new ArrayList<>();

        for (final String vertexName : group.elements()) {
            final ModelVertex subVertex = vertexMap.get(vertexName);
            if (subVertex == null) {
                throw new IllegalStateException("Vertex " + vertexName
                        + " is not in the vertex map. Can not insert into group " + group.name() + ".");
            }

            subVertices.add(subVertex);
        }

        // Remove those vertices from the vertex map.
        for (final ModelVertex subVertex : subVertices) {
            vertexMap.remove(subVertex.getName());
        }

        // Create a new vertex that represents the group.
        final GroupVertex groupVertex = new GroupVertex(group.name(), subVertices);
        vertexMap.put(group.name(), groupVertex);

        return groupVertex;
    }

    /**
     * Collapse a group of vertices into a single vertex.
     *
     * @param group the group to collapse
     */
    private void collapseGroup(@NonNull final GroupVertex group) {
        final List<ModelEdge> edges = collectEdges();
        final List<ModelVertex> groupVertices = collectGroupVertices(group);

        final Set<String> hyperlinks = new HashSet<>();
        for (final ModelVertex vertex : groupVertices) {
            if (vertex.getHyperlink() != null) {
                hyperlinks.add(vertex.getHyperlink());
            }
        }
        final String hyperlink = hyperlinks.size() == 1 ? hyperlinks.iterator().next() : null;

        final TaskSchedulerType schedulerType = getSchedulerTypeOfCollapsedGroup(groupVertices);

        final StandardVertex newVertex = new StandardVertex(group.getName(), schedulerType, SCHEDULER, hyperlink, true);

        // Assign all vertices with a source that is collapsed to the new vertex.
        // Redirect all vertices with a destination that is collapsed to the new vertex.
        for (final ModelEdge edge : edges) {
            final boolean collapsedSource = groupVertices.contains(edge.getSource());
            final boolean collapsedDestination = groupVertices.contains(edge.getDestination());

            if (collapsedSource && collapsedDestination) {
                // If the source and or destination are collapsed, then the edge is removed.
                continue;
            }

            if (collapsedSource) {
                edge.setSource(newVertex);
                newVertex.getOutgoingEdges().add(edge);
            }

            if (collapsedDestination) {
                // Add and remove from set to avoid possible duplicates.
                edge.getSource().getOutgoingEdges().remove(edge);
                edge.setDestination(newVertex);
                edge.getSource().getOutgoingEdges().add(edge);
            }
        }

        // Extract substitutions from collapsed vertices.
        for (final ModelVertex vertex : groupVertices) {
            for (final String input : vertex.getSubstitutedInputs()) {
                newVertex.getSubstitutedInputs().add(input);
            }
        }

        // Remove old vertices from the vertex map.
        for (final ModelVertex vertex : groupVertices) {
            vertexMap.remove(vertex.getName());
        }

        // Finally, add the new vertex to the vertex map.
        vertexMap.put(newVertex.getName(), newVertex);
    }

    /**
     * When collapsing a group, determine the type of task scheduler type that should be displayed.
     */
    @NonNull
    private TaskSchedulerType getSchedulerTypeOfCollapsedGroup(@NonNull final List<ModelVertex> groupVertices) {

        boolean hasSequential = false;
        boolean hasState = false;

        for (final ModelVertex vertex : groupVertices) {
            if (vertex.getType() == CONCURRENT) {
                return CONCURRENT;
            }

            if (vertex.getType() == SEQUENTIAL || vertex.getType() == SEQUENTIAL_THREAD) {
                if (hasSequential) {
                    // We've detected more than one sequential scheduler type, so there is more than one logical
                    // thread of execution within this group.
                    return CONCURRENT;
                }
                hasSequential = true;
            }

            if (vertex.getType() == DIRECT) {
                hasState = true;
            }
        }

        if (hasSequential) {
            return SEQUENTIAL;
        } else {
            if (hasState) {
                return DIRECT;
            }
            return DIRECT_THREADSAFE;
        }
    }

    /**
     * Get all edges in the flowchart.
     *
     * @return all edges in the flowchart, sorted
     */
    private List<ModelVertex> collectGroupVertices(@NonNull final GroupVertex group) {
        final List<ModelVertex> vertices = new ArrayList<>();
        final LinkedList<ModelVertex> stack = new LinkedList<>();
        stack.addLast(group);

        while (!stack.isEmpty()) {
            final ModelVertex vertex = stack.removeLast();
            vertices.add(vertex);
            if (vertex instanceof final GroupVertex groupVertex) {
                for (final ModelVertex subVertex : groupVertex.getSubVertices()) {
                    stack.addLast(subVertex);
                }
            }
        }

        Collections.sort(vertices);
        return vertices;
    }

    /**
     * Get all edges in the flowchart.
     *
     * @return all edges in the flowchart, sorted
     */
    private List<ModelEdge> collectEdges() {
        final List<ModelEdge> edges = new ArrayList<>();
        final LinkedList<ModelVertex> stack = new LinkedList<>();

        for (final ModelVertex vertex : vertexMap.values()) {
            stack.addLast(vertex);
        }

        while (!stack.isEmpty()) {
            final ModelVertex vertex = stack.removeLast();
            edges.addAll(vertex.getOutgoingEdges());
            if (vertex instanceof final GroupVertex groupVertex) {
                for (final ModelVertex subVertex : groupVertex.getSubVertices()) {
                    stack.addLast(subVertex);
                }
            }
        }

        Collections.sort(edges);
        return edges;
    }

    /**
     * Render the flowchart to a string.
     *
     * @return the rendered flowchart
     */
    @NonNull
    public String render() {
        final StringBuilder sb = new StringBuilder();
        sb.append(
                """
                %%{
                    init: {
                        'flowchart': {'defaultRenderer': 'elk'},
                        'theme': 'base',
                        'themeVariables': {
                            'primaryColor': '#454545',
                            'primaryTextColor': '#EEEEEE',
                            'lineColor': '#C0C0C0'
                        }
                    }
                }%%
                flowchart TD
                """);

        final List<ModelVertex> sortedVertices =
                vertexMap.values().stream().sorted().toList();
        for (final ModelVertex vertex : sortedVertices) {
            vertex.render(sb, nameProvider, styleManager);
        }

        for (final ModelEdge edge : collectEdges()) {
            edge.render(sb, nameProvider);
        }

        styleManager.render(sb);

        return sb.toString();
    }
}
