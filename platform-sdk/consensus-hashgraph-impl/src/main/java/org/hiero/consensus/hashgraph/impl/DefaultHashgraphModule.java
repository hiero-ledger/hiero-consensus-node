// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.hashgraph.FreezePeriodChecker;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.hashgraph.config.HashgraphWiringConfig;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Default implementation of the {@link HashgraphModule}.
 */
public class DefaultHashgraphModule implements HashgraphModule {

    @Nullable
    private ComponentWiring<ConsensusEngine, ConsensusEngineOutput> consensusEngineWiring;

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
            @Nullable EventPipelineTracker pipelineTracker) {

        //noinspection VariableNotUsedInsideIf
        if (consensusEngineWiring != null) {
            throw new IllegalStateException("Already initialized");
        }

        final HashgraphWiringConfig wiringConfig = configuration.getConfigData(HashgraphWiringConfig.class);

        this.consensusEngineWiring =
                new ComponentWiring<>(model, ConsensusEngine.class, wiringConfig.consensusEngine());

        this.consensusRoundOutputWire = consensusEngineWiring
                .getOutputWire()
                .buildTransformer("consensusRounds", "consensusEngineOutput", ConsensusEngineOutput::consensusRounds)
                .buildSplitter("ConsensusRoundsSplitter", "consensus rounds");
        this.preconsensusEventOutputWire = consensusEngineWiring
                .getOutputWire()
                .buildTransformer(
                        "PreConsensusEvents", "consensusEngineOutput", ConsensusEngineOutput::preConsensusEvents)
                .buildSplitter("PreConsensusEventsSplitter", "preconsensus events");
        this.staleEventOutputWire = consensusEngineWiring
                .getOutputWire()
                .buildTransformer("staleEvents", "consensusEngineOutput", ConsensusEngineOutput::staleEvents)
                .buildSplitter("staleEventsSplitter", "stale events");

        // Force not soldered wires to be built
        consensusEngineWiring.getInputWire(ConsensusEngine::outOfBandSnapshotUpdate);

        // Create and bind components
        final ConsensusEngine consensusEngine =
                new DefaultConsensusEngine(configuration, metrics, time, roster, selfId, freezeChecker);
        consensusEngineWiring.bind(consensusEngine);

        if (pipelineTracker != null) {
            pipelineTracker.registerMetric("consensus");
            consensusRoundOutputWire.solderForMonitoring(consensusRound -> pipelineTracker.recordEvents(
                    "consensus",
                    consensusRound.getConsensusEvents().stream()
                            .map(PlatformEvent::getTimeReceived)
                            .toList()));
        }
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public InputWire<PlatformEvent> eventInputWire() {
        return requireNonNull(consensusEngineWiring, "Not initialized").getInputWire(ConsensusEngine::addEvent);
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
        return requireNonNull(consensusEngineWiring, "Not initialized")
                .getInputWire(ConsensusEngine::updatePlatformStatus);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public InputWire<ConsensusSnapshot> consensusSnapshotInputWire() {
        return requireNonNull(consensusEngineWiring, "Not initialized")
                .getInputWire(ConsensusEngine::outOfBandSnapshotUpdate);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startSquelching() {
        requireNonNull(consensusEngineWiring, "Not initialized").startSquelching();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stopSquelching() {
        requireNonNull(consensusEngineWiring, "Not initialized").stopSquelching();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() {
        requireNonNull(consensusEngineWiring, "Not initialized").flush();
    }
}
