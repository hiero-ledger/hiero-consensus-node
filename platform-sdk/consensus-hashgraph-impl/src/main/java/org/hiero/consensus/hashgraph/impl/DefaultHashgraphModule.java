// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.input.NoInput;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.freeze.FreezePeriodChecker;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.hashgraph.config.HashgraphWiringConfig;
import org.hiero.consensus.hashgraph.impl.DefaultConsensusEngineBuffer.ConsensusEngineBufferOutput;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Default implementation of the {@link HashgraphModule}.
 */
public class DefaultHashgraphModule implements HashgraphModule {

    @Nullable
    private ComponentWiring<ConsensusEngineBuffer, ConsensusEngineBufferOutput> consensusEngineBufferWiring;

    @Nullable
    private OutputWire<ConsensusRound> consensusRoundOutputWire;

    @Nullable
    private OutputWire<PlatformEvent> preconsensusEventOutputWire;

    @Nullable
    private OutputWire<PlatformEvent> staleEventOutputWire;

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(
            @NonNull final WiringModel model,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final FreezePeriodChecker freezeChecker,
            @Nullable final EventPipelineTracker pipelineTracker,
            final long transactionOffsetNanos) {

        //noinspection VariableNotUsedInsideIf
        if (consensusEngineBufferWiring != null) {
            throw new IllegalStateException("Already initialized");
        }

        final HashgraphWiringConfig wiringConfig = configuration.getConfigData(HashgraphWiringConfig.class);

        this.consensusEngineBufferWiring =
                new ComponentWiring<>(model, ConsensusEngineBuffer.class, wiringConfig.consensusEngineBuffer());

        this.consensusRoundOutputWire = consensusEngineBufferWiring
                .getOutputWire()
                .buildTransformer("consensusResults", "consensusEngineBufferOutput",
                        (output) -> output.consensusResults().stream().map(ConsensusResult::consensusRound).toList())
                .buildSplitter("ConsensusResultsSplitter", "consensus results");
        this.preconsensusEventOutputWire = consensusEngineBufferWiring
                .getOutputWire()
                .buildTransformer(
                        "PreConsensusEvents", "consensusEngineBufferOutput",
                        ConsensusEngineBufferOutput::preConsensusEvents)
                .buildSplitter("PreConsensusEventsSplitter", "preconsensus events");
        this.staleEventOutputWire = consensusEngineBufferWiring
                .getOutputWire()
                .buildTransformer("staleEvents", "consensusEngineOutput",
                        (output) -> output.consensusResults().stream().flatMap(result -> result.staleEvents().stream())
                                .toList())
                .buildSplitter("staleEventsSplitter", "stale events");

        // Force not soldered wires to be built
        consensusEngineBufferWiring.getInputWire(ConsensusEngineBuffer::outOfBandSnapshotUpdate);

        // Create and bind components
        final ConsensusEngine consensusEngine = new DefaultConsensusEngine(
                configuration, metrics, time, roster, selfId, freezeChecker, transactionOffsetNanos);
        final ConsensusEngineBuffer consensusEngineBuffer = new DefaultConsensusEngineBuffer(configuration, metrics,
                consensusEngine);
        consensusEngineBufferWiring.bind(consensusEngineBuffer);

        if (pipelineTracker != null) {
            pipelineTracker.registerMetric("consensus");
            consensusRoundOutputWire.solderForMonitoring(
                    consensusRound -> pipelineTracker.recordEvents("consensus", consensusRound.getPlatformEvents()));
        }
    }

    @NonNull
    @Override
    public InputWire<NoInput> requestRoundInputWire() {
        return requireNonNull(consensusEngineBufferWiring, "Not initialized").getInputWire(ConsensusEngineBuffer::requestRound);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public InputWire<PlatformEvent> eventInputWire() {
        return requireNonNull(consensusEngineBufferWiring, "Not initialized").getInputWire(ConsensusEngineBuffer::addEvent);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public OutputWire<ConsensusRound> consensusRoundOutputWire() {
        return requireNonNull(consensusRoundOutputWire, "Not initialized");
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public OutputWire<PlatformEvent> preconsensusEventOutputWire() {
        return requireNonNull(preconsensusEventOutputWire, "Not initialized");
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public OutputWire<PlatformEvent> staleEventOutputWire() {
        return requireNonNull(staleEventOutputWire, "Not initialized");
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public InputWire<PlatformStatus> platformStatusInputWire() {
        return requireNonNull(consensusEngineBufferWiring, "Not initialized")
                .getInputWire(ConsensusEngineBuffer::updatePlatformStatus);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public InputWire<ConsensusSnapshot> consensusSnapshotOverrideInputWire() {
        return requireNonNull(consensusEngineBufferWiring, "Not initialized")
                .getInputWire(ConsensusEngineBuffer::outOfBandSnapshotUpdate);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startSquelching() {
        requireNonNull(consensusEngineBufferWiring, "Not initialized").startSquelching();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stopSquelching() {
        requireNonNull(consensusEngineBufferWiring, "Not initialized").stopSquelching();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() {
        requireNonNull(consensusEngineBufferWiring, "Not initialized").flush();
    }
}
