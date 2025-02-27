// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.analysis;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A utility for finding cyclical back pressure in a wiring model.
 */
public final class CycleFinder {

    private static final Logger logger = LogManager.getLogger(CycleFinder.class);

    private CycleFinder() {}

    /**
     * Check for cyclical back pressure in a wiring model.
     *
     * @param vertices the vertices in the wiring model
     * @return true if there is cyclical backpressure
     */
    public static boolean checkForCyclicalBackPressure(@NonNull final Collection<ModelVertex> vertices) {
        for (final ModelVertex vertex : vertices) {
            if (checkForCycleStartingFromVertex(vertex)) {
                return true;
            }
        }
        logger.info(STARTUP.getMarker(), "No cyclical back pressure detected in wiring model.");
        return false;
    }

    /**
     * Check for a cycle starting from a vertex.
     *
     * @param start the vertex to start from
     * @return true if there is a cycle
     */
    private static boolean checkForCycleStartingFromVertex(@NonNull final ModelVertex start) {

        // Perform a depth first traversal of the graph starting from the given vertex.
        // Ignore any edge that doesn't apply back pressure.

        final Deque<ModelVertex> stack = new LinkedList<>();
        stack.addLast(start);

        final Set<ModelVertex> visited = new HashSet<>();

        // Track the parent of each vertex. Useful for tracing the cycle after it's detected.
        final Map<ModelVertex, ModelVertex> parents = new HashMap<>();

        while (!stack.isEmpty()) {

            final ModelVertex parent = stack.removeLast();

            for (final ModelEdge childEdge : parent.getOutgoingEdges()) {
                if (!childEdge.isInsertionIsBlocking()) {
                    // Ignore non-blocking edges.
                    continue;
                }

                final ModelVertex child = childEdge.getDestination();

                if (child.equals(start)) {
                    // We've found a cycle!
                    parents.put(child, parent);
                    logCycle(start, parents);
                    return true;
                }

                if (visited.add(child)) {
                    stack.addLast(child);
                    parents.put(child, parent);
                }
            }
        }
        return false;
    }

    /**
     * Logs a warning message when cyclical back pressure is detected. Is intended to fail standard test validators.
     *
     * @param start   the vertex where the cycle was detected
     * @param parents records the parents for the traversal, used to trace the cycle
     */
    private static void logCycle(
            @NonNull final ModelVertex start, @NonNull final Map<ModelVertex, ModelVertex> parents) {

        final StringBuilder sb = new StringBuilder();
        sb.append("Cyclical back pressure detected in wiring model. Cycle: ");

        // Following parent links will walk the cycle in reverse order.
        final List<ModelVertex> path = new ArrayList<>();
        path.add(start);
        ModelVertex target = start;

        while (!target.equals(start) || path.size() == 1) {
            target = parents.get(target);
            path.add(target);
        }

        for (int i = path.size() - 1; i >= 0; i--) {
            sb.append(path.get(i).getName());
            if (i > 0) {
                sb.append(" -> ");
            }
        }

        logger.error(EXCEPTION.getMarker(), sb.toString());
    }
}
