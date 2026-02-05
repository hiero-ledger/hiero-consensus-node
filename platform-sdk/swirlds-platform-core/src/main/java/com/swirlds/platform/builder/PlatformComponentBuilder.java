// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.state.iss.IssDetector.DO_NOT_IGNORE_ROUNDS;
import static org.hiero.consensus.platformstate.PlatformStateUtils.latestFreezeRoundOf;

import com.swirlds.common.merkle.utility.SerializableLong;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.components.appcomm.DefaultLatestCompleteStateNotifier;
import com.swirlds.platform.components.appcomm.LatestCompleteStateNotifier;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.event.branching.BranchDetector;
import com.swirlds.platform.event.branching.BranchReporter;
import com.swirlds.platform.event.branching.DefaultBranchDetector;
import com.swirlds.platform.event.branching.DefaultBranchReporter;
import com.swirlds.platform.event.stream.ConsensusEventStream;
import com.swirlds.platform.event.stream.DefaultConsensusEventStream;
import com.swirlds.platform.eventhandling.DefaultTransactionHandler;
import com.swirlds.platform.eventhandling.DefaultTransactionPrehandler;
import com.swirlds.platform.eventhandling.TransactionHandler;
import com.swirlds.platform.eventhandling.TransactionPrehandler;
import com.swirlds.platform.reconnect.api.ProtocolFactory;
import com.swirlds.platform.state.hasher.DefaultStateHasher;
import com.swirlds.platform.state.hasher.StateHasher;
import com.swirlds.platform.state.hashlogger.DefaultHashLogger;
import com.swirlds.platform.state.hashlogger.HashLogger;
import com.swirlds.platform.state.iss.DefaultIssDetector;
import com.swirlds.platform.state.iss.IssDetector;
import com.swirlds.platform.state.iss.IssHandler;
import com.swirlds.platform.state.iss.IssScratchpad;
import com.swirlds.platform.state.iss.internal.DefaultIssHandler;
import com.swirlds.platform.state.signed.DefaultSignedStateSentinel;
import com.swirlds.platform.state.signed.DefaultStateGarbageCollector;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedStateSentinel;
import com.swirlds.platform.state.signed.StateGarbageCollector;
import com.swirlds.platform.state.signer.DefaultStateSigner;
import com.swirlds.platform.state.signer.StateSigner;
import com.swirlds.platform.state.snapshot.DefaultStateSnapshotManager;
import com.swirlds.platform.state.snapshot.StateSnapshotManager;
import com.swirlds.platform.system.DefaultPlatformMonitor;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.PlatformMonitor;
import com.swirlds.platform.system.SystemExitUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.function.Supplier;
import org.hiero.consensus.concurrent.manager.AdHocThreadManager;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.gossip.impl.gossip.Gossip;
import org.hiero.consensus.gossip.impl.gossip.SyncGossipModular;
import org.hiero.consensus.gossip.impl.network.protocol.Protocol;
import org.hiero.consensus.model.event.CesEvent;
import org.hiero.consensus.pces.config.PcesConfig;

/**
 * The advanced platform builder is responsible for constructing platform components. This class is exposed so that
 * individual components can be replaced with alternate implementations.
 * <p>
 * In order to be considered a "component", an object must meet the following criteria:
 * <ul>
 *     <li>A component must not require another component as a constructor argument.</li>
 *     <li>A component's constructor should only use things from the {@link PlatformBuildingBlocks} or things derived
 *     from things from the {@link PlatformBuildingBlocks}.</li>
 *     <li>A component must not communicate with other components except through the wiring framework
 *         (with a very small number of exceptions due to tech debt that has not yet been paid off).</li>
 *     <li>A component should have an interface and at default implementation.</li>
 *     <li>A component should use {@link ComponentWiring ComponentWiring} to define
 *         wiring API.</li>
 *     <li>The order in which components are constructed should not matter.</li>
 *     <li>A component must not be a static singleton or use static stateful variables in any way.</li>
 * </ul>
 */
public class PlatformComponentBuilder {

    private final PlatformBuildingBlocks blocks;

    private StateGarbageCollector stateGarbageCollector;
    private ConsensusEventStream consensusEventStream;
    private SignedStateSentinel signedStateSentinel;
    private PlatformMonitor platformMonitor;
    private TransactionPrehandler transactionPrehandler;
    private IssDetector issDetector;
    private IssHandler issHandler;
    private Gossip gossip;
    private StateHasher stateHasher;
    private StateSnapshotManager stateSnapshotManager;
    private HashLogger hashLogger;
    private BranchDetector branchDetector;
    private BranchReporter branchReporter;
    private StateSigner stateSigner;
    private TransactionHandler transactionHandler;
    private LatestCompleteStateNotifier latestCompleteStateNotifier;

    private SwirldsPlatform swirldsPlatform;

    private boolean metricsDocumentationEnabled = true;

    /**
     * False if this builder has not yet been used to build a platform (or platform component builder), true if it has.
     */
    private boolean used;

    /**
     * Constructor.
     *
     * @param blocks the build context for the platform under construction, contains all data needed to construct
     * platform components
     */
    public PlatformComponentBuilder(@NonNull final PlatformBuildingBlocks blocks) {
        this.blocks = Objects.requireNonNull(blocks);
    }

    /**
     * Get the build context for this platform. Contains all data needed to construct platform components.
     *
     * @return the build context
     */
    @NonNull
    public PlatformBuildingBlocks getBuildingBlocks() {
        return blocks;
    }

    /**
     * Throw an exception if this builder has been used to build a platform or a platform factory.
     */
    private void throwIfAlreadyUsed() {
        if (used) {
            throw new IllegalStateException("PlatformBuilder has already been used");
        }
    }

    /**
     * Build the platform.
     *
     * @return the platform
     */
    @NonNull
    public Platform build() {
        throwIfAlreadyUsed();
        used = true;

        try (final ReservedSignedState ignored = blocks.initialState()) {
            swirldsPlatform = new SwirldsPlatform(this);
            return swirldsPlatform;
        } finally {
            getMetricsProvider().start();
        }
    }

    /**
     * Provide a state garbage collector in place of the platform's default state garbage collector.
     *
     * @param stateGarbageCollector the state garbage collector to use
     * @return this builder
     */
    public PlatformComponentBuilder withStateGarbageCollector(
            @NonNull final StateGarbageCollector stateGarbageCollector) {
        throwIfAlreadyUsed();
        if (this.stateGarbageCollector != null) {
            throw new IllegalStateException("State garbage collector has already been set");
        }
        this.stateGarbageCollector = Objects.requireNonNull(stateGarbageCollector);
        return this;
    }

    /**
     * Build the state garbage collector if it has not yet been built. If one has been provided via
     * {@link #withStateGarbageCollector(StateGarbageCollector)}, that garbage collector will be used. If this method is
     * called more than once, only the first call will build the state garbage collector. Otherwise, the default garbage
     * collector will be created and returned.
     *
     * @return the state garbage collector
     */
    @NonNull
    public StateGarbageCollector buildStateGarbageCollector() {
        if (stateGarbageCollector == null) {
            stateGarbageCollector = new DefaultStateGarbageCollector(blocks.platformContext());
        }
        return stateGarbageCollector;
    }

    /**
     * Provide a consensus event stream in place of the platform's default consensus event stream.
     *
     * @param consensusEventStream the consensus event stream to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withConsensusEventStream(@NonNull final ConsensusEventStream consensusEventStream) {
        throwIfAlreadyUsed();
        if (this.consensusEventStream != null) {
            throw new IllegalStateException("Consensus event stream has already been set");
        }
        this.consensusEventStream = Objects.requireNonNull(consensusEventStream);
        return this;
    }

    /**
     * Build the consensus event stream if it has not yet been built. If one has been provided via
     * {@link #withConsensusEventStream(ConsensusEventStream)}, that stream will be used. If this method is called more
     * than once, only the first call will build the consensus event stream. Otherwise, the default stream will be
     * created and returned.
     *
     * @return the consensus event stream
     */
    @NonNull
    public ConsensusEventStream buildConsensusEventStream() {
        if (consensusEventStream == null) {
            consensusEventStream = new DefaultConsensusEventStream(
                    blocks.platformContext(),
                    blocks.selfId(),
                    (byte[] data) -> new PlatformSigner(blocks.keysAndCerts()).sign(data),
                    blocks.consensusEventStreamName(),
                    (CesEvent event) -> event.isLastInRoundReceived()
                            && blocks.freezeChecker()
                                    .isInFreezePeriod(event.getPlatformEvent().getConsensusTimestamp()));
        }
        return consensusEventStream;
    }

    /**
     * Provide a platform monitor in place of the platform's default platform monitor.
     *
     * @param platformMonitor the platform monitor to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withPlatformMonitor(@NonNull final PlatformMonitor platformMonitor) {
        throwIfAlreadyUsed();
        if (this.platformMonitor != null) {
            throw new IllegalStateException("Status state machine has already been set");
        }
        this.platformMonitor = Objects.requireNonNull(platformMonitor);
        return this;
    }

    /**
     * Build the platform monitor if it has not yet been built. If one has been provided via
     * {@link #withPlatformMonitor(PlatformMonitor)}, that platform monitor will be used. If this method is called
     * more than once, only the first call will build the platform monitor. Otherwise, the default platform monitor
     * will be created and returned.
     *
     * @return the platform monitor
     */
    @NonNull
    public PlatformMonitor buildPlatformMonitor() {
        if (platformMonitor == null) {
            platformMonitor = new DefaultPlatformMonitor(blocks.platformContext(), blocks.selfId());
        }
        return platformMonitor;
    }

    /**
     * Provide a signed state sentinel in place of the platform's default signed state sentinel.
     *
     * @param signedStateSentinel the signed state sentinel to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withSignedStateSentinel(@NonNull final SignedStateSentinel signedStateSentinel) {
        throwIfAlreadyUsed();
        if (this.signedStateSentinel != null) {
            throw new IllegalStateException("Signed state sentinel has already been set");
        }
        this.signedStateSentinel = Objects.requireNonNull(signedStateSentinel);
        return this;
    }

    /**
     * Build the signed state sentinel if it has not yet been built. If one has been provided via
     * {@link #withSignedStateSentinel(SignedStateSentinel)}, that sentinel will be used. If this method is called more
     * than once, only the first call will build the signed state sentinel. Otherwise, the default sentinel will be
     * created and returned.
     *
     * @return the signed state sentinel
     */
    @NonNull
    public SignedStateSentinel buildSignedStateSentinel() {
        if (signedStateSentinel == null) {
            signedStateSentinel = new DefaultSignedStateSentinel(blocks.platformContext());
        }
        return signedStateSentinel;
    }

    /**
     * Provide a transaction prehandler in place of the platform's default transaction prehandler.
     *
     * @param transactionPrehandler the transaction prehandler to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withTransactionPrehandler(
            @NonNull final TransactionPrehandler transactionPrehandler) {
        throwIfAlreadyUsed();
        if (this.transactionPrehandler != null) {
            throw new IllegalStateException("Transaction prehandler has already been set");
        }
        this.transactionPrehandler = Objects.requireNonNull(transactionPrehandler);
        return this;
    }

    /**
     * Build the transaction prehandler if it has not yet been built. If one has been provided via
     * {@link #withTransactionPrehandler(TransactionPrehandler)}, that transaction prehandler will be used. If this
     * method is called more than once, only the first call will build the transaction prehandler. Otherwise, the
     * default transaction prehandler will be created and returned.
     *
     * @return the transaction prehandler
     */
    @NonNull
    public TransactionPrehandler buildTransactionPrehandler() {
        if (transactionPrehandler == null) {
            transactionPrehandler = new DefaultTransactionPrehandler(
                    blocks.platformContext(),
                    () -> blocks.latestImmutableStateProviderReference().get().apply("transaction prehandle"),
                    blocks.consensusStateEventHandler());
        }
        return transactionPrehandler;
    }

    /**
     * Provide an ISS detector in place of the platform's default ISS detector.
     *
     * @param issDetector the ISS detector to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withIssDetector(@NonNull final IssDetector issDetector) {
        throwIfAlreadyUsed();
        if (this.issDetector != null) {
            throw new IllegalStateException("ISS detector has already been set");
        }
        this.issDetector = Objects.requireNonNull(issDetector);
        return this;
    }

    /**
     * Build the ISS detector if it has not yet been built. If one has been provided via
     * {@link #withIssDetector(IssDetector)}, that detector will be used. If this method is called more than once, only
     * the first call will build the ISS detector. Otherwise, the default detector will be created and returned.
     *
     * @return the ISS detector
     */
    @NonNull
    public IssDetector buildIssDetector() {
        if (issDetector == null) {
            // Only validate preconsensus signature transactions if we are not recovering from an ISS.
            // ISS round == null means we haven't observed an ISS yet.
            // ISS round < current round means there was an ISS prior to the saved state
            //    that has already been recovered from.
            // ISS round >= current round means that the ISS happens in the future relative the initial state, meaning
            //    we may observe ISS-inducing signature transactions in the preconsensus event stream.

            final SerializableLong issRound = blocks.issScratchpad().get(IssScratchpad.LAST_ISS_ROUND);

            final boolean forceIgnorePcesSignatures = blocks.platformContext()
                    .getConfiguration()
                    .getConfigData(PcesConfig.class)
                    .forceIgnorePcesSignatures();

            final long initialStateRound = blocks.initialState().get().getRound();

            final boolean ignorePreconsensusSignatures;
            if (forceIgnorePcesSignatures) {
                // this is used FOR TESTING ONLY
                ignorePreconsensusSignatures = true;
            } else {
                ignorePreconsensusSignatures = issRound != null && issRound.getValue() >= initialStateRound;
            }

            // A round that we will completely skip ISS detection for. Needed for tests that do janky state modification
            // without a software upgrade (in production this feature should not be used).
            final long roundToIgnore = blocks.platformContext()
                            .getConfiguration()
                            .getConfigData(StateConfig.class)
                            .validateInitialState()
                    ? DO_NOT_IGNORE_ROUNDS
                    : initialStateRound;
            final long latestFreezeRound =
                    latestFreezeRoundOf(blocks.initialState().get().getState());

            issDetector = new DefaultIssDetector(
                    blocks.platformContext(),
                    blocks.rosterHistory().getCurrentRoster(),
                    ignorePreconsensusSignatures,
                    roundToIgnore,
                    latestFreezeRound);
        }
        return issDetector;
    }

    /**
     * Provide an ISS handler in place of the platform's default ISS handler.
     *
     * @param issHandler the ISS handler to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withIssHandler(@NonNull final IssHandler issHandler) {
        throwIfAlreadyUsed();
        if (this.issHandler != null) {
            throw new IllegalStateException("ISS handler has already been set");
        }
        this.issHandler = Objects.requireNonNull(issHandler);
        return this;
    }

    /**
     * Build the ISS handler if it has not yet been built. If one has been provided via
     * {@link #withIssHandler(IssHandler)}, that handler will be used. If this method is called more than once, only the
     * first call will build the ISS handler. Otherwise, the default handler will be created and returned.
     *
     * @return the ISS handler
     */
    @NonNull
    public IssHandler buildIssHandler() {
        if (issHandler == null) {
            issHandler = new DefaultIssHandler(
                    blocks.platformContext(), SystemExitUtils::handleFatalError, blocks.issScratchpad());
        }
        return issHandler;
    }

    /**
     * Provide a gossip in place of the platform's default gossip.
     *
     * @param gossip the gossip to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withGossip(@NonNull final Gossip gossip) {
        throwIfAlreadyUsed();
        if (this.gossip != null) {
            throw new IllegalStateException("Gossip has already been set");
        }
        this.gossip = Objects.requireNonNull(gossip);
        return this;
    }

    /**
     * Build the gossip if it has not yet been built. If one has been provided via {@link #withGossip(Gossip)}, that
     * gossip will be used. If this method is called more than once, only the first call will build the gossip.
     * Otherwise, the default gossip will be created and returned.
     *
     * @return the gossip
     */
    @NonNull
    public Gossip buildGossip() {
        if (gossip == null) {
            final ThreadManager threadManager = AdHocThreadManager.getStaticThreadManager();
            final Supplier<ReservedSignedState> latestCompleteState =
                    () -> blocks.getLatestCompleteStateReference().get().get();

            final ProtocolFactory factory =
                    ServiceLoader.load(ProtocolFactory.class).findFirst().orElseThrow();
            final Protocol reconnectProtocol = factory.createProtocol(
                    blocks.platformContext().getConfiguration(),
                    blocks.platformContext().getMetrics(),
                    blocks.platformContext().getTime(),
                    threadManager,
                    latestCompleteState,
                    blocks.reservedSignedStateResultPromise(),
                    blocks.fallenBehindMonitor(),
                    blocks.stateLifecycleManager());

            gossip = new SyncGossipModular(
                    blocks.platformContext().getConfiguration(),
                    blocks.platformContext().getMetrics(),
                    blocks.platformContext().getTime(),
                    threadManager,
                    blocks.keysAndCerts(),
                    blocks.rosterHistory().getCurrentRoster(),
                    blocks.selfId(),
                    blocks.appVersion(),
                    blocks.intakeEventCounter(),
                    blocks.fallenBehindMonitor(),
                    reconnectProtocol);
        }
        return gossip;
    }

    /**
     * Provide a state hasher in place of the platform's default state hasher.
     *
     * @param stateHasher the state hasher to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withStateHasher(@NonNull final StateHasher stateHasher) {
        throwIfAlreadyUsed();
        if (this.stateHasher != null) {
            throw new IllegalStateException("Signed state hasher has already been set");
        }
        this.stateHasher = Objects.requireNonNull(stateHasher);
        return this;
    }

    /**
     * Build the state hasher if it has not yet been built. If one has been provided via
     * {@link #withStateHasher(StateHasher)}, that hasher will be used. If this method is called more than once, only
     * the first call will build the state hasher. Otherwise, the default hasher will be created and returned.
     *
     * @return the signed state hasher
     */
    @NonNull
    public StateHasher buildStateHasher() {
        if (stateHasher == null) {
            stateHasher = new DefaultStateHasher(blocks.platformContext());
        }
        return stateHasher;
    }

    /**
     * Provide a state snapshot manager in place of the platform's default state snapshot manager.
     *
     * @param stateSnapshotManager the state snapshot manager to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withStateSnapshotManager(@NonNull final StateSnapshotManager stateSnapshotManager) {
        throwIfAlreadyUsed();
        if (this.stateSnapshotManager != null) {
            throw new IllegalStateException("State snapshot manager has already been set");
        }
        this.stateSnapshotManager = Objects.requireNonNull(stateSnapshotManager);
        return this;
    }

    /**
     * Build the state snapshot manager if it has not yet been built. If one has been provided via
     * {@link #withStateSnapshotManager(StateSnapshotManager)}, that manager will be used. If this method is called more
     * than once, only the first call will build the state snapshot manager. Otherwise, the default manager will be
     * created and returned.
     *
     * @return the state snapshot manager
     */
    @NonNull
    public StateSnapshotManager buildStateSnapshotManager() {
        if (stateSnapshotManager == null) {
            final StateConfig stateConfig =
                    blocks.platformContext().getConfiguration().getConfigData(StateConfig.class);
            final String actualMainClassName = stateConfig.getMainClassName(blocks.mainClassName());

            stateSnapshotManager = new DefaultStateSnapshotManager(
                    blocks.platformContext(),
                    actualMainClassName,
                    blocks.selfId(),
                    blocks.swirldName(),
                    blocks.stateLifecycleManager());
        }
        return stateSnapshotManager;
    }

    /**
     * Provide a hash logger in place of the platform's default hash logger.
     *
     * @param hashLogger the hash logger to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withHashLogger(@NonNull final HashLogger hashLogger) {
        throwIfAlreadyUsed();
        if (this.hashLogger != null) {
            throw new IllegalStateException("Hash logger has already been set");
        }
        this.hashLogger = Objects.requireNonNull(hashLogger);
        return this;
    }

    /**
     * Build the hash logger if it has not yet been built. If one has been provided via
     * {@link #withHashLogger(HashLogger)}, that logger will be used. If this method is called more than once, only the
     * first call will build the hash logger. Otherwise, the default logger will be created and returned.
     *
     * @return the hash logger
     */
    @NonNull
    public HashLogger buildHashLogger() {
        if (hashLogger == null) {
            hashLogger = new DefaultHashLogger(blocks.platformContext());
        }
        return hashLogger;
    }

    /**
     * Provide a branch detector in place of the platform's default branch detector.
     *
     * @param branchDetector the branch detector to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withBranchDetector(@NonNull final BranchDetector branchDetector) {
        throwIfAlreadyUsed();
        if (this.branchDetector != null) {
            throw new IllegalStateException("Branch detector has already been set");
        }
        this.branchDetector = Objects.requireNonNull(branchDetector);
        return this;
    }

    /**
     * Build the branch detector if it has not yet been built. If one has been provided via
     * {@link #withBranchDetector(BranchDetector)}, that detector will be used. If this method is called more than once,
     * only the first call will build the branch detector. Otherwise, the default detector will be created and
     * returned.
     *
     * @return the branch detector
     */
    @NonNull
    public BranchDetector buildBranchDetector() {
        if (branchDetector == null) {
            branchDetector = new DefaultBranchDetector(blocks.rosterHistory().getCurrentRoster());
        }
        return branchDetector;
    }

    /**
     * Provide a branch reporter in place of the platform's default branch reporter.
     *
     * @param branchReporter the branch reporter to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withBranchReporter(@NonNull final BranchReporter branchReporter) {
        throwIfAlreadyUsed();
        if (this.branchReporter != null) {
            throw new IllegalStateException("Branch reporter has already been set");
        }
        this.branchReporter = Objects.requireNonNull(branchReporter);
        return this;
    }

    /**
     * Build the branch reporter if it has not yet been built. If one has been provided via
     * {@link #withBranchReporter(BranchReporter)}, that reporter will be used. If this method is called more than once,
     * only the first call will build the branch reporter. Otherwise, the default reporter will be created and
     * returned.
     *
     * @return the branch reporter
     */
    @NonNull
    public BranchReporter buildBranchReporter() {
        if (branchReporter == null) {
            branchReporter = new DefaultBranchReporter(
                    blocks.platformContext(), blocks.rosterHistory().getCurrentRoster());
        }
        return branchReporter;
    }

    /**
     * Provide a state signer in place of the platform's default state signer.
     *
     * @param stateSigner the state signer to use
     * @return this builder
     */
    public PlatformComponentBuilder withStateSigner(@NonNull final StateSigner stateSigner) {
        throwIfAlreadyUsed();
        if (this.stateSigner != null) {
            throw new IllegalStateException("State signer has already been set");
        }
        this.stateSigner = Objects.requireNonNull(stateSigner);
        return this;
    }

    /**
     * Build the state signer if it has not yet been built. If one has been provided via
     * {@link #withStateSigner(StateSigner)}, that signer will be used. If this method is called more than once, only
     * the first call will build the state signer. Otherwise, the default signer will be created and returned.
     *
     * @return the state signer
     */
    @NonNull
    public StateSigner buildStateSigner() {
        if (stateSigner == null) {
            stateSigner = new DefaultStateSigner(new PlatformSigner(blocks.keysAndCerts()));
        }
        return stateSigner;
    }

    /**
     * Provide a transaction handler in place of the platform's default transaction handler.
     *
     * @param transactionHandler the transaction handler to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withTransactionHandler(@NonNull final TransactionHandler transactionHandler) {
        throwIfAlreadyUsed();
        if (this.transactionHandler != null) {
            throw new IllegalStateException("Transaction handler has already been set");
        }
        this.transactionHandler = Objects.requireNonNull(transactionHandler);
        return this;
    }

    /**
     * Build the transaction handler if it has not yet been built. If one has been provided via
     * {@link #withTransactionHandler(TransactionHandler)}, that handler will be used. If this method is called more
     * than once, only the first call will build the transaction handler. Otherwise, the default handler will be created
     * and returned.
     *
     * @return the transaction handler
     */
    @NonNull
    public TransactionHandler buildTransactionHandler() {
        if (transactionHandler == null) {
            transactionHandler = new DefaultTransactionHandler(
                    blocks.platformContext(),
                    blocks.stateLifecycleManager(),
                    blocks.statusActionSubmitterReference().get(),
                    blocks.appVersion(),
                    blocks.consensusStateEventHandler(),
                    blocks.selfId());
        }
        return transactionHandler;
    }

    /**
     * Provide a latest complete state notifier in place of the platform's default latest complete state notifier.
     *
     * @param latestCompleteStateNotifier the latest complete state notifier to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withLatestCompleteStateNotifier(
            @NonNull final LatestCompleteStateNotifier latestCompleteStateNotifier) {
        throwIfAlreadyUsed();
        if (this.latestCompleteStateNotifier != null) {
            throw new IllegalStateException("Latest complete state notifier has already been set");
        }
        this.latestCompleteStateNotifier = Objects.requireNonNull(latestCompleteStateNotifier);
        return this;
    }

    /**
     * Build the latest complete state notifier if it has not yet been built. If one has been provided via
     * {@link #withLatestCompleteStateNotifier(LatestCompleteStateNotifier)}, that notifier will be used. If this method
     * is called more than once, only the first call will build the latest complete state notifier. Otherwise, the
     * default notifier will be created and returned.
     *
     * @return the latest complete state notifier
     */
    @NonNull
    public LatestCompleteStateNotifier buildLatestCompleteStateNotifier() {
        if (latestCompleteStateNotifier == null) {
            latestCompleteStateNotifier = new DefaultLatestCompleteStateNotifier();
        }
        return latestCompleteStateNotifier;
    }
}
