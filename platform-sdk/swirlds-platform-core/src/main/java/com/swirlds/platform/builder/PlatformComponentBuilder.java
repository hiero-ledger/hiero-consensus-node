// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getGlobalMetrics;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.gui.internal.BrowserWindowManager.getPlatforms;
import static com.swirlds.platform.state.iss.IssDetector.DO_NOT_IGNORE_ROUNDS;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.utility.SerializableLong;
import com.swirlds.common.threading.manager.AdHocThreadManager;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.components.appcomm.DefaultLatestCompleteStateNotifier;
import com.swirlds.platform.components.appcomm.LatestCompleteStateNotifier;
import com.swirlds.platform.components.consensus.ConsensusEngine;
import com.swirlds.platform.components.consensus.DefaultConsensusEngine;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.event.branching.BranchDetector;
import com.swirlds.platform.event.branching.BranchReporter;
import com.swirlds.platform.event.branching.DefaultBranchDetector;
import com.swirlds.platform.event.branching.DefaultBranchReporter;
import com.swirlds.platform.event.deduplication.EventDeduplicator;
import com.swirlds.platform.event.deduplication.StandardEventDeduplicator;
import com.swirlds.platform.event.orphan.DefaultOrphanBuffer;
import com.swirlds.platform.event.orphan.OrphanBuffer;
import com.swirlds.platform.event.preconsensus.DefaultInlinePcesWriter;
import com.swirlds.platform.event.preconsensus.InlinePcesWriter;
import com.swirlds.platform.event.preconsensus.PcesConfig;
import com.swirlds.platform.event.preconsensus.PcesFileManager;
import com.swirlds.platform.event.preconsensus.PcesUtilities;
import com.swirlds.platform.event.resubmitter.DefaultTransactionResubmitter;
import com.swirlds.platform.event.resubmitter.TransactionResubmitter;
import com.swirlds.platform.event.stream.ConsensusEventStream;
import com.swirlds.platform.event.stream.DefaultConsensusEventStream;
import com.swirlds.platform.event.validation.DefaultEventSignatureValidator;
import com.swirlds.platform.event.validation.DefaultInternalEventValidator;
import com.swirlds.platform.event.validation.EventSignatureValidator;
import com.swirlds.platform.event.validation.InternalEventValidator;
import com.swirlds.platform.eventhandling.DefaultTransactionHandler;
import com.swirlds.platform.eventhandling.DefaultTransactionPrehandler;
import com.swirlds.platform.eventhandling.TransactionHandler;
import com.swirlds.platform.eventhandling.TransactionPrehandler;
import com.swirlds.platform.gossip.SyncGossipModular;
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
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SystemExitUtils;
import com.swirlds.platform.system.status.DefaultStatusStateMachine;
import com.swirlds.platform.system.status.StatusStateMachine;
import com.swirlds.platform.util.MetricsDocUtils;
import com.swirlds.platform.wiring.components.Gossip;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.event.FutureEventBuffer;
import org.hiero.consensus.event.creator.impl.DefaultEventCreationManager;
import org.hiero.consensus.event.creator.impl.EventCreationManager;
import org.hiero.consensus.event.creator.impl.EventCreator;
import org.hiero.consensus.event.creator.impl.pool.DefaultTransactionPool;
import org.hiero.consensus.event.creator.impl.pool.TransactionPool;
import org.hiero.consensus.event.creator.impl.signing.DefaultSelfEventSigner;
import org.hiero.consensus.event.creator.impl.signing.SelfEventSigner;
import org.hiero.consensus.event.creator.impl.stale.DefaultStaleEventDetector;
import org.hiero.consensus.event.creator.impl.stale.StaleEventDetector;
import org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator;
import org.hiero.consensus.model.event.CesEvent;

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

    private EventHasher eventHasher;
    private InternalEventValidator internalEventValidator;
    private EventDeduplicator eventDeduplicator;
    private EventSignatureValidator eventSignatureValidator;
    private SelfEventSigner selfEventSigner;
    private StateGarbageCollector stateGarbageCollector;
    private OrphanBuffer orphanBuffer;
    private EventCreationManager eventCreationManager;
    private ConsensusEngine consensusEngine;
    private ConsensusEventStream consensusEventStream;
    private SignedStateSentinel signedStateSentinel;
    private StatusStateMachine statusStateMachine;
    private TransactionPrehandler transactionPrehandler;
    private InlinePcesWriter inlinePcesWriter;
    private IssDetector issDetector;
    private IssHandler issHandler;
    private Gossip gossip;
    private StaleEventDetector staleEventDetector;
    private TransactionResubmitter transactionResubmitter;
    private TransactionPool transactionPool;
    private StateHasher stateHasher;
    private StateSnapshotManager stateSnapshotManager;
    private HashLogger hashLogger;
    private BranchDetector branchDetector;
    private BranchReporter branchReporter;
    private StateSigner stateSigner;
    private TransactionHandler transactionHandler;
    private LatestCompleteStateNotifier latestCompleteStateNotifier;
    private FutureEventBuffer futureEventBuffer;

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

        try (final ReservedSignedState initialState = blocks.initialState()) {
            swirldsPlatform = new SwirldsPlatform(this);
            return swirldsPlatform;
        } finally {
            if (metricsDocumentationEnabled) {
                // Future work: eliminate the static variables that require this code to exist
                if (blocks.firstPlatform()) {
                    MetricsDocUtils.writeMetricsDocumentToFile(
                            getGlobalMetrics(),
                            getPlatforms(),
                            blocks.platformContext().getConfiguration());
                    getMetricsProvider().start();
                }
            }
        }
    }

    /**
     * If enabled, building this object will cause a metrics document to be generated. Default is true.
     *
     * @param metricsDocumentationEnabled whether to generate a metrics document
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withMetricsDocumentationEnabled(final boolean metricsDocumentationEnabled) {
        throwIfAlreadyUsed();
        this.metricsDocumentationEnabled = metricsDocumentationEnabled;
        return this;
    }

    /**
     * Provide an event hasher in place of the platform's default event hasher.
     *
     * @param eventHasher the event hasher to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withEventHasher(@NonNull final EventHasher eventHasher) {
        throwIfAlreadyUsed();
        if (this.eventHasher != null) {
            throw new IllegalStateException("Event hasher has already been set");
        }
        this.eventHasher = Objects.requireNonNull(eventHasher);
        return this;
    }

    /**
     * Build the event hasher if it has not yet been built. If one has been provided via
     * {@link #withEventHasher(EventHasher)}, that hasher will be used. If this method is called more than once, only
     * the first call will build the event hasher. Otherwise, the default hasher will be created and returned.
     *
     * @return the event hasher
     */
    @NonNull
    public EventHasher buildEventHasher() {
        if (eventHasher == null) {
            eventHasher = new DefaultEventHasher();
        }
        return eventHasher;
    }

    /**
     * Provide an internal event validator in place of the platform's default internal event validator.
     *
     * @param internalEventValidator the internal event validator to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withInternalEventValidator(
            @NonNull final InternalEventValidator internalEventValidator) {
        throwIfAlreadyUsed();
        if (this.internalEventValidator != null) {
            throw new IllegalStateException("Internal event validator has already been set");
        }
        this.internalEventValidator = Objects.requireNonNull(internalEventValidator);
        return this;
    }

    /**
     * Build the internal event validator if it has not yet been built. If one has been provided via
     * {@link #withInternalEventValidator(InternalEventValidator)}, that validator will be used. If this method is
     * called more than once, only the first call will build the internal event validator. Otherwise, the default
     * validator will be created and returned.
     *
     * @return the internal event validator
     */
    @NonNull
    public InternalEventValidator buildInternalEventValidator() {
        if (internalEventValidator == null) {
            final boolean singleNodeNetwork =
                    blocks.rosterHistory().getCurrentRoster().rosterEntries().size() == 1;
            internalEventValidator = new DefaultInternalEventValidator(
                    blocks.platformContext(), singleNodeNetwork, blocks.intakeEventCounter());
        }
        return internalEventValidator;
    }

    /**
     * Provide an event deduplicator in place of the platform's default event deduplicator.
     *
     * @param eventDeduplicator the event deduplicator to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withEventDeduplicator(@NonNull final EventDeduplicator eventDeduplicator) {
        throwIfAlreadyUsed();
        if (this.eventDeduplicator != null) {
            throw new IllegalStateException("Event deduplicator has already been set");
        }
        this.eventDeduplicator = Objects.requireNonNull(eventDeduplicator);
        return this;
    }

    /**
     * Build the event deduplicator if it has not yet been built. If one has been provided via
     * {@link #withEventDeduplicator(EventDeduplicator)}, that deduplicator will be used. If this method is called more
     * than once, only the first call will build the event deduplicator. Otherwise, the default deduplicator will be
     * created and returned.
     *
     * @return the event deduplicator
     */
    @NonNull
    public EventDeduplicator buildEventDeduplicator() {
        if (eventDeduplicator == null) {
            eventDeduplicator = new StandardEventDeduplicator(blocks.platformContext(), blocks.intakeEventCounter());
        }
        return eventDeduplicator;
    }

    /**
     * Provide an event signature validator in place of the platform's default event signature validator.
     *
     * @param eventSignatureValidator the event signature validator to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withEventSignatureValidator(
            @NonNull final EventSignatureValidator eventSignatureValidator) {
        throwIfAlreadyUsed();
        if (this.eventSignatureValidator != null) {
            throw new IllegalStateException("Event signature validator has already been set");
        }
        this.eventSignatureValidator = Objects.requireNonNull(eventSignatureValidator);

        return this;
    }

    /**
     * Build the event signature validator if it has not yet been built. If one has been provided via
     * {@link #withEventSignatureValidator(EventSignatureValidator)}, that validator will be used. If this method is
     * called more than once, only the first call will build the event signature validator. Otherwise, the default
     * validator will be created and returned.
     */
    @NonNull
    public EventSignatureValidator buildEventSignatureValidator() {
        if (eventSignatureValidator == null) {
            eventSignatureValidator = new DefaultEventSignatureValidator(
                    blocks.platformContext(),
                    CryptoStatic::verifySignature,
                    blocks.rosterHistory(),
                    blocks.intakeEventCounter());
        }
        return eventSignatureValidator;
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
     * Provide a self event signer in place of the platform's default self event signer.
     *
     * @param selfEventSigner the self event signer to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withSelfEventSigner(@NonNull final SelfEventSigner selfEventSigner) {
        throwIfAlreadyUsed();
        if (this.selfEventSigner != null) {
            throw new IllegalStateException("Self event signer has already been set");
        }
        this.selfEventSigner = Objects.requireNonNull(selfEventSigner);
        return this;
    }

    /**
     * Build the self event signer if it has not yet been built. If one has been provided via
     * {@link #withSelfEventSigner(SelfEventSigner)}, that signer will be used. If this method is called more than once,
     * only the first call will build the self event signer. Otherwise, the default signer will be created and
     * returned.
     *
     * @return the self event signer
     */
    @NonNull
    public SelfEventSigner buildSelfEventSigner() {
        if (selfEventSigner == null) {
            selfEventSigner = new DefaultSelfEventSigner(blocks.keysAndCerts());
        }
        return selfEventSigner;
    }

    /**
     * Build the orphan buffer if it has not yet been built. If one has been provided via
     * {@link #withOrphanBuffer(OrphanBuffer)}, that orphan buffer will be used. If this method is called more than
     * once, only the first call will build the orphan buffer. Otherwise, the default orphan buffer will be created and
     * returned.
     *
     * @return the orphan buffer
     */
    @NonNull
    public OrphanBuffer buildOrphanBuffer() {
        if (orphanBuffer == null) {
            orphanBuffer = new DefaultOrphanBuffer(
                    blocks.platformContext().getConfiguration(),
                    blocks.platformContext().getMetrics(),
                    blocks.intakeEventCounter());
        }
        return orphanBuffer;
    }

    /**
     * Provide an orphan buffer in place of the platform's default orphan buffer.
     *
     * @param orphanBuffer the orphan buffer to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withOrphanBuffer(@NonNull final OrphanBuffer orphanBuffer) {
        throwIfAlreadyUsed();
        if (this.orphanBuffer != null) {
            throw new IllegalStateException("Orphan buffer has already been set");
        }
        this.orphanBuffer = Objects.requireNonNull(orphanBuffer);

        return this;
    }

    /**
     * Provide an event creation manager in place of the platform's default event creation manager.
     *
     * @param eventCreationManager the event creation manager to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withEventCreationManager(@NonNull final EventCreationManager eventCreationManager) {
        throwIfAlreadyUsed();
        if (this.eventCreationManager != null) {
            throw new IllegalStateException("Event creation manager has already been set");
        }
        this.eventCreationManager = Objects.requireNonNull(eventCreationManager);
        return this;
    }

    /**
     * Build the event creation manager if it has not yet been built. If one has been provided via
     * {@link #withEventCreationManager(EventCreationManager)}, that manager will be used. If this method is called more
     * than once, only the first call will build the event creation manager. Otherwise, the default manager will be
     * created and returned.
     *
     * @return the event creation manager
     */
    @NonNull
    public EventCreationManager buildEventCreationManager() {
        if (eventCreationManager == null) {
            final EventCreator eventCreator = new TipsetEventCreator(
                    blocks.platformContext().getConfiguration(),
                    blocks.platformContext().getMetrics(),
                    blocks.platformContext().getTime(),
                    blocks.randomBuilder().buildNonCryptographicRandom(),
                    data -> new PlatformSigner(blocks.keysAndCerts()).sign(data),
                    blocks.rosterHistory().getCurrentRoster(),
                    blocks.selfId(),
                    blocks.transactionPoolNexus());

            eventCreationManager = new DefaultEventCreationManager(
                    blocks.platformContext().getConfiguration(),
                    blocks.platformContext().getMetrics(),
                    blocks.platformContext().getTime(),
                    blocks.transactionPoolNexus(),
                    eventCreator);
        }
        return eventCreationManager;
    }

    /**
     * Provide a consensus engine in place of the platform's default consensus engine.
     *
     * @param consensusEngine the consensus engine to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withConsensusEngine(@NonNull final ConsensusEngine consensusEngine) {
        throwIfAlreadyUsed();
        if (this.consensusEngine != null) {
            throw new IllegalStateException("Consensus engine has already been set");
        }
        this.consensusEngine = Objects.requireNonNull(consensusEngine);
        return this;
    }

    /**
     * Build the consensus engine if it has not yet been built. If one has been provided via
     * {@link #withConsensusEngine(ConsensusEngine)}, that engine will be used. If this method is called more than once,
     * only the first call will build the consensus engine. Otherwise, the default engine will be created and returned.
     *
     * @return the consensus engine
     */
    @NonNull
    public ConsensusEngine buildConsensusEngine() {
        if (consensusEngine == null) {
            consensusEngine = new DefaultConsensusEngine(
                    blocks.platformContext(),
                    blocks.rosterHistory().getCurrentRoster(),
                    blocks.selfId(),
                    blocks.freezeCheckHolder());
        }
        return consensusEngine;
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
                            && blocks.freezeCheckHolder()
                                    .isInFreezePeriod(event.getPlatformEvent().getConsensusTimestamp()));
        }
        return consensusEventStream;
    }

    /**
     * Provide a status state machine in place of the platform's default status state machine.
     *
     * @param statusStateMachine the status state machine to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withStatusStateMachine(@NonNull final StatusStateMachine statusStateMachine) {
        throwIfAlreadyUsed();
        if (this.statusStateMachine != null) {
            throw new IllegalStateException("Status state machine has already been set");
        }
        this.statusStateMachine = Objects.requireNonNull(statusStateMachine);
        return this;
    }

    /**
     * Build the status state machine if it has not yet been built. If one has been provided via
     * {@link #withStatusStateMachine(StatusStateMachine)}, that state machine will be used. If this method is called
     * more than once, only the first call will build the status state machine. Otherwise, the default state machine
     * will be created and returned.
     *
     * @return the status state machine
     */
    @NonNull
    public StatusStateMachine buildStatusStateMachine() {
        if (statusStateMachine == null) {
            statusStateMachine = new DefaultStatusStateMachine(blocks.platformContext());
        }
        return statusStateMachine;
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
     * Provide an Inline PCES writer in place of the platform's default Inline PCES writer.
     *
     * @param inlinePcesWriter the PCES writer to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withInlinePcesWriter(@NonNull final InlinePcesWriter inlinePcesWriter) {
        throwIfAlreadyUsed();
        if (this.inlinePcesWriter != null) {
            throw new IllegalStateException("Inline PCES writer has already been set");
        }
        this.inlinePcesWriter = Objects.requireNonNull(inlinePcesWriter);
        return this;
    }

    /**
     * Build the Inline PCES writer if it has not yet been built. If one has been provided via
     * {@link #withInlinePcesWriter(InlinePcesWriter)}, that writer will be used. If this method is called more than
     * once, only the first call will build the Inline PCES writer. Otherwise, the default writer will be created and
     * returned.
     *
     * @return the Inline PCES writer
     */
    @NonNull
    public InlinePcesWriter buildInlinePcesWriter() {
        if (inlinePcesWriter == null) {
            try {
                final PcesFileManager preconsensusEventFileManager = new PcesFileManager(
                        blocks.platformContext(),
                        blocks.initialPcesFiles(),
                        PcesUtilities.getDatabaseDirectory(
                                blocks.platformContext().getConfiguration(), blocks.selfId()),
                        blocks.initialState().get().getRound());
                inlinePcesWriter = new DefaultInlinePcesWriter(
                        blocks.platformContext(), preconsensusEventFileManager, blocks.selfId());

            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return inlinePcesWriter;
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
            final long latestFreezeRound = blocks.platformStateFacade()
                    .latestFreezeRoundOf(blocks.initialState().get().getState());

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
                    blocks.platformContext(),
                    ignored -> {
                        // FUTURE WORK: Previously this lambda was needed in order to stop gossip.
                        // Now that gossip pays attention to the platform status, it will naturally
                        // halt without needing to be stopped here. This should eventually be cleaned up.
                    },
                    SystemExitUtils::handleFatalError,
                    blocks.issScratchpad());
        }
        return issHandler;
    }

    /**
     * Provide a stale event detector in place of the platform's default stale event detector.
     *
     * @param staleEventDetector the stale event detector to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withStaleEventDetector(@NonNull final StaleEventDetector staleEventDetector) {
        throwIfAlreadyUsed();
        if (this.staleEventDetector != null) {
            throw new IllegalStateException("Stale event detector has already been set");
        }
        this.staleEventDetector = Objects.requireNonNull(staleEventDetector);
        return this;
    }

    /**
     * Build the stale event detector if it has not yet been built. If one has been provided via
     * {@link #withStaleEventDetector(StaleEventDetector)}, that detector will be used. If this method is called more
     * than once, only the first call will build the stale event detector. Otherwise, the default detector will be
     * created and returned.
     *
     * @return the stale event detector
     */
    @NonNull
    public StaleEventDetector buildStaleEventDetector() {
        if (staleEventDetector == null) {
            final PlatformContext context = blocks.platformContext();
            staleEventDetector = new DefaultStaleEventDetector(context.getMetrics(), blocks.selfId());
        }
        return staleEventDetector;
    }

    /**
     * Provide a transaction resubmitter in place of the platform's default transaction resubmitter.
     *
     * @param transactionResubmitter the transaction resubmitter to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withTransactionResubmitter(
            @NonNull final TransactionResubmitter transactionResubmitter) {
        throwIfAlreadyUsed();
        if (this.transactionResubmitter != null) {
            throw new IllegalStateException("Transaction resubmitter has already been set");
        }
        this.transactionResubmitter = Objects.requireNonNull(transactionResubmitter);
        return this;
    }

    /**
     * Build the transaction resubmitter if it has not yet been built. If one has been provided via
     * {@link #withTransactionResubmitter(TransactionResubmitter)}, that resubmitter will be used. If this method is
     * called more than once, only the first call will build the transaction resubmitter. Otherwise, the default
     * resubmitter will be created and returned.
     *
     * @return the transaction resubmitter
     */
    @NonNull
    public TransactionResubmitter buildTransactionResubmitter() {
        if (transactionResubmitter == null) {
            transactionResubmitter = new DefaultTransactionResubmitter(blocks.platformContext());
        }
        return transactionResubmitter;
    }

    /**
     * Provide a transaction pool in place of the platform's default transaction pool.
     *
     * @param transactionPool the transaction pool to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withTransactionPool(@NonNull final TransactionPool transactionPool) {
        throwIfAlreadyUsed();
        if (this.transactionPool != null) {
            throw new IllegalStateException("Transaction pool has already been set");
        }
        this.transactionPool = Objects.requireNonNull(transactionPool);
        return this;
    }

    /**
     * Build the transaction pool if it has not yet been built. If one has been provided via
     * {@link #withTransactionPool(TransactionPool)}, that pool will be used. If this method is called more than once,
     * only the first call will build the transaction pool. Otherwise, the default pool will be created and returned.
     *
     * @return the transaction pool
     */
    @NonNull
    public TransactionPool buildTransactionPool() {
        if (transactionPool == null) {
            transactionPool = new DefaultTransactionPool(blocks.transactionPoolNexus());
        }
        return transactionPool;
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
            gossip = new SyncGossipModular(
                    blocks.platformContext(),
                    AdHocThreadManager.getStaticThreadManager(),
                    blocks.keysAndCerts(),
                    blocks.rosterHistory().getCurrentRoster(),
                    blocks.selfId(),
                    blocks.appVersion(),
                    blocks.swirldStateManager(),
                    () -> blocks.getLatestCompleteStateReference().get().get(),
                    x -> blocks.statusActionSubmitterReference().get().submitStatusAction(x),
                    state -> blocks.loadReconnectStateReference().get().accept(state),
                    () -> blocks.clearAllPipelinesForReconnectReference().get().run(),
                    blocks.intakeEventCounter(),
                    blocks.platformStateFacade());
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
                    blocks.platformStateFacade());
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
            hashLogger = new DefaultHashLogger(blocks.platformContext(), blocks.platformStateFacade());
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
                    blocks.swirldStateManager(),
                    blocks.statusActionSubmitterReference().get(),
                    blocks.appVersion(),
                    blocks.platformStateFacade());
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

    /**
     * Provide a future event buffer in place of the platform's default future event buffer.
     *
     * @param futureEventBuffer the future event buffer to use
     * @return this builder
     */
    @NonNull
    public PlatformComponentBuilder withFutureEventBuffer(@NonNull final FutureEventBuffer futureEventBuffer) {
        throwIfAlreadyUsed();
        if (this.futureEventBuffer != null) {
            throw new IllegalStateException("Future event buffer has already been set");
        }
        this.futureEventBuffer = Objects.requireNonNull(futureEventBuffer);
        return this;
    }
}
