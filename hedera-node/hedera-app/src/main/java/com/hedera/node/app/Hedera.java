// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_BLOCK_STREAM_INFO;
import static com.hedera.hapi.node.base.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PLATFORM_NOT_ACTIVE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNKNOWN;
import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.hapi.util.HapiUtils.functionOf;
import static com.hedera.node.app.blocks.BlockStreamManager.ZERO_BLOCK_HASH;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.combine;
import static com.hedera.node.app.blocks.impl.BlockStreamManagerImpl.NULL_HASH;
import static com.hedera.node.app.blocks.impl.ConcurrentStreamingTreeHasher.rootHashFrom;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.blockHashByBlockNumber;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.nodeSignedTxWith;
import static com.hedera.node.app.util.HederaAsciiArt.HEDERA;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static com.swirlds.platform.state.service.PlatformStateService.PLATFORM_STATE_SERVICE;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID;
import static com.swirlds.platform.system.InitTrigger.GENESIS;
import static com.swirlds.platform.system.InitTrigger.RECONNECT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.STARTING_UP;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.blocks.InitialStateHash;
import com.hedera.node.app.blocks.StreamingTreeHasher;
import com.hedera.node.app.blocks.impl.BlockStreamManagerImpl;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.ImmediateStateChangeListener;
import com.hedera.node.app.blocks.impl.streaming.NoBlockNodesAvailableException;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fees.FeeService;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.impl.ReadableHintsStoreImpl;
import com.hedera.node.app.hints.impl.WritableHintsStoreImpl;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.impl.ReadableHistoryStoreImpl;
import com.hedera.node.app.history.impl.WritableHistoryStoreImpl;
import com.hedera.node.app.ids.AppEntityIdFactory;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.ReadableEntityIdStoreImpl;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.info.CurrentPlatformStatusImpl;
import com.hedera.node.app.info.StateNetworkInfo;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.roster.RosterService;
import com.hedera.node.app.service.addressbook.impl.AddressBookServiceImpl;
import com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl;
import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.networkadmin.impl.FreezeServiceImpl;
import com.hedera.node.app.service.networkadmin.impl.NetworkServiceImpl;
import com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import com.hedera.node.app.service.util.impl.UtilServiceImpl;
import com.hedera.node.app.services.AppContextImpl;
import com.hedera.node.app.services.ServiceMigrator;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.services.StartupNetworks;
import com.hedera.node.app.signature.AppSignatureVerifier;
import com.hedera.node.app.signature.impl.SignatureExpanderImpl;
import com.hedera.node.app.signature.impl.SignatureVerifierImpl;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.state.ConsensusStateEventHandlerImpl;
import com.hedera.node.app.state.recordcache.RecordCacheService;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.throttle.AppThrottleFactory;
import com.hedera.node.app.throttle.CongestionThrottleService;
import com.hedera.node.app.throttle.ThrottleAccumulator;
import com.hedera.node.app.tss.TssBaseServiceImpl;
import com.hedera.node.app.workflows.handle.HandleWorkflow;
import com.hedera.node.app.workflows.ingest.IngestWorkflow;
import com.hedera.node.app.workflows.query.QueryWorkflow;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.Utils;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.node.config.types.StreamMode;
import com.hedera.node.internal.network.Network;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.listeners.ReconnectCompleteListener;
import com.swirlds.platform.listeners.ReconnectCompleteNotification;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteListener;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.ReadablePlatformStateStore;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SwirldMain;
import com.swirlds.platform.system.state.notifications.AsyncFatalIssListener;
import com.swirlds.platform.system.state.notifications.StateHashedListener;
import com.swirlds.state.State;
import com.swirlds.state.StateChangeListener;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.RuntimeConstructable;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.TimestampedTransaction;
import org.hiero.consensus.model.transaction.Transaction;
import org.hiero.consensus.roster.ReadableRosterStore;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.consensus.transaction.TransactionLimits;
import org.hiero.consensus.transaction.TransactionPoolNexus;

/*
 ****************        ****************************************************************************************
 ************                ************                                                                       *
 *********                      *********                                                                       *
 ******                            ******                                                                       *
 ****                                ****      ___           ___           ___           ___           ___      *
 ***        ĦĦĦĦ          ĦĦĦĦ        ***     /\  \         /\  \         /\  \         /\  \         /\  \     *
 **         ĦĦĦĦ          ĦĦĦĦ         **    /::\  \       /::\  \       /::\  \       /::\  \       /::\  \    *
 *          ĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦ          *   /:/\:\  \     /:/\:\  \     /:/\:\  \     /:/\:\  \     /:/\:\  \   *
            ĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦ             /::\~\:\  \   /:/  \:\__\   /::\~\:\  \   /::\~\:\  \   /::\~\:\  \  *
            ĦĦĦĦ          ĦĦĦĦ            /:/\:\ \:\__\ /:/__/ \:|__| /:/\:\ \:\__\ /:/\:\ \:\__\ /:/\:\ \:\__\ *
            ĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦ            \:\~\:\ \/__/ \:\  \ /:/  / \:\~\:\ \/__/ \/_|::\/:/  / \/__\:\/:/  / *
 *          ĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦ          *  \:\ \:\__\    \:\  /:/  /   \:\ \:\__\      |:|::/  /       \::/  /  *
 **         ĦĦĦĦ          ĦĦĦĦ         **   \:\ \/__/     \:\/:/  /     \:\ \/__/      |:|\/__/        /:/  /   *
 ***        ĦĦĦĦ          ĦĦĦĦ        ***    \:\__\        \::/__/       \:\__\        |:|  |         /:/  /    *
 ****                                ****     \/__/         ~~            \/__/         \|__|         \/__/     *
 ******                            ******                                                                       *
 *********                      *********                                                                       *
 ************                ************                                                                       *
 ****************        ****************************************************************************************
*/

/**
 * Represents the Hedera Consensus Node.
 *
 * <p>This is the main entry point for the Hedera Consensus Node. It contains initialization logic for the node,
 * including its state. It constructs the Dagger dependency tree, and manages the gRPC server, and in all other ways,
 * controls execution of the node. If you want to understand our system, this is a great place to start!
 */
public final class Hedera implements SwirldMain<MerkleNodeState>, AppContext.Gossip {

    private static final Logger logger = LogManager.getLogger(Hedera.class);

    private static final java.time.Duration SHUTDOWN_TIMEOUT = java.time.Duration.ofSeconds(10);

    /**
     * The application name from the platform's perspective. This is currently locked in at the old main class name and
     * requires data migration to change.
     */
    public static final String APP_NAME = "com.hedera.services.ServicesMain";

    /**
     * The swirld name. Currently, there is only one swirld.
     */
    public static final String SWIRLD_NAME = "123";
    /**
     * The registry to use.
     */
    private final ServicesRegistry servicesRegistry;
    /**
     * The services migrator to use.
     */
    private final ServiceMigrator serviceMigrator;
    /**
     * The current version of the software; it is not possible for a node's version to change
     * without restarting the process, so final.
     */
    private final SemanticVersion version;
    /**
     * The current version of the HAPI protobufs.
     */
    private final SemanticVersion hapiVersion;

    /**
     * The application context for the node.
     */
    private final AppContext appContext;

    /**
     * The contract service singleton, kept as a field here to avoid constructing twice
     * (once in constructor to register schemas, again inside Dagger component).
     */
    private final ContractServiceImpl contractServiceImpl;

    /**
     * The schedule service singleton, kept as a field here to avoid constructing twice
     * (once in constructor to register schemas, again inside Dagger component).
     */
    private final ScheduleServiceImpl scheduleServiceImpl;

    /**
     * The hinTS service singleton, kept as a field here to avoid constructing twice
     * (once in constructor to register schemas, again inside Dagger component).
     */
    private final HintsService hintsService;

    /**
     * The history service singleton, kept as a field here to avoid constructing twice
     * (once in constructor to register schemas, again inside Dagger component).
     */
    private final HistoryService historyService;

    /**
     * The util service singleton, kept as a field here to avoid constructing twice
     * (once in constructor to register schemas, again inside Dagger component).
     */
    private final UtilServiceImpl utilServiceImpl;

    /**
     * The file service singleton, kept as a field here to avoid constructing twice
     * (once in constructor to register schemas, again inside Dagger component).
     */
    private final FileServiceImpl fileServiceImpl;

    /**
     * The block stream service singleton, kept as a field here to reuse information learned
     * during the state migration phase in the later initialization phase.
     */
    private final BlockStreamService blockStreamService;

    /**
     * The platform state facade singleton, kept as a field here to avoid constructing twice`
     * (once in constructor to register schemas, again inside Dagger component).
     */
    private final PlatformStateFacade platformStateFacade;

    /**
     * The block hash signer factory.
     */
    private final BlockHashSignerFactory blockHashSignerFactory;

    /**
     * The bootstrap configuration provider for the network.
     */
    private final BootstrapConfigProviderImpl bootstrapConfigProvider;

    /**
     * The stream mode the node is operating in.
     */
    private final StreamMode streamMode;

    /**
     * The factory for the startup networks.
     */
    private final StartupNetworksFactory startupNetworksFactory;

    private final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler;

    /** Transaction size limits */
    private final TransactionLimits transactionLimits;
    /** the transaction pool, stores transactions that should be submitted to the network */
    private final TransactionPoolNexus transactionPool;

    /**
     * The Hashgraph Platform. This is set during state initialization.
     */
    private Platform platform;
    /**
     * The current status of the platform.
     */
    private PlatformStatus platformStatus = STARTING_UP;
    /**
     * The configuration for this node; non-final because its sources depend on whether
     * we are initializing the first consensus state from genesis or a saved state.
     */
    private ConfigProviderImpl configProvider;
    /**
     * DI for all objects needed to implement Hedera node lifecycles; non-final because
     * it is completely recreated every time the platform initializes a new state as the
     * basis for applying consensus transactions.
     */
    private HederaInjectionComponent daggerApp;

    /**
     * When initializing the State API, the state being initialized.
     */
    @Nullable
    private MerkleNodeState initState;

    /**
     * The metrics object being used for reporting.
     */
    private final Metrics metrics;

    /**
     * A {@link StateChangeListener} that accumulates state changes that are only reported once per block; in the
     * current system, these are the singleton and queue updates. Every {@link MerkleNodeState} will have this
     * listener registered.
     */
    private final BoundaryStateChangeListener boundaryStateChangeListener;

    /**
     * A {@link StateChangeListener} that accumulates k/v and queue state changes that must be immediately reported as they occur,
     * because the exact order of mutations---not just the final values---determines the Merkle root hash.
     */
    private final ImmediateStateChangeListener immediateStateChangeListener = new ImmediateStateChangeListener();

    /**
     * The state root supplier to use for creating a new state root.
     */
    private final Supplier<MerkleNodeState> stateRootSupplier;

    /**
     * The action to take, if any, when a consensus round is sealed.
     */
    private final BiPredicate<Round, State> onSealConsensusRound;
    /**
     * Once set, a future that resolves to the hash of the state used to initialize the application. This is known
     * immediately at genesis or on restart from a saved state; during reconnect, it is known when reconnect
     * completes. Used to inject the start-of-state hash to the {@link BlockStreamManagerImpl}.
     */
    @Nullable
    private CompletableFuture<Bytes> initialStateHashFuture;

    @Nullable
    private List<StateChanges.Builder> migrationStateChanges;

    @Nullable
    private StartupNetworks startupNetworks;

    @Nullable
    private Supplier<Network> genesisNetworkSupplier;

    @NonNull
    private StoreMetricsServiceImpl storeMetricsService;

    private boolean onceOnlyServiceInitializationPostDaggerHasHappened = false;

    @FunctionalInterface
    public interface StartupNetworksFactory {
        @NonNull
        StartupNetworks apply(@NonNull ConfigProvider configProvider);
    }

    @FunctionalInterface
    public interface HintsServiceFactory {
        @NonNull
        HintsService apply(@NonNull AppContext appContext, @NonNull Configuration bootstrapConfig);
    }

    @FunctionalInterface
    public interface HistoryServiceFactory {
        @NonNull
        HistoryService apply(@NonNull AppContext appContext, @NonNull Configuration bootstrapConfig);
    }

    @FunctionalInterface
    public interface BlockHashSignerFactory {
        @NonNull
        BlockHashSigner apply(
                @NonNull HintsService hintsService,
                @NonNull HistoryService historyService,
                @NonNull ConfigProvider configProvider);
    }

    /*==================================================================================================================
    *
    * Hedera Object Construction.
    *
    =================================================================================================================*/

    /**
     * Creates a Hedera node and registers its own and its services' {@link RuntimeConstructable} factories
     * with the given {@link ConstructableRegistry}.
     *
     * <p>This registration is a critical side effect that must happen called before any Platform initialization
     * steps that try to create or deserialize a {@link MerkleNodeState}.
     *
     * @param constructableRegistry the registry to register {@link RuntimeConstructable} factories with
     * @param registryFactory the factory to use for creating the services registry
     * @param migrator the migrator to use with the services
     * @param startupNetworksFactory the factory for the startup networks
     * @param hintsServiceFactory the factory for the hinTS service
     * @param historyServiceFactory the factory for the history service
     * @param blockHashSignerFactory the factory for the block hash signer
     * @param metrics the metrics object to use for reporting
     * @param platformStateFacade the facade object to access platform state
     * @param baseSupplier the base supplier to create a new state with
     */
    public Hedera(
            @NonNull final ConstructableRegistry constructableRegistry,
            @NonNull final ServicesRegistry.Factory registryFactory,
            @NonNull final ServiceMigrator migrator,
            @NonNull final InstantSource instantSource,
            @NonNull final StartupNetworksFactory startupNetworksFactory,
            @NonNull final HintsServiceFactory hintsServiceFactory,
            @NonNull final HistoryServiceFactory historyServiceFactory,
            @NonNull final BlockHashSignerFactory blockHashSignerFactory,
            @NonNull final Metrics metrics,
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final Supplier<MerkleNodeState> baseSupplier) {
        requireNonNull(registryFactory);
        requireNonNull(constructableRegistry);
        requireNonNull(hintsServiceFactory);
        requireNonNull(historyServiceFactory);
        this.metrics = requireNonNull(metrics);
        this.serviceMigrator = requireNonNull(migrator);
        this.startupNetworksFactory = requireNonNull(startupNetworksFactory);
        this.blockHashSignerFactory = requireNonNull(blockHashSignerFactory);
        this.storeMetricsService = new StoreMetricsServiceImpl(metrics);
        this.platformStateFacade = requireNonNull(platformStateFacade);
        logger.info(
                """

                        {}

                        Welcome to Hedera! Developed with ❤\uFE0F by the Open Source Community.
                        https://github.com/hashgraph/hedera-services

                        """,
                HEDERA);
        bootstrapConfigProvider = new BootstrapConfigProviderImpl();
        final var bootstrapConfig = bootstrapConfigProvider.getConfiguration();
        hapiVersion = bootstrapConfig.getConfigData(VersionConfig.class).hapiVersion();
        final var versionConfig = bootstrapConfig.getConfigData(VersionConfig.class);
        version = versionConfig
                .servicesVersion()
                .copyBuilder()
                .build("" + bootstrapConfig.getConfigData(HederaConfig.class).configVersion())
                .build();
        streamMode = bootstrapConfig.getConfigData(BlockStreamConfig.class).streamMode();
        servicesRegistry = registryFactory.create(constructableRegistry, bootstrapConfig);
        logger.info(
                "Creating Hedera Consensus Node {} with HAPI {}",
                () -> HapiUtils.toString(version),
                () -> HapiUtils.toString(hapiVersion));
        fileServiceImpl = new FileServiceImpl();

        final Supplier<Configuration> configSupplier = () -> configProvider().getConfiguration();
        this.appContext = new AppContextImpl(
                instantSource,
                new AppSignatureVerifier(
                        bootstrapConfig.getConfigData(HederaConfig.class),
                        new SignatureExpanderImpl(),
                        new SignatureVerifierImpl()),
                this,
                configSupplier,
                () -> daggerApp.networkInfo().selfNodeInfo(),
                () -> this.metrics,
                new AppThrottleFactory(
                        configSupplier,
                        () -> daggerApp.workingStateAccessor().getState(),
                        () -> daggerApp.throttleServiceManager().activeThrottleDefinitionsOrThrow(),
                        ThrottleAccumulator::new),
                () -> daggerApp.appFeeCharging(),
                new AppEntityIdFactory(bootstrapConfig));
        boundaryStateChangeListener = new BoundaryStateChangeListener(storeMetricsService, configSupplier);
        hintsService = hintsServiceFactory.apply(appContext, bootstrapConfig);
        historyService = historyServiceFactory.apply(appContext, bootstrapConfig);
        utilServiceImpl = new UtilServiceImpl(appContext, (txnBytes, config) -> daggerApp
                .transactionChecker()
                .parseSignedAndCheck(
                        txnBytes, config.getConfigData(HederaConfig.class).transactionMaxBytes())
                .txBody());
        contractServiceImpl = new ContractServiceImpl(appContext, metrics);
        scheduleServiceImpl = new ScheduleServiceImpl(appContext);
        blockStreamService = new BlockStreamService();
        transactionLimits = new TransactionLimits(
                bootstrapConfig.getConfigData(HederaConfig.class).nodeTransactionMaxBytes(),
                bootstrapConfig.getConfigData(HederaConfig.class).maxTransactionBytesPerEvent());
        transactionPool = new TransactionPoolNexus(
                transactionLimits,
                bootstrapConfig.getConfigData(HederaConfig.class).throttleTransactionQueueSize(),
                metrics,
                instantSource);

        // Register all service schema RuntimeConstructable factories before platform init
        Set.of(
                        new EntityIdService(),
                        new ConsensusServiceImpl(),
                        contractServiceImpl,
                        fileServiceImpl,
                        hintsService,
                        historyService,
                        new TssBaseServiceImpl(),
                        new FreezeServiceImpl(),
                        scheduleServiceImpl,
                        new TokenServiceImpl(appContext),
                        utilServiceImpl,
                        new RecordCacheService(),
                        new BlockRecordService(),
                        blockStreamService,
                        new FeeService(),
                        new CongestionThrottleService(),
                        new NetworkServiceImpl(),
                        new AddressBookServiceImpl(),
                        new RosterService(
                                this::canAdoptRoster,
                                this::onAdoptRoster,
                                () -> requireNonNull(initState),
                                platformStateFacade),
                        PLATFORM_STATE_SERVICE)
                .forEach(servicesRegistry::register);
        consensusStateEventHandler = new ConsensusStateEventHandlerImpl(this);
        final var blockStreamsEnabled = isBlockStreamEnabled();
        stateRootSupplier = blockStreamsEnabled ? () -> withListeners(baseSupplier.get()) : baseSupplier;
        onSealConsensusRound = blockStreamsEnabled ? this::manageBlockEndRound : (round, state) -> true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SemanticVersion getSemanticVersion() {
        return version;
    }

    /*==================================================================================================================
    *
    * Initialization Step 1: Create a new state (either genesis or restart, once per node).
    *
    =================================================================================================================*/

    /**
     * {@inheritDoc}
     *
     * <p>Called by the platform to build a genesis state.
     *
     * @return a Services state object
     */
    @Override
    @NonNull
    public MerkleNodeState newStateRoot() {
        return stateRootSupplier.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Function<VirtualMap, MerkleNodeState> stateRootFromVirtualMap() {
        return HederaVirtualMapState::new;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ConsensusStateEventHandler<MerkleNodeState> newConsensusStateEvenHandler() {
        return consensusStateEventHandler;
    }

    @Override
    public void reportUnhealthyDuration(@NonNull final java.time.Duration duration) {
        transactionPool.reportUnhealthyDuration(duration);
    }

    @Override
    public void newPlatformStatus(@NonNull final PlatformStatus platformStatus) {
        this.platformStatus = platformStatus;
        transactionPool.updatePlatformStatus(platformStatus);
        logger.info("HederaNode#{} is {}", platform.getSelfId(), platformStatus.name());
        final var streamToBlockNodes = configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .streamToBlockNodes();
        switch (platformStatus) {
            case ACTIVE -> {
                startGrpcServer();
                if (initState != null) {
                    // Disabling start up mode, so since now singletons will be commited only on block close
                    if (initState instanceof VirtualMapState<?> virtualMapState) {
                        virtualMapState.disableStartupMode();
                    }
                }
            }
            case FREEZE_COMPLETE -> {
                logger.info("Platform status is now FREEZE_COMPLETE");
                shutdownGrpcServer();
                closeRecordStreams();
                if (streamToBlockNodes && isNotEmbedded()) {
                    logger.info("FREEZE_COMPLETE - Shutting down connections to Block Nodes");
                    daggerApp.blockNodeConnectionManager().shutdown();
                }
            }
            case CATASTROPHIC_FAILURE -> {
                logger.error("Platform status is now CATASTROPHIC_FAILURE");
                shutdownGrpcServer();
                if (streamToBlockNodes && isNotEmbedded()) {
                    logger.info("CATASTROPHIC_FAILURE - Shutting down connections to Block Nodes");
                    daggerApp.blockNodeConnectionManager().shutdown();
                }

                // Wait for the block stream to close any pending or current blocks–-we may need them for triage
                blockStreamManager().awaitFatalShutdown(SHUTDOWN_TIMEOUT);
            }
            case REPLAYING_EVENTS, STARTING_UP, OBSERVING, RECONNECT_COMPLETE, CHECKING, FREEZING, BEHIND -> {
                // Nothing to do here, just enumerate for completeness
            }
        }
    }

    /*==================================================================================================================
    *
    * Initialization Step 2: Initialize the state. Either genesis or restart or reconnect or some other trigger.
    * Includes migration when needed.
    *
    =================================================================================================================*/

    /**
     * Initializes the States API in the given state based on the given startup conditions.
     *
     * @param state the state to initialize
     * @param trigger the trigger that is calling migration
     * @param platformConfig the platform configuration
     */
    public void initializeStatesApi(
            @NonNull final MerkleNodeState state,
            @NonNull final InitTrigger trigger,
            @NonNull final Configuration platformConfig) {
        requireNonNull(state);
        requireNonNull(platformConfig);
        this.configProvider = new ConfigProviderImpl(trigger == GENESIS, metrics);
        this.genesisNetworkSupplier = () -> startupNetworks().genesisNetworkOrThrow(platformConfig);
        final var deserializedVersion = platformStateFacade.creationSemanticVersionOf(state);
        logger.info(
                "Initializing Hedera state version {} in {} mode with trigger {} and previous version {}",
                version,
                configProvider
                        .getConfiguration()
                        .getConfigData(HederaConfig.class)
                        .activeProfile(),
                trigger,
                deserializedVersion == null ? "<NONE>" : deserializedVersion);
        if (trigger != GENESIS) {
            requireNonNull(deserializedVersion, "Deserialized version cannot be null for trigger " + trigger);
            withListeners(state);
        }
        if (SEMANTIC_VERSION_COMPARATOR.compare(version, deserializedVersion) < 0) {
            logger.fatal(
                    "Fatal error, state source version {} is higher than node software version {}",
                    deserializedVersion,
                    version);
            throw new IllegalStateException("Cannot downgrade from " + deserializedVersion + " to " + version);
        }
        try {
            migrateSchemas(state, deserializedVersion, trigger, platformConfig);
            logConfiguration();
        } catch (final Throwable t) {
            logger.fatal("Critical failure during schema migration", t);
            throw new IllegalStateException("Critical failure during migration", t);
        }
        logger.info(
                "Platform state includes freeze time={} and last frozen={}",
                platformStateFacade.freezeTimeOf(state),
                platformStateFacade.lastFrozenTimeOf(state));
    }

    /**
     * Invoked by the platform when the state should be initialized. This happens <b>BEFORE</b>
     * {@link SwirldMain#init(Platform, NodeId)} and after {@link SwirldMain#newStateRoot()}.
     */
    @SuppressWarnings("java:S1181") // catching Throwable instead of Exception when we do a direct System.exit()
    public void onStateInitialized(
            @NonNull final MerkleNodeState state,
            @NonNull final Platform platform,
            @NonNull final InitTrigger trigger) {
        // A Hedera object can receive multiple onStateInitialized() calls throughout its lifetime if
        // the platform needs to initialize a learned state after reconnect; however, it cannot be
        // used by multiple platform instances
        if (this.platform != null && this.platform != platform) {
            logger.fatal("Fatal error, platform should never change once set");
            throw new IllegalStateException("Platform should never change once set");
        }
        this.platform = requireNonNull(platform);
        if (state.getReadableStates(EntityIdService.NAME).isEmpty()) {
            initializeStatesApi(state, trigger, platform.getContext().getConfiguration());
        }
        // With the States API grounded in the working state, we can create the object graph from it
        initializeDagger(state, trigger);

        // Perform any service initialization that has to be postponed until Dagger is available
        // (simple boolean is usable since we're still single-threaded when `onStateInitialized` is called)
        if (!onceOnlyServiceInitializationPostDaggerHasHappened) {
            contractServiceImpl.createMetrics();
            onceOnlyServiceInitializationPostDaggerHasHappened = true;
            try {
                // Verify the native libraries
                contractServiceImpl.nativeLibVerifier().verifyNativeLibs();
            } catch (final IllegalStateException e) {
                // This block will be invoked only if the contracts.evm.nativeLibVerification.halt is enabled
                // We should be shutting down the node if the verification fails
                // to ensure that no unhandled exceptions from the native libs are thrown
                // so that we prevent possibly catastrophic failures.
                shutdown();
                System.exit(1);
            }
        }
    }

    /**
     * Called by this class when we detect it is time to do migration. The {@code deserializedVersion} must not be newer
     * than the current software version. If it is prior to the current version, then each migration between the
     * {@code deserializedVersion} and the current version, including the current version, will be executed, thus
     * bringing the state up to date.
     *
     * <p>If the {@code deserializedVersion} is {@code null}, then this is the first time the node has been started,
     * and thus all schemas will be executed.
     *
     * @param state current state
     * @param deserializedVersion version deserialized
     * @param trigger trigger that is calling migration
     * @param platformConfig platform configuration
     */
    private void migrateSchemas(
            @NonNull final MerkleNodeState state,
            @Nullable final SemanticVersion deserializedVersion,
            @NonNull final InitTrigger trigger,
            @NonNull final Configuration platformConfig) {
        final var isUpgrade = SEMANTIC_VERSION_COMPARATOR.compare(version, deserializedVersion) > 0;
        logger.info(
                "{} from Services version {} @ current {} with trigger {}",
                () -> isUpgrade ? "Upgrading" : (deserializedVersion == null ? "Starting" : "Restarting"),
                () -> HapiUtils.toString(deserializedVersion),
                () -> HapiUtils.toString(version),
                () -> trigger);
        blockStreamService.resetMigratedLastBlockHash();
        startupNetworks = startupNetworksFactory.apply(configProvider);
        PLATFORM_STATE_SERVICE.setAppVersionFn(
                config -> platformConfig.getConfigData(VersionConfig.class).servicesVersion());
        this.initState = state;
        final var migrationChanges = serviceMigrator.doMigrations(
                state,
                servicesRegistry,
                deserializedVersion,
                version,
                // (FUTURE) In principle, the FileService could change the active configuration during a
                // migration, implying we should pass a config provider; but we don't need this yet
                configProvider.getConfiguration(),
                platformConfig,
                startupNetworks,
                storeMetricsService,
                configProvider,
                platformStateFacade);
        this.initState = null;
        migrationStateChanges = new ArrayList<>(migrationChanges);
        immediateStateChangeListener.reset(null);
        boundaryStateChangeListener.reset();
        // If still using BlockRecordManager state, then for specifically a non-genesis upgrade,
        // set in state that post-upgrade work is pending
        if (streamMode != BLOCKS && isUpgrade && trigger != RECONNECT && trigger != GENESIS) {
            unmarkMigrationRecordsStreamed(state);
            migrationStateChanges.add(
                    StateChanges.newBuilder().stateChanges(boundaryStateChangeListener.allStateChanges()));
            boundaryStateChangeListener.reset();
        }
        logger.info("Migration complete");
    }

    /*==================================================================================================================
    *
    * Initialization Step 3: Initialize the app. Happens once at startup.
    *
    =================================================================================================================*/

    /**
     * {@inheritDoc}
     *
     * <p>Called <b>AFTER</b> init and migrate have been called on the state (either the new state created from
     * {@link SwirldMain#newStateRoot()} or an instance of {@link MerkleNodeState} created by the platform and
     * loaded from the saved state).
     *
     * <p>(FUTURE) Consider moving this initialization into {@link #onStateInitialized(MerkleNodeState, Platform, InitTrigger)}
     * instead, as there is no special significance to having it here instead.
     */
    @SuppressWarnings("java:S1181") // catching Throwable instead of Exception when we do a direct System.exit()
    @Override
    public void init(@NonNull final Platform platform, @NonNull final NodeId nodeId) {
        if (this.platform != platform) {
            throw new IllegalArgumentException("Platform must be the same instance");
        }
        assertEnvSanityChecks(nodeId);
        logger.info("Initializing Hedera app with HederaNode#{}", nodeId);
        Locale.setDefault(Locale.US);
        logger.info("Locale to set to US en");
    }

    @Override
    public void submit(@NonNull final TransactionBody body) {
        requireNonNull(body);
        if (platformStatus != ACTIVE) {
            throw new IllegalStateException("" + PLATFORM_NOT_ACTIVE);
        }
        final HederaFunctionality function;
        try {
            function = functionOf(body);
        } catch (UnknownHederaFunctionality e) {
            throw new IllegalArgumentException("" + UNKNOWN);
        }
        try {
            final var config = configProvider.getConfiguration();
            final var adminConfig = config.getConfigData(NetworkAdminConfig.class);
            final var allowList = adminConfig.nodeTransactionsAllowList().functionalitySet();
            if (!allowList.contains(function)) {
                throw new IllegalArgumentException("" + NOT_SUPPORTED);
            }
            final var payload = SignedTransaction.PROTOBUF.toBytes(nodeSignedTxWith(body));
            requireNonNull(daggerApp).submissionManager().submit(body, payload);
        } catch (PreCheckException e) {
            final var reason = e.responseCode();
            if (reason == DUPLICATE_TRANSACTION) {
                // In this case the client must not retry with the same transaction, but
                // could retry with a different transaction id if desired.
                throw new IllegalArgumentException("" + DUPLICATE_TRANSACTION);
            }
            throw new IllegalStateException("" + reason);
        }
    }

    @Override
    public Signature sign(final byte[] ledgerId) {
        return platform.sign(ledgerId);
    }

    @Override
    public boolean isAvailable() {
        return daggerApp != null && daggerApp.currentPlatformStatus().get() == ACTIVE;
    }

    /**
     * Called to perform orderly close record streams.
     */
    private void closeRecordStreams() {
        daggerApp.blockRecordManager().close();
    }

    /**
     * Gets whether the default charset is UTF-8.
     */
    private boolean isUTF8(@NonNull final Charset defaultCharset) {
        if (!UTF_8.equals(defaultCharset)) {
            logger.error("Default charset is {}, not UTF-8", defaultCharset);
            return false;
        }
        return true;
    }

    /**
     * Gets whether the sha384 digest is available
     */
    private boolean sha384DigestIsAvailable() {
        try {
            MessageDigest.getInstance("SHA-384");
            return true;
        } catch (final NoSuchAlgorithmException e) {
            logger.error(e);
            return false;
        }
    }

    /*==================================================================================================================
    *
    * Other app lifecycle methods
    *
    =================================================================================================================*/

    /**
     * {@inheritDoc}
     *
     * <p>Called by the platform after <b>ALL</b> initialization to start the gRPC servers and begin operation, or by
     * the notification listener when it is time to restart the gRPC server after it had been stopped (such as during
     * reconnect).
     */
    @Override
    public void run() {
        logger.info("Starting the Hedera node");
    }

    /**
     * Called for an orderly shutdown.
     */
    public void shutdown() {
        logger.info("Shutting down Hedera node");
        shutdownGrpcServer();

        if (daggerApp != null) {
            logger.debug("Shutting down the Block Node Connection Manager");
            daggerApp.blockNodeConnectionManager().shutdown();

            logger.debug("Shutting down the state");
            final var state = daggerApp.workingStateAccessor().getState();
            if (state instanceof HederaVirtualMapState msr) {
                msr.close();
            }

            logger.debug("Shutting down the block manager");
            daggerApp.blockRecordManager().close();
        }

        platform = null;
        daggerApp = null;
    }

    /**
     * Invoked by the platform to handle pre-consensus events. This only happens after {@link #run()} has been called.
     */
    public void onPreHandle(
            @NonNull final Event event,
            @NonNull final State state,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTxnCallback) {
        final var readableStoreFactory = new ReadableStoreFactory(state);
        final var creatorInfo =
                daggerApp.networkInfo().nodeInfo(event.getCreatorId().id());
        if (creatorInfo == null) {
            // It's normal immediately post-upgrade to still see events from a node removed from the address book
            final var platformStateStore = readableStoreFactory.getStore(ReadablePlatformStateStore.class);
            if (event.getEventCore().birthRound() > platformStateStore.getLatestFreezeRound()) {
                logger.warn(
                        "Received event with birth round {}, last freeze round is {}, from node {} "
                                + "which is not in the address book",
                        event.getEventCore().birthRound(),
                        platformStateStore.getLatestFreezeRound(),
                        event.getCreatorId());
            }
            return;
        }

        final Consumer<StateSignatureTransaction> simplifiedStateSignatureTxnCallback = txn -> {
            final var scopedTxn = new ScopedSystemTransaction<>(event.getCreatorId(), event.getBirthRound(), txn);
            stateSignatureTxnCallback.accept(scopedTxn);
        };

        final var transactions = new ArrayList<Transaction>(1000);
        event.forEachTransaction(transactions::add);
        daggerApp
                .preHandleWorkflow()
                .preHandle(
                        readableStoreFactory, creatorInfo, transactions.stream(), simplifiedStateSignatureTxnCallback);
    }

    public void onNewRecoveredState() {
        // Always close the block manager so replay will end with a complete record file
        daggerApp.blockRecordManager().close();
    }

    /**
     * Invoked by the platform to handle a round of consensus events.  This only happens after {@link #run()} has been
     * called.
     */
    public void onHandleConsensusRound(
            @NonNull final Round round,
            @NonNull final MerkleNodeState state,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTxnCallback) {
        daggerApp.workingStateAccessor().setState(state);
        daggerApp.handleWorkflow().handleRound(state, round, stateSignatureTxnCallback);
    }

    /**
     * Called by the platform after it has made all its changes to this state for the given round.
     *
     * @param round the round whose platform state changes are completed
     * @param state the state after the platform has made all its changes
     * @return true if a block has closed, signaling a safe time to sign the state without risking loss
     * of transactions in the event of an incident
     */
    public boolean onSealConsensusRound(@NonNull final Round round, @NonNull final State state) {
        requireNonNull(state);
        requireNonNull(round);
        return onSealConsensusRound.test(round, state);
    }

    /*==================================================================================================================
    *
    * gRPC Server Lifecycle
    *
    =================================================================================================================*/

    /**
     * Start the gRPC Server if it is not already running.
     */
    void startGrpcServer() {
        if (isNotEmbedded() && !daggerApp.grpcServerManager().isRunning()) {
            daggerApp.grpcServerManager().start();
        }
    }

    /**
     * Called to perform orderly shutdown of the gRPC servers.
     */
    public void shutdownGrpcServer() {
        if (isNotEmbedded()) {
            daggerApp.grpcServerManager().stop();
        }
    }

    /**
     * Called to set the starting state hash after genesis or restart.
     *
     * @param stateHash the starting state hash
     */
    public void setInitialStateHash(@NonNull final Hash stateHash) {
        requireNonNull(stateHash);
        initialStateHashFuture = completedFuture(stateHash.getBytes());
    }

    /**
     * Returns the startup networks.
     */
    public @NonNull StartupNetworks startupNetworks() {
        return requireNonNull(startupNetworks);
    }

    /*==================================================================================================================
    *
    * Exposed for use by embedded Hedera
    *
    =================================================================================================================*/
    public IngestWorkflow ingestWorkflow() {
        return daggerApp.ingestWorkflow();
    }

    public QueryWorkflow queryWorkflow() {
        return daggerApp.queryWorkflow();
    }

    public QueryWorkflow operatorQueryWorkflow() {
        return daggerApp.operatorQueryWorkflow();
    }

    public HandleWorkflow handleWorkflow() {
        return daggerApp.handleWorkflow();
    }

    public ConfigProvider configProvider() {
        return configProvider;
    }

    public BootstrapConfigProviderImpl bootstrapConfigProvider() {
        return bootstrapConfigProvider;
    }

    public BlockStreamManager blockStreamManager() {
        return daggerApp.blockStreamManager();
    }

    public ThrottleDefinitions activeThrottleDefinitions() {
        return daggerApp.throttleServiceManager().activeThrottleDefinitionsOrThrow();
    }

    public boolean isBlockStreamEnabled() {
        return streamMode != RECORDS;
    }

    public ImmediateStateChangeListener immediateStateChangeListener() {
        return immediateStateChangeListener;
    }

    public BoundaryStateChangeListener boundaryStateChangeListener() {
        return boundaryStateChangeListener;
    }

    public boolean systemEntitiesCreated() {
        return Optional.ofNullable(daggerApp.systemEntitiesCreationFlag())
                .map(AtomicBoolean::get)
                .orElse(true);
    }

    public @NonNull Supplier<Network> genesisNetworkSupplierOrThrow() {
        return requireNonNull(genesisNetworkSupplier);
    }

    public Bytes encodeSystemTransaction(@NonNull final StateSignatureTransaction stateSignatureTransaction) {
        final var nodeAccountID = appContext.selfNodeInfoSupplier().get().accountId();

        final var transactionID = TransactionID.newBuilder()
                .transactionValidStart(Timestamp.DEFAULT)
                .accountID(nodeAccountID);

        final var transactionBody = TransactionBody.newBuilder()
                .transactionID(transactionID)
                .nodeAccountID(nodeAccountID)
                .transactionValidDuration(Duration.DEFAULT)
                .stateSignatureTransaction(stateSignatureTransaction);

        final var signedTx = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(transactionBody.build()))
                .sigMap(SignatureMap.DEFAULT)
                .build();

        return SignedTransaction.PROTOBUF.toBytes(signedTx);
    }

    @Override
    public void submitStateSignature(@NonNull final StateSignatureTransaction stateSignatureTransaction) {
        transactionPool.submitPriorityTransaction(encodeSystemTransaction(stateSignatureTransaction));
    }

    @Override
    public @NonNull List<TimestampedTransaction> getTransactionsForEvent() {
        return transactionPool.getTransactionsForEvent();
    }

    @Override
    public boolean hasBufferedSignatureTransactions() {
        return transactionPool.hasBufferedSignatureTransactions();
    }

    @Override
    public @NonNull TransactionLimits getTransactionLimits() {
        return transactionLimits;
    }

    /*==================================================================================================================
    *
    * Random private helper methods
    *
    =================================================================================================================*/

    private void initializeDagger(@NonNull final State state, @NonNull final InitTrigger trigger) {
        final var notifications = platform.getNotificationEngine();
        final var blockStreamEnabled = isBlockStreamEnabled();
        // The Dagger component should be constructed every time we reach this point, even if
        // it exists (this avoids any problems with mutable singleton state by reconstructing
        // everything); but we must ensure the gRPC server in the old component is fully stopped,
        // as well as unregister listeners from the last time this method ran
        if (daggerApp != null) {
            shutdownGrpcServer();
            notifications.unregister(ReconnectCompleteListener.class, daggerApp.reconnectListener());
            notifications.unregister(StateWriteToDiskCompleteListener.class, daggerApp.stateWriteToDiskListener());
            notifications.unregister(AsyncFatalIssListener.class, daggerApp.fatalIssListener());
            if (blockStreamEnabled) {
                notifications.unregister(StateHashedListener.class, daggerApp.blockStreamManager());
                daggerApp.blockNodeConnectionManager().shutdown();
            }
        }
        if (trigger == RECONNECT) {
            // During a reconnect, we wait for reconnect to complete successfully and then set the initial hash
            // from the immutable state in the ReconnectCompleteNotification
            initialStateHashFuture = new CompletableFuture<>();
            notifications.register(ReconnectCompleteListener.class, new ReadReconnectStartingStateHash(notifications));
        }
        // For other triggers the initial state hash must have been set already
        requireNonNull(initialStateHashFuture);
        final var roundNum = requireNonNull(state.getReadableStates(PlatformStateService.NAME)
                        .<PlatformState>getSingleton(PLATFORM_STATE_STATE_ID)
                        .get())
                .consensusSnapshotOrThrow()
                .round();
        final var initialStateHash = new InitialStateHash(initialStateHashFuture, roundNum);

        final var rosterStore = new ReadableStoreFactory(state).getStore(ReadableRosterStore.class);
        final var currentRoster = requireNonNull(rosterStore.getActiveRoster());
        final var networkInfo = new StateNetworkInfo(
                platform.getSelfId().id(), state, currentRoster, configProvider, () -> requireNonNull(
                                genesisNetworkSupplier)
                        .get());
        hintsService.initCurrentRoster(currentRoster);
        final var blockHashSigner = blockHashSignerFactory.apply(hintsService, historyService, configProvider);
        // Fully qualified so as to not confuse javadoc
        daggerApp = DaggerHederaInjectionComponent.builder()
                .configProviderImpl(configProvider)
                .bootstrapConfigProviderImpl(bootstrapConfigProvider)
                .fileServiceImpl(fileServiceImpl)
                .contractServiceImpl(contractServiceImpl)
                .utilServiceImpl(utilServiceImpl)
                .scheduleService(scheduleServiceImpl)
                .initTrigger(trigger)
                .softwareVersion(version)
                .self(networkInfo.selfNodeInfo())
                .platform(platform)
                .transactionPool(transactionPool)
                .currentPlatformStatus(new CurrentPlatformStatusImpl(platform))
                .servicesRegistry(servicesRegistry)
                .instantSource(appContext.instantSource())
                .throttleFactory(appContext.throttleFactory())
                .metrics(metrics)
                .immediateStateChangeListener(immediateStateChangeListener)
                .boundaryStateChangeListener(boundaryStateChangeListener)
                .migrationStateChanges(migrationStateChanges != null ? migrationStateChanges : new ArrayList<>())
                .initialStateHash(initialStateHash)
                .networkInfo(networkInfo)
                .startupNetworks(startupNetworks)
                .hintsService(hintsService)
                .historyService(historyService)
                .blockHashSigner(blockHashSigner)
                .appContext(appContext)
                .platformStateFacade(platformStateFacade)
                .build();
        // Initialize infrastructure for fees, exchange rates, and throttles from the working state
        daggerApp.initializer().initialize(state, streamMode);
        logConfiguration();
        notifications.register(ReconnectCompleteListener.class, daggerApp.reconnectListener());
        notifications.register(StateWriteToDiskCompleteListener.class, daggerApp.stateWriteToDiskListener());
        notifications.register(AsyncFatalIssListener.class, daggerApp.fatalIssListener());
        if (blockStreamEnabled) {
            notifications.register(StateHashedListener.class, daggerApp.blockStreamManager());
            daggerApp
                    .blockStreamManager()
                    .initLastBlockHash(
                            switch (trigger) {
                                case GENESIS -> ZERO_BLOCK_HASH;
                                default ->
                                    blockStreamService
                                            .migratedLastBlockHash()
                                            .orElseGet(() -> startBlockHashFrom(state));
                            });
            migrationStateChanges = null;
            initializeBlockNodeConnections();
        }
    }

    /**
     * Given the {@link BlockStreamInfo} context from a {@link State}, infers the block hash of the
     * last block that was incorporated in this state.
     *
     * @param state the state to use
     * @return the inferred block hash
     */
    private Bytes startBlockHashFrom(@NonNull final State state) {
        final var blockStreamInfo = state.getReadableStates(BlockStreamService.NAME)
                .<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID)
                .get();
        requireNonNull(blockStreamInfo);
        // Three of the four ingredients in the block hash are directly in the BlockStreamInfo; that is,
        // the previous block hash, the input tree root hash, and the start of block state hash
        final var prevBlockHash = blockStreamInfo.blockNumber() == 0L
                ? ZERO_BLOCK_HASH
                : blockHashByBlockNumber(
                        blockStreamInfo.trailingBlockHashes(),
                        blockStreamInfo.blockNumber() - 1,
                        blockStreamInfo.blockNumber() - 1);
        requireNonNull(prevBlockHash);

        // The fourth ingredient, the state changes tree root hash, is not directly in the BlockStreamInfo, but
        // we can recompute it based on the tree hash information and the fact the last state changes item in
        // the block was devoted to putting the BlockStreamInfo itself into the state
        final var stateChangesHash = stateChangesTreeRootHashFrom(blockStreamInfo);

        final var level1A = combine(prevBlockHash, blockStreamInfo.startOfBlockStateHash());
        final var level1B = combine(blockStreamInfo.consensusHeaderTreeRootHash(), blockStreamInfo.inputTreeRootHash());
        final var level1C = combine(blockStreamInfo.outputTreeRootHash(), stateChangesHash);
        final var level1D = combine(blockStreamInfo.traceDataTreeRootHash(), NULL_HASH);
        final var leftParent = combine(level1A, level1B);
        final var rightParent = combine(level1C, level1D);
        return combine(leftParent, rightParent);
    }

    /**
     * Given a {@link BlockStreamInfo} context, computes the state changes tree root hash that must have been
     * computed at the end of the block that the context describes, assuming the final state change block item
     * was the state change that put the context into the state.
     *
     * @param info the context to use
     * @return the inferred output tree root hash
     */
    private @NonNull Bytes stateChangesTreeRootHashFrom(@NonNull final BlockStreamInfo info) {
        // This was the last state change in the block
        final var blockStreamInfoChange = StateChange.newBuilder()
                .stateId(STATE_ID_BLOCK_STREAM_INFO.protoOrdinal())
                .singletonUpdate(SingletonUpdateChange.newBuilder()
                        .blockStreamInfoValue(info)
                        .build())
                .build();
        // And this was the last output block item
        final var lastStateChanges = BlockItem.newBuilder()
                .stateChanges(new StateChanges(info.blockEndTime(), List.of(blockStreamInfoChange)))
                .build();
        // So we can combine this last leaf's has with the size and rightmost hashes
        // store from the pending state changes tree to recompute its final root hash
        final var penultimateStateChangesTreeStatus = new StreamingTreeHasher.Status(
                info.numPrecedingStateChangesItems(), info.rightmostPrecedingStateChangesTreeHashes());
        final var lastLeafHash = noThrowSha384HashOf(BlockItem.PROTOBUF.toBytes(lastStateChanges));
        return rootHashFrom(penultimateStateChangesTreeStatus, lastLeafHash);
    }

    private void logConfiguration() {
        if (logger.isInfoEnabled()) {
            final var config = configProvider.getConfiguration();
            final var lines = new ArrayList<String>();
            lines.add("Active Configuration:");
            Utils.allProperties(config).forEach((key, value) -> lines.add(key + " = " + value));
            logger.info(String.join("\n", lines));
        }
    }

    private void unmarkMigrationRecordsStreamed(@NonNull final State state) {
        final var blockServiceState = state.getWritableStates(BlockRecordService.NAME);
        final var blockInfoState = blockServiceState.<BlockInfo>getSingleton(BLOCKS_STATE_ID);
        final var currentBlockInfo = requireNonNull(blockInfoState.get());
        final var nextBlockInfo =
                currentBlockInfo.copyBuilder().migrationRecordsStreamed(false).build();
        blockInfoState.put(nextBlockInfo);
        logger.info("Unmarked post-upgrade work as done");
        ((WritableSingletonStateBase<BlockInfo>) blockInfoState).commit();
    }

    private void assertEnvSanityChecks(@NonNull final NodeId nodeId) {
        // Check that UTF-8 is in use. Otherwise, the node will be subject to subtle bugs in string handling that will
        // lead to ISS.
        final var defaultCharset = daggerApp.nativeCharset().get();
        if (!isUTF8(defaultCharset)) {
            logger.error(
                    """
                            Fatal precondition violation in HederaNode#{}: default charset is {} and not UTF-8
                            LC_ALL={}
                            LANG={}
                            file.encoding={}
                            """,
                    nodeId,
                    defaultCharset,
                    System.getenv("LC_ALL"),
                    System.getenv("LANG"),
                    System.getProperty("file.encoding"));
            System.exit(1);
        }

        // Check that the digest factory supports SHA-384.
        if (!sha384DigestIsAvailable()) {
            logger.error(
                    "Fatal precondition violation in HederaNode#{}: digest factory does not support SHA-384", nodeId);
            System.exit(1);
        }
    }

    private MerkleNodeState withListeners(@NonNull final MerkleNodeState root) {
        root.registerCommitListener(boundaryStateChangeListener);
        root.registerCommitListener(immediateStateChangeListener);
        return root;
    }

    private boolean manageBlockEndRound(@NonNull final Round round, @NonNull final State state) {
        daggerApp.nodeRewardManager().updateJudgesOnEndRound(state);
        return daggerApp.blockStreamManager().endRound(state, round.getRoundNum());
    }

    /**
     * Returns true if the source of time is the system time. Always true for live networks.
     *
     * @return true if the source of time is the system time
     */
    private boolean isNotEmbedded() {
        return appContext.instantSource() == InstantSource.system();
    }

    private class ReadReconnectStartingStateHash implements ReconnectCompleteListener {
        private final NotificationEngine notifications;

        private ReadReconnectStartingStateHash(@NonNull final NotificationEngine notifications) {
            this.notifications = requireNonNull(notifications);
        }

        @Override
        public void notify(@NonNull final ReconnectCompleteNotification notification) {
            requireNonNull(notification);
            requireNonNull(initialStateHashFuture)
                    .complete(requireNonNull(notification.getState().getHash()).getBytes());
            notifications.unregister(ReconnectCompleteListener.class, this);
        }
    }

    private boolean canAdoptRoster(@NonNull final Roster roster) {
        requireNonNull(initState);
        final var rosterHash = RosterUtils.hash(roster).getBytes();
        final var tssConfig = configProvider.getConfiguration().getConfigData(TssConfig.class);
        final var entityCounters = new ReadableEntityIdStoreImpl(initState.getWritableStates(EntityIdService.NAME));
        return (!tssConfig.hintsEnabled()
                        || new ReadableHintsStoreImpl(initState.getReadableStates(HintsService.NAME), entityCounters)
                                .isReadyToAdopt(rosterHash))
                && (!tssConfig.historyEnabled()
                        || new ReadableHistoryStoreImpl(initState.getReadableStates(HistoryService.NAME))
                                .isReadyToAdopt(rosterHash));
    }

    private void onAdoptRoster(@NonNull final Roster previousRoster, @NonNull final Roster adoptedRoster) {
        requireNonNull(initState);
        final var tssConfig = configProvider.getConfiguration().getConfigData(TssConfig.class);
        if (tssConfig.historyEnabled()) {
            final var adoptedRosterHash = RosterUtils.hash(adoptedRoster).getBytes();
            final var writableHistoryStates = initState.getWritableStates(HistoryService.NAME);
            final var store = new WritableHistoryStoreImpl(writableHistoryStates);
            store.handoff(previousRoster, adoptedRoster, adoptedRosterHash);
            ((CommittableWritableStates) writableHistoryStates).commit();
        }
        if (tssConfig.hintsEnabled()) {
            final var adoptedRosterHash = RosterUtils.hash(adoptedRoster).getBytes();
            final var writableHintsStates = initState.getWritableStates(HintsService.NAME);
            final var writableEntityStates = initState.getWritableStates(EntityIdService.NAME);
            final var entityCounters = new WritableEntityIdStore(writableEntityStates);
            final var store = new WritableHintsStoreImpl(writableHintsStates, entityCounters);
            hintsService.manageRosterAdoption(
                    store, previousRoster, adoptedRoster, adoptedRosterHash, tssConfig.forceHandoffs());
            ((CommittableWritableStates) writableHintsStates).commit();
        }
    }

    /**
     * Initializes block node connections and waits for at least one connection to be established.
     * This should be called before platform.start() to ensure we don't miss any blocks.
     */
    public void initializeBlockNodeConnections() {
        final BlockStreamConfig blockStreamConfig =
                configProvider.getConfiguration().getConfigData(BlockStreamConfig.class);

        if (!blockStreamConfig.streamToBlockNodes()) {
            logger.info("Block stream to Block Nodes is disabled, skipping block node connection initialization");
            return;
        }

        final BlockNodeConnectionConfig blockNodeConnectionConfig =
                configProvider.getConfiguration().getConfigData(BlockNodeConnectionConfig.class);

        try {
            daggerApp.blockNodeConnectionManager().start();
        } catch (final NoBlockNodesAvailableException e) {
            if (blockNodeConnectionConfig.shutdownNodeOnNoBlockNodes()
                    && blockStreamConfig.writerMode().equals(BlockStreamWriterMode.GRPC)
                    && blockStreamConfig.streamMode().equals(BLOCKS)) {

                logger.fatal("No block nodes available to connect to; shutting down");
                shutdown();
                System.exit(1);
            } else {
                logger.warn("No block nodes available to connect to");
            }
        }
    }
}
