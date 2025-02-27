// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.analysis;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A utility for checking direct scheduler use.
 */
public final class DirectSchedulerChecks {

    private static final Logger logger = LogManager.getLogger(DirectSchedulerChecks.class);

    private DirectSchedulerChecks() {}

    /**
     * Check for illegal direct scheduler use. Rules are as follows:
     *
     * <ul>
     * <li>
     * Calling into a component with type {@link TaskSchedulerType#DIRECT DIRECT}
     * from a component with {@link TaskSchedulerType#CONCURRENT CONCURRENT} is not
     * allowed.
     * </li>
     * <li>
     * Calling into a component with type {@link TaskSchedulerType#DIRECT DIRECT}
     * from more than one component with type
     * {@link TaskSchedulerType#SEQUENTIAL SEQUENTIAL} or type
     * {@link TaskSchedulerType#SEQUENTIAL_THREAD SEQUENTIAL_THREAD} is not allowed.
     * </li>
     * <li>
     * Calling into a component A with type
     * {@link TaskSchedulerType#DIRECT DIRECT} from component B with type
     * {@link TaskSchedulerType#DIRECT DIRECT} or type
     * {@link TaskSchedulerType#DIRECT_THREADSAFE DIRECT_THREADSAFE} counts as a call
     * into B from all components calling into component A.
     * </li>
     * </ul>
     *
     * @param vertices the vertices in the wiring model
     * @return true if there is illegal direct scheduler use
     */
    public static boolean checkForIllegalDirectSchedulerUse(@NonNull final Collection<ModelVertex> vertices) {

        boolean illegalAccessDetected = false;

        // Note: this is only logged if we detect a problem.
        final StringBuilder sb = new StringBuilder("Illegal direct scheduler use detected:\n");

        // A map from each direct vertex to a set of non-direct schedulers that call into it.
        // If access is legal, then each of these sets should contain at most one element.
        final Map<ModelVertex, Set<ModelVertex>> directVertexCallers = new HashMap<>();

        for (final ModelVertex vertex : vertices) {
            final TaskSchedulerType vertexType = vertex.getType();

            if (vertexType == TaskSchedulerType.DIRECT || vertexType == TaskSchedulerType.DIRECT_THREADSAFE) {
                // we can ignore direct schedulers at this phase. We care about calls INTO direct schedulers,
                // not calls OUT OF direct schedulers.
                continue;
            }

            final Set<ModelVertex> directSchedulersAccessed = collectDirectVerticesAccessedByScheduler(vertex);

            if (vertexType == TaskSchedulerType.CONCURRENT && !directSchedulersAccessed.isEmpty()) {
                // It is illegal for a concurrent scheduler to call into a direct scheduler.
                illegalAccessDetected = true;
                sb.append("  ")
                        .append(vertex.getName())
                        .append(" is a concurrent scheduler that calls into direct scheduler(s):\n");
                for (final ModelVertex directScheduler : directSchedulersAccessed) {
                    sb.append("    - ").append(directScheduler.getName()).append("\n");
                }
            }

            for (final ModelVertex directScheduler : directSchedulersAccessed) {
                directVertexCallers
                        .computeIfAbsent(directScheduler, k -> new HashSet<>())
                        .add(vertex);
            }
        }

        // Now, check to see if any direct schedulers are called into by more than one non-direct scheduler.
        for (final Map.Entry<ModelVertex, Set<ModelVertex>> entry : directVertexCallers.entrySet()) {
            final ModelVertex directScheduler = entry.getKey();
            final Set<ModelVertex> callers = entry.getValue();

            if (callers.size() > 1) {
                illegalAccessDetected = true;
                sb.append("  ")
                        .append(directScheduler.getName())
                        .append(" is called into by more than one non-direct scheduler:\n");
                for (final ModelVertex caller : callers) {
                    sb.append("    - ").append(caller.getName()).append("\n");
                }
            }
        }

        if (illegalAccessDetected) {
            logger.error(EXCEPTION.getMarker(), sb.toString());
        } else {
            logger.info(STARTUP.getMarker(), "No illegal direct scheduler use detected in the wiring model.");
        }

        return illegalAccessDetected;
    }

    /**
     * Collect all direct vertices that are accessed by a scheduler.
     *
     * @param scheduler the scheduler to check
     * @return the set of direct vertices accessed by the scheduler
     */
    @NonNull
    private static Set<ModelVertex> collectDirectVerticesAccessedByScheduler(@NonNull final ModelVertex scheduler) {
        final Set<ModelVertex> directSchedulersAccessed = new HashSet<>();

        final Deque<ModelVertex> stack = new LinkedList<>();
        final Set<ModelVertex> visited = new HashSet<>();

        stack.addLast(scheduler);
        visited.add(scheduler);

        while (!stack.isEmpty()) {
            final ModelVertex next = stack.removeLast();

            for (final ModelEdge edge : next.getOutgoingEdges()) {
                final ModelVertex destination = edge.getDestination();
                final TaskSchedulerType destinationType = destination.getType();

                if (destinationType != TaskSchedulerType.DIRECT
                        && destinationType != TaskSchedulerType.DIRECT_THREADSAFE) {
                    // we don't need to traverse edges that lead into non-direct schedulers
                    continue;
                }

                if (destinationType == TaskSchedulerType.DIRECT) {
                    directSchedulersAccessed.add(destination);
                }

                if (visited.add(destination)) {
                    stack.addLast(destination);
                    visited.add(destination);
                }
            }
        }

        return directSchedulersAccessed;
    }
}
