// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration.DIRECT_THREADSAFE_CONFIGURATION;
import static com.swirlds.component.framework.wires.SolderType.OFFER;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.transformers.WireFilter;
import com.swirlds.component.framework.transformers.WireTransformer;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.input.NoInput;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Queue;
import java.util.function.UnaryOperator;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.state.StateSavingResult;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.state.config.StateConfig;
import org.hiero.consensus.state.config.StateWiringConfig;
import org.hiero.consensus.state.hashing.DefaultHashLogger;
import org.hiero.consensus.state.hashing.DefaultStateHasher;
import org.hiero.consensus.state.hashing.HashLogger;
import org.hiero.consensus.state.hashing.StateHasher;
import org.hiero.consensus.state.nexus.LatestCompleteStateNexus;
import org.hiero.consensus.state.persistence.DefaultStateSnapshotManager;
import org.hiero.consensus.state.persistence.StateSnapshotManager;
import org.hiero.consensus.state.sentinel.DefaultSignedStateSentinel;
import org.hiero.consensus.state.sentinel.SignedStateSentinel;
import org.hiero.consensus.state.signed.DefaultStateGarbageCollector;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.state.signed.StateGarbageCollector;
import org.hiero.consensus.state.signed.StateWithHashComplexity;
import org.hiero.consensus.state.signing.DefaultStateSignatureCollector;
import org.hiero.consensus.state.signing.DefaultStateSigner;
import org.hiero.consensus.state.signing.SignedStateMetrics;
import org.hiero.consensus.state.signing.StateSignatureCollector;
import org.hiero.consensus.state.signing.StateSigner;
import org.hiero.consensus.state.utils.SignedStateReserver;

/**
 * Module for signed state management.
 */
public class StateModule {

    private final WireTransformer<ConsensusRound, EventWindow> eventWindowExtractor;

    private final WireTransformer<ReservedSignedState, ReservedSignedState> stateDispatcher;

    private final ComponentWiring<StateHasher, ReservedSignedState> stateHasherWiring;

    private final ComponentWiring<StateSigner, StateSignatureTransaction> stateSignerWiring;
    private final ComponentWiring<StateSignatureCollector, List<ReservedSignedState>> stateSignatureCollectorWiring;

    private final ComponentWiring<StateSnapshotManager, StateSavingResult> stateSnapshotManagerWiring;
    private final ComponentWiring<SavedStateController, StateWithHashComplexity> savedStateControllerWiring;

    private final ComponentWiring<LatestCompleteStateNexus, Void> latestCompleteStateNexusWiring;

    private final ComponentWiring<StateGarbageCollector, Void> stateGarbageCollectorWiring;

    private final OutputWire<ReservedSignedState> hashedStateOutputWire;

    /**
     * Constructor for {@code TransactionHandlingModule}
     *
     * @param model the wiring model
     * @param configuration the configuration
     * @param metrics the metrics system
     * @param time the time source
     * @param fileSystemManager the file system manager
     * @param keysAndCerts the keys and certs
     * @param mainClassName the main class name of the application
     * @param selfId the ID of this node
     * @param swirldName the swirld name
     * @param stateLifecycleManager the state lifecycle manager
     * @param latestCompleteStateNexus the latest complete state nexus
     * @param savedStateController the saved state controller
     */
    public StateModule(
            @NonNull final WiringModel model,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final FileSystemManager fileSystemManager,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final String mainClassName,
            @NonNull final NodeId selfId,
            @NonNull final String swirldName,
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager,
            @NonNull final LatestCompleteStateNexus latestCompleteStateNexus,
            @NonNull final SavedStateController savedStateController) {

        // Set up wiring
        this.eventWindowExtractor = new WireTransformer<>(
                model, "State_EventWindowExtractor", "consensus round", ConsensusRound::getEventWindow);
        this.stateDispatcher =
                new WireTransformer<>(model, "ReservedSignedStateDispatcher", "signed state", UnaryOperator.identity());

        final StateWiringConfig wiringConfig = configuration.getConfigData(StateWiringConfig.class);
        this.stateHasherWiring = new ComponentWiring<>(
                model,
                StateHasher.class,
                wiringConfig.stateHasher(),
                data -> data instanceof final StateWithHashComplexity swhc ? swhc.hashComplexity() : 1);
        final ComponentWiring<HashLogger, Void> hashLoggerWiring =
                new ComponentWiring<>(model, HashLogger.class, wiringConfig.hashLogger());
        this.stateSignerWiring = new ComponentWiring<>(model, StateSigner.class, wiringConfig.stateSigner());
        this.stateSignatureCollectorWiring =
                new ComponentWiring<>(model, StateSignatureCollector.class, wiringConfig.stateSignatureCollector());
        this.stateSnapshotManagerWiring =
                new ComponentWiring<>(model, StateSnapshotManager.class, wiringConfig.stateSnapshotManager());
        this.savedStateControllerWiring =
                new ComponentWiring<>(model, SavedStateController.class, DIRECT_THREADSAFE_CONFIGURATION);
        this.latestCompleteStateNexusWiring =
                new ComponentWiring<>(model, LatestCompleteStateNexus.class, DIRECT_THREADSAFE_CONFIGURATION);
        this.stateGarbageCollectorWiring =
                new ComponentWiring<>(model, StateGarbageCollector.class, wiringConfig.stateGarbageCollector());
        final ComponentWiring<SignedStateSentinel, Void> signedStateSentinelWiring =
                new ComponentWiring<>(model, SignedStateSentinel.class, wiringConfig.signedStateSentinel());

        // Wire components
        eventWindowExtractor
                .getOutputWire()
                .solderTo(latestCompleteStateNexusWiring.getInputWire(LatestCompleteStateNexus::updateEventWindow));

        // Eventually mark unhashed state for storage and forward to StateHasher
        savedStateControllerWiring.getOutputWire().solderTo(stateHasherWiring.getInputWire(StateHasher::hashState));

        // The state hasher needs to pass its data through a transformer.
        hashedStateOutputWire = stateHasherWiring
                .getOutputWire()
                .buildAdvancedTransformer(new SignedStateReserver("postHasher_stateReserver"));
        hashedStateOutputWire.solderTo(stateSignerWiring.getInputWire(StateSigner::signState));

        // The hashed state has to be dispatched to the logger and signature collector.
        hashedStateOutputWire.solderTo(stateDispatcher.getInputWire());
        final OutputWire<ReservedSignedState> dispatcherOutputWire = stateDispatcher
                .getOutputWire()
                .buildAdvancedTransformer(new SignedStateReserver("postDispatcher_stateReserver"));
        dispatcherOutputWire.solderTo(hashLoggerWiring.getInputWire(HashLogger::logHashes));
        dispatcherOutputWire.solderTo(
                stateSignatureCollectorWiring.getInputWire(StateSignatureCollector::addReservedState));

        // Split output of StateSignatureCollector into single ReservedSignedStates.
        final OutputWire<ReservedSignedState> allReservedSignedStatesWire = stateSignatureCollectorWiring
                .getOutputWire()
                .<ReservedSignedState>buildSplitter("reservedStateSplitter", "reserved state lists")
                .buildAdvancedTransformer(new SignedStateReserver("allStatesReserver"));

        // Filter states that need to be saved to disk and pass them to StateSnapshotManager
        final WireFilter<ReservedSignedState> saveToDiskFilter =
                new WireFilter<>(model, "saveToDiskFilter", "states", state -> {
                    if (state.get().isStateToSave()) {
                        return true;
                    }
                    state.close();
                    return false;
                });
        allReservedSignedStatesWire.solderTo(saveToDiskFilter.getInputWire());
        saveToDiskFilter
                .getOutputWire()
                .solderTo(stateSnapshotManagerWiring.getInputWire(StateSnapshotManager::saveStateTask));

        // Filter to complete states only and store in latestCompleteStateNexus
        allReservedSignedStatesWire
                .buildFilter("completeStateFilter", "states", rs -> {
                    if (rs.get().isComplete()) {
                        return true;
                    } else {
                        // close the second reservation on states that are not passed on.
                        rs.close();
                        return false;
                    }
                })
                .solderTo(latestCompleteStateNexusWiring.getInputWire(LatestCompleteStateNexus::setStateIfNewer));

        // Setup heartbeat
        model.buildHeartbeatWire(wiringConfig.stateGarbageCollectorHeartbeatPeriod())
                .solderTo(stateGarbageCollectorWiring.getInputWire(StateGarbageCollector::heartbeat), OFFER);
        model.buildHeartbeatWire(wiringConfig.signedStateSentinelHeartbeatPeriod())
                .solderTo(signedStateSentinelWiring.getInputWire(SignedStateSentinel::checkSignedStates), OFFER);

        // Force not soldered wires to be built
        stateSignatureCollectorWiring.getInputWire(StateSignatureCollector::clear);
        stateSnapshotManagerWiring.getInputWire(StateSnapshotManager::dumpStateTask);

        // Create and bind components
        final StateHasher stateHasher = new DefaultStateHasher(metrics);
        stateHasherWiring.bind(stateHasher);
        final HashLogger hashLogger = new DefaultHashLogger(configuration);
        hashLoggerWiring.bind(hashLogger);
        final StateSigner stateSigner = new DefaultStateSigner(new PlatformSigner(keysAndCerts));
        stateSignerWiring.bind(stateSigner);
        final SignedStateMetrics signedStateMetrics = new SignedStateMetrics(metrics);
        final StateSignatureCollector stateSignatureCollector =
                new DefaultStateSignatureCollector(configuration, signedStateMetrics);
        stateSignatureCollectorWiring.bind(stateSignatureCollector);
        final StateConfig stateConfig = configuration.getConfigData(StateConfig.class);
        final String actualMainClassName = stateConfig.getMainClassName(mainClassName);
        final StateSnapshotManager stateSnapshotManager = new DefaultStateSnapshotManager(
                configuration,
                metrics,
                time,
                fileSystemManager,
                actualMainClassName,
                selfId,
                swirldName,
                stateLifecycleManager);
        stateSnapshotManagerWiring.bind(stateSnapshotManager);
        savedStateControllerWiring.bind(savedStateController);
        latestCompleteStateNexusWiring.bind(latestCompleteStateNexus);
        final StateGarbageCollector garbageCollector = new DefaultStateGarbageCollector(metrics);
        stateGarbageCollectorWiring.bind(garbageCollector);
        final SignedStateSentinel signedStateSentinel = new DefaultSignedStateSentinel(configuration);
        signedStateSentinelWiring.bind(signedStateSentinel);
    }

    /**
     * Get the input wire for {@link StateWithHashComplexity}s to handle post-consensus.
     *
     * @return the input wire for the transactions
     */
    @InputWireLabel("state to hash")
    @NonNull
    public InputWire<StateWithHashComplexity> unhashedStatesInputWire() {
        return savedStateControllerWiring.getInputWire(SavedStateController::markSavedState);
    }

    /**
     * Get the input wire for registering states in the garbage collector
     *
     * @return the input wire for registering states
     */
    @InputWireLabel("hashed states")
    @NonNull
    public InputWire<ReservedSignedState> garbageCollectorRegistrationInputWire() {
        return stateGarbageCollectorWiring.getInputWire(StateGarbageCollector::registerState);
    }

    /**
     * Get the input wire for {@link StateSignatureTransaction}s to handle preconsensus.
     *
     * @return the input wire for the transactions
     */
    @InputWireLabel("preconsensus state signatures")
    @NonNull
    public InputWire<Queue<ScopedSystemTransaction<StateSignatureTransaction>>>
            preconsensusSystemTransactionsInputWire() {
        return stateSignatureCollectorWiring.getInputWire(StateSignatureCollector::handlePreconsensusSignatures);
    }

    /**
     * Get the input wire for {@link StateSignatureTransaction}s to handle post-consensus.
     *
     * @return the input wire for the transactions
     */
    @InputWireLabel("post consensus state signatures")
    @NonNull
    public InputWire<Queue<ScopedSystemTransaction<StateSignatureTransaction>>>
            postconsensusSystemTranscationsInputWire() {
        return stateSignatureCollectorWiring.getInputWire(StateSignatureCollector::handlePostconsensusSignatures);
    }

    /**
     * Get the input wire for setting the latest {@link PlatformStatus}.
     *
     * @return the input wire for the transactions
     */
    @NonNull
    public InputWire<PlatformStatus> platformStatusInputWire() {
        return latestCompleteStateNexusWiring.getInputWire(LatestCompleteStateNexus::updatePlatformStatus);
    }

    /**
     * {@link InputWire} for the consensus round received from the {@code Hashgraph} component.
     *
     * @return the {@link InputWire} for the consensus round
     */
    @InputWireLabel("consensus round")
    @NonNull
    public InputWire<ConsensusRound> consensusRoundInputWire() {
        return eventWindowExtractor.getInputWire();
    }

    /**
     * {@link InputWire} for the initial event window.
     *
     * @return the {@link InputWire} for the initial event window
     */
    @InputWireLabel("initial event window")
    @NonNull
    public InputWire<EventWindow> initialEventWindowInputWire() {
        return latestCompleteStateNexusWiring.getInputWire(LatestCompleteStateNexus::updateEventWindow);
    }

    /**
     * Get the output wire for hashed states
     *
     * @return the output wire for hashed states
     */
    @NonNull
    public OutputWire<ReservedSignedState> hashedStateOutputWire() {
        return hashedStateOutputWire;
    }

    /**
     * Get the output wire for state signature transactions
     *
     * @return the output wire for state signature transactions
     */
    @NonNull
    public OutputWire<StateSignatureTransaction> stateSignaturesOutputWire() {
        return stateSignerWiring.getOutputWire();
    }

    /**
     * Get the output wire for the oldest minimum birth round on disk.
     *
     * @return the output wire for the oldest minimum birth round on disk.
     */
    @NonNull
    public OutputWire<Long> oldestMinimumBirthRoundOnDiskOutputWire() {
        return stateSnapshotManagerWiring.getTransformedOutput(
                StateSnapshotManager::extractOldestMinimumBirthRoundOnDisk);
    }

    /**
     * Get the result of state saving.
     *
     * @return the output wire for the result of state saving.
     */
    @NonNull
    public OutputWire<StateSavingResult> stateSavingResultOutputWire() {
        return stateSnapshotManagerWiring.getOutputWire();
    }

    /**
     * Get the input wire for clearing the state module.
     *
     * @return the input wire for clearing the state module.
     */
    public InputWire<NoInput> clearInputWire() {
        return stateSignatureCollectorWiring.getInputWire(StateSignatureCollector::clear);
    }

    /**
     * Flush the {@code StateModule}.
     */
    public void flush() {
        stateHasherWiring.flush();
        stateSignatureCollectorWiring.flush();
    }

    /**
     * Forward a state to the hash logger.
     *
     * @param signedState the state to forward
     */
    public void sendState(@NonNull final SignedState signedState) {
        final ReservedSignedState stateReservedForHasher = signedState.reserve("logging state hash");

        final boolean offerResult = stateDispatcher.getInputWire().offer(stateReservedForHasher);
        if (!offerResult) {
            stateReservedForHasher.close();
        }
    }
}
