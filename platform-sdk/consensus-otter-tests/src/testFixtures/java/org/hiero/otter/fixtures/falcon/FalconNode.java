// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.DESTROYED;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.RUNNING;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.hashgraph.impl.ConsensusEngineOutput;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.ProfilerEvent;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.internal.AbstractNode;
import org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver;
import org.hiero.otter.fixtures.internal.NetworkConfiguration;
import org.hiero.otter.fixtures.internal.result.ConsensusRoundPool;
import org.hiero.otter.fixtures.internal.result.NodeResultsCollector;
import org.hiero.otter.fixtures.internal.simulator.SecureRandomBuilder;
import org.hiero.otter.fixtures.internal.simulator.SimulatorTimeManager;
import org.hiero.otter.fixtures.network.simulation.EventReceiver;
import org.hiero.otter.fixtures.network.simulation.SimulatedNetworkConnectivity;
import org.hiero.otter.fixtures.network.transactions.OtterTransaction;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeEventStreamResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;

/**
 * An implementation of {@link Node} that is based on the Falcon framework.
 */
public class FalconNode extends AbstractNode implements Node, TimeTickReceiver, EventReceiver {

    private final Random random;
    private final SimulatorTimeManager timeManager;
    private final SimulatedNetworkConnectivity networkConnectivity;
    private final NodeConfiguration nodeConfiguration;
    private final NodeResultsCollector resultsCollector;

    @Nullable
    private FalconWiring wiring;

    /**
     * Constructor for {@code FalconNode}.
     *
     * @param random the random number generator
     * @param timeManager the time manager
     * @param selfId the ID of this node
     * @param keysAndCerts the keys and certificates of this node
     * @param networkConnectivity the simulated network connectivity
     * @param networkConfiguration the network configuration
     * @param consensusRoundPool the consensus round pool that collects and deduplicates consensus rounds
     */
    public FalconNode(
            @NonNull final Random random,
            @NonNull final SimulatorTimeManager timeManager,
            @NonNull final NodeId selfId,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final SimulatedNetworkConnectivity networkConnectivity,
            @NonNull final NetworkConfiguration networkConfiguration,
            @NonNull final ConsensusRoundPool consensusRoundPool) {
        super(selfId, keysAndCerts, networkConfiguration);
        this.random = requireNonNull(random);
        this.timeManager = requireNonNull(timeManager);
        this.networkConnectivity = requireNonNull(networkConnectivity);

        this.nodeConfiguration =
                new FalconNodeConfiguration(() -> lifeCycle, networkConfiguration.overrideProperties());
        this.resultsCollector = new NodeResultsCollector(selfId, consensusRoundPool);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean receiveEvent(@NonNull final PlatformEvent event) {
        if (wiring != null) {
            wiring.receivedGossipEventsInputWire().put(event);
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected TimeManager timeManager() {
        return timeManager;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected Random random() {
        return random;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStart(@NonNull final Duration timeout) {
        throwIfInLifecycle(RUNNING, "Node has already been started.");
        throwIfInLifecycle(DESTROYED, "Node has already been destroyed.");

        final Configuration currentConfiguration = nodeConfiguration.current();
        final Time time = timeManager.time();
        final SecureRandom secureRandom = new SecureRandomBuilder(random.nextLong()).get();

        wiring = new FalconWiring(currentConfiguration, time, selfId, roster(), secureRandom);
        wiring.sentGossipEventsOutputWire().solderTo("EventSubmitter_" + selfId, "event", event -> {
            // Self-created events have no sender until now; the network identifies the source by this field
            event.setSenderId(selfId);
            networkConnectivity.submitEvent(event);
        });
        wiring.eventWindowOutputWire()
                .solderTo(
                        "EventWindowSubmitter_" + selfId,
                        "event window",
                        eventWindow -> networkConnectivity.updateEventWindow(selfId, eventWindow));
        wiring.consensusOutputWire()
                .buildTransformer(
                        "ConsensusResultCollector", "consensus result", ConsensusEngineOutput::consensusRounds)
                .<ConsensusRound>buildSplitter("ConsensusResultSplitter", "consensus rounds")
                .solderTo("ResultsCollector", "consensus round", resultsCollector::addConsensusRound);
        wiring.start();

        platformStatus = PlatformStatus.ACTIVE;
        lifeCycle = RUNNING;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doKillImmediately(@NonNull final Duration timeout) {
        throw new UnsupportedOperationException("Killing a node is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStartSyntheticBottleneck(@NonNull final Duration delayPerRound, @NonNull final Duration timeout) {
        throw new UnsupportedOperationException("Synthetic bottleneck is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStopSyntheticBottleneck(@NonNull final Duration timeout) {
        throw new UnsupportedOperationException("Synthetic bottleneck is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doSendQuiescenceCommand(@NonNull final QuiescenceCommand command, @NonNull final Duration timeout) {
        throw new UnsupportedOperationException("Quiescence command is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitTransactions(@NonNull final List<OtterTransaction> transactions) {
        throw new UnsupportedOperationException("Transactions are not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doTriggerSelfIss(@NonNull final Duration timeout) {
        throw new UnsupportedOperationException("Self ISS is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startFromSavedState(@NonNull final Path savedStateDirectory) {
        throw new UnsupportedOperationException("Starting from a saved state is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void version(@NonNull final SemanticVersion version) {
        throw new UnsupportedOperationException("Versions are not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bumpConfigVersion() {
        throw new UnsupportedOperationException("Versions are not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration configuration() {
        return nodeConfiguration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeConsensusResult newConsensusResult() {
        return resultsCollector.newConsensusResult();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeLogResult newLogResult() {
        throw new UnsupportedOperationException("Log results are not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodePlatformStatusResult newPlatformStatusResult() {
        throw new UnsupportedOperationException("Platform status is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodePcesResult newPcesResult() {
        throw new UnsupportedOperationException("PCES is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeReconnectResult newReconnectResult() {
        throw new UnsupportedOperationException("Reconnect is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeEventStreamResult newEventStreamResult() {
        throw new UnsupportedOperationException("Event stream is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAlive() {
        return lifeCycle == RUNNING;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startProfiling(
            @NonNull final String outputFile,
            @NonNull final Duration samplingInterval,
            @NonNull final ProfilerEvent... events) {
        throw new UnsupportedOperationException("Profiling is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stopProfiling() {
        throw new UnsupportedOperationException("Profiling is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String dumpThreads() {
        throw new UnsupportedOperationException("Profiling is not supported in FalconNode.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull final Instant now) {
        if (wiring != null) {
            wiring.tick(now);
        }
    }
}
