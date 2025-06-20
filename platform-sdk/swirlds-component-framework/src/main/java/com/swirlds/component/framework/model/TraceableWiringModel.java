// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model;

import static com.swirlds.component.framework.model.internal.analysis.ModelVertexMetaType.SCHEDULER;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.DIRECT;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.DIRECT_THREADSAFE;

import com.swirlds.component.framework.model.diagram.ModelEdgeSubstitution;
import com.swirlds.component.framework.model.diagram.ModelGroup;
import com.swirlds.component.framework.model.diagram.ModelManualLink;
import com.swirlds.component.framework.model.internal.analysis.CycleFinder;
import com.swirlds.component.framework.model.internal.analysis.DirectSchedulerChecks;
import com.swirlds.component.framework.model.internal.analysis.InputWireChecks;
import com.swirlds.component.framework.model.internal.analysis.InputWireDescriptor;
import com.swirlds.component.framework.model.internal.analysis.ModelEdge;
import com.swirlds.component.framework.model.internal.analysis.ModelVertex;
import com.swirlds.component.framework.model.internal.analysis.StandardVertex;
import com.swirlds.component.framework.model.internal.analysis.WiringFlowchart;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.wires.SolderType;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Common functionality for wiring model implementations. Has methods for registering information about the topology of
 * wiring that is appropriate for internal use by the framework, but should not be exposed to the end users of the
 * wiring framework.
 */
public abstract class TraceableWiringModel implements WiringModel {

    /**
     * A map of vertex names to vertices.
     */
    private final Map<String, ModelVertex> vertices = new HashMap<>();

    /**
     * A set of all edges in the model.
     */
    private final Set<ModelEdge> edges = new HashSet<>();

    /**
     * Input wires that have been created.
     */
    private final Set<InputWireDescriptor> inputWires = new HashSet<>();

    /**
     * Input wires that have been bound to a handler.
     */
    private final Set<InputWireDescriptor> boundInputWires = new HashSet<>();

    /**
     * Input wires with at least one thing soldered to them.
     */
    private final Set<InputWireDescriptor> solderedInputWires = new HashSet<>();

    /**
     * All task schedulers in the model.
     */
    protected final List<TaskScheduler<?>> schedulers = new ArrayList<>();

    /**
     * True if start() has been called.
     */
    private boolean started = false;

    /**
     * True if backpressure is enabled.
     */
    private final boolean backpressureEnabled;

    /**
     * Constructor.
     *
     * @param backpressureEnabled true if backpressure is enabled
     */
    TraceableWiringModel(final boolean backpressureEnabled) {
        this.backpressureEnabled = backpressureEnabled;
    }

    /**
     * If true then backpressure is enabled. If false then this model will never apply backpressure internally.
     *
     * @return true if backpressure is enabled for this model
     */
    public boolean isBackpressureEnabled() {
        return backpressureEnabled;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean checkForCyclicalBackpressure() {
        return CycleFinder.checkForCyclicalBackPressure(vertices.values());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean checkForIllegalDirectSchedulerUsage() {
        return DirectSchedulerChecks.checkForIllegalDirectSchedulerUse(vertices.values());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean checkForUnboundInputWires() {
        return InputWireChecks.checkForUnboundInputWires(inputWires, boundInputWires);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String generateWiringDiagram(
            @NonNull final List<ModelGroup> groups,
            @NonNull final List<ModelEdgeSubstitution> substitutions,
            @NonNull final List<ModelManualLink> manualLinks,
            final boolean moreMystery) {
        addVertexForUnsolderedInputWires(moreMystery);
        final WiringFlowchart flowchart = new WiringFlowchart(vertices, substitutions, groups, manualLinks);
        return flowchart.render();
    }

    /**
     * Add a special vertex for all unsoldered input wires.
     */
    private void addVertexForUnsolderedInputWires(final boolean moreMystery) {
        final Set<InputWireDescriptor> unsolderedInputWires = new HashSet<>(inputWires);
        unsolderedInputWires.removeAll(solderedInputWires);

        if (unsolderedInputWires.isEmpty()) {
            return;
        }

        final ModelVertex unsolderedDataSource =
                new StandardVertex("Mystery Input", DIRECT_THREADSAFE, SCHEDULER, null, true);
        vertices.put(unsolderedDataSource.getName(), unsolderedDataSource);

        for (final InputWireDescriptor unsolderedInputWire : unsolderedInputWires) {
            final ModelVertex destination = getVertex(unsolderedInputWire.taskSchedulerName());

            final String edgeDescription = moreMystery ? "mystery data" : unsolderedInputWire.name();
            final ModelEdge edge = new ModelEdge(unsolderedDataSource, destination, edgeDescription, true, true);
            unsolderedDataSource.getOutgoingEdges().add(edge);
        }
    }

    /**
     * Register a task scheduler with the wiring model.
     *
     * @param scheduler the task scheduler to register
     * @param hyperlink the hyperlink to the documentation for this vertex, or null if there is no documentation
     */
    public void registerScheduler(@NonNull final TaskScheduler<?> scheduler, @Nullable final String hyperlink) {
        throwIfStarted();
        Objects.requireNonNull(scheduler);
        schedulers.add(scheduler);
        registerVertex(scheduler.getName(), scheduler.getType(), hyperlink, scheduler.isInsertionBlocking());
    }

    /**
     * Register a vertex in the wiring model. These are either task schedulers or wire transformers.
     *
     * @param vertexName          the name of the vertex
     * @param type                the type of task scheduler that corresponds to this vertex.
     * @param hyperlink           the hyperlink to the documentation for this vertex, or null if there is no
     *                            documentation
     * @param insertionIsBlocking if true then insertion may block until capacity is available
     */
    public void registerVertex(
            @NonNull final String vertexName,
            @NonNull final TaskSchedulerType type,
            @Nullable final String hyperlink,
            final boolean insertionIsBlocking) {
        throwIfStarted();
        Objects.requireNonNull(vertexName);
        Objects.requireNonNull(type);
        final boolean unique = vertices.put(
                        vertexName, new StandardVertex(vertexName, type, SCHEDULER, hyperlink, insertionIsBlocking))
                == null;
        if (!unique) {
            throw new IllegalArgumentException("Duplicate vertex name: " + vertexName);
        }
    }

    /**
     * Register an edge between two vertices.
     *
     * @param originVertex      the origin vertex
     * @param destinationVertex the destination vertex
     * @param label             the label of the edge
     * @param solderType        the type of solder connection
     */
    public void registerEdge(
            @NonNull final String originVertex,
            @NonNull final String destinationVertex,
            @NonNull final String label,
            @NonNull final SolderType solderType) {
        throwIfStarted();

        final boolean blockingEdge = solderType == SolderType.PUT;

        final ModelVertex origin = getVertex(originVertex);
        final ModelVertex destination = getVertex(destinationVertex);
        final boolean blocking = blockingEdge && destination.isInsertionIsBlocking();

        final ModelEdge edge = new ModelEdge(origin, destination, label, blocking, false);
        origin.getOutgoingEdges().add(edge);

        final boolean unique = edges.add(edge);
        if (!unique) {
            throw new IllegalArgumentException(
                    "Duplicate edge: " + originVertex + " -> " + destinationVertex + ", label = " + label);
        }

        solderedInputWires.add(new InputWireDescriptor(destinationVertex, label));
    }

    /**
     * Register an input wire with the wiring model. For every input wire registered via this method, the model expects
     * to see exactly one registration via {@link #registerInputWireBinding(String, String)}.
     *
     * @param taskSchedulerName the name of the task scheduler that the input wire is associated with
     * @param inputWireName     the name of the input wire
     */
    public void registerInputWireCreation(
            @NonNull final String taskSchedulerName, @NonNull final String inputWireName) {
        throwIfStarted();

        final boolean unique = inputWires.add(new InputWireDescriptor(taskSchedulerName, inputWireName));
        if (!unique) {
            throw new IllegalStateException(
                    "Duplicate input wire " + inputWireName + " for scheduler " + taskSchedulerName);
        }
    }

    /**
     * Register an input wire binding with the wiring model. For every input wire registered via
     * {@link #registerInputWireCreation(String, String)}, the model expects to see exactly one registration via this
     * method.
     *
     * @param taskSchedulerName the name of the task scheduler that the input wire is associated with
     * @param inputWireName     the name of the input wire
     */
    public void registerInputWireBinding(@NonNull final String taskSchedulerName, @NonNull final String inputWireName) {
        throwIfStarted();

        final InputWireDescriptor descriptor = new InputWireDescriptor(taskSchedulerName, inputWireName);

        final boolean registered = inputWires.contains(descriptor);
        if (!registered) {
            throw new IllegalStateException(
                    "Input wire " + inputWireName + " for scheduler " + taskSchedulerName + " was not registered");
        }

        final boolean unique = boundInputWires.add(descriptor);
        if (!unique) {
            throw new IllegalStateException("Input wire " + inputWireName + " for scheduler " + taskSchedulerName
                    + " should not be bound more than once");
        }
    }

    /**
     * Throw an exception if start() has already been called.
     */
    protected void throwIfStarted() {
        if (started) {
            throw new IllegalStateException("start() has already been called, operation not permitted.");
        }
    }

    /**
     * Throw an exception if the wiring model has not been started.
     */
    protected void throwIfNotStarted() {
        if (!started) {
            throw new IllegalStateException("start() has not been called, operation not permitted.");
        }
    }

    /**
     * Mark the wiring model as started.
     */
    protected void markAsStarted() {
        started = true;
    }

    /**
     * Find an existing vertex
     *
     * @param vertexName the name of the vertex
     * @return the vertex
     */
    @NonNull
    private ModelVertex getVertex(@NonNull final String vertexName) {
        final ModelVertex vertex = vertices.get(vertexName);
        if (vertex != null) {
            return vertex;
        }

        // Create an ad hoc vertex.
        final StandardVertex adHocVertex = new StandardVertex(vertexName, DIRECT, SCHEDULER, null, true);

        vertices.put(vertexName, adHocVertex);
        return adHocVertex;
    }
}
