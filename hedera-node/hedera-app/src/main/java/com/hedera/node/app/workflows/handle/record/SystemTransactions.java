// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record;

import static com.hedera.hapi.node.base.HederaFunctionality.NODE_CREATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS_BUT_MISSING_EXPECTED_OPERATION;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.parseEd25519NodeAdminKeysFrom;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.dispatchSynthFileUpdate;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.parseConfigList;
import static com.hedera.node.app.service.token.impl.handlers.staking.EndOfStakingPeriodUpdater.END_OF_PERIOD_MEMO;
import static com.hedera.node.app.service.token.impl.handlers.staking.EndOfStakingPeriodUtils.fromStakingInfo;
import static com.hedera.node.app.service.token.impl.handlers.staking.EndOfStakingPeriodUtils.lastInstantOfPreviousPeriodFor;
import static com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema.dispatchSynthNodeRewards;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.NODE;
import static com.hedera.node.app.util.FileUtilities.createFileID;
import static com.hedera.node.app.workflows.handle.HandleOutput.failInvalidStreamItems;
import static com.hedera.node.app.workflows.handle.HandleWorkflow.ALERT_MESSAGE;
import static com.hedera.node.app.workflows.handle.TransactionType.INTERNAL_TRANSACTION;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static com.swirlds.platform.system.InitTrigger.GENESIS;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.node.NodeUtilities.formatNodeName;
import static org.hiero.consensus.platformstate.V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID;

import com.hedera.hapi.node.addressbook.NodeCreateTransactionBody;
import com.hedera.hapi.node.addressbook.NodeDeleteTransactionBody;
import com.hedera.hapi.node.addressbook.NodeUpdateTransactionBody;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.CurrentAndNextFeeSchedule;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.history.ProofKey;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.NodeStake;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.tss.LedgerIdNodeContribution;
import com.hedera.hapi.node.tss.LedgerIdPublicationTransactionBody;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.records.impl.WrappedRecordBlockHashMigration;
import com.hedera.node.app.records.schemas.V0490BlockRecordSchema;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.ReadableEntityIdStore;
import com.hedera.node.app.service.entityid.impl.WritableEntityIdStoreImpl;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.BlocklistParser;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import com.hedera.node.app.service.token.impl.handlers.staking.EndOfStakingPeriodUtils;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.app.spi.records.RecordSource;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.app.spi.workflows.SystemContext;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.state.recordcache.LegacyListRecordSource;
import com.hedera.node.app.store.ReadableStoreFactoryImpl;
import com.hedera.node.app.workflows.handle.Dispatch;
import com.hedera.node.app.workflows.handle.DispatchProcessor;
import com.hedera.node.app.workflows.handle.HandleOutput;
import com.hedera.node.app.workflows.handle.steps.ParentTxnFactory;
import com.hedera.node.app.workflows.handle.steps.StakePeriodChanges;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.BootstrapConfig;
import com.hedera.node.config.data.ConsensusConfig;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.node.config.data.SchedulingConfig;
import com.hedera.node.config.data.StakingConfig;
import com.hedera.node.config.types.StreamMode;
import com.hedera.node.internal.network.NodeMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.State;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.LongStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.platformstate.PlatformStateService;
import org.hiero.consensus.roster.ReadableRosterStore;
import org.hiero.hapi.support.fees.FeeSchedule;

/**
 * This class is responsible for storing the system accounts created during node startup, and then creating
 * the corresponding synthetic records when a consensus time becomes available.
 */
@Singleton
@SuppressWarnings("deprecation")
public class SystemTransactions {

    private static final Logger log = LogManager.getLogger(SystemTransactions.class);

    private static final int DEFAULT_GENESIS_WEIGHT = 500;
    private static final long FIRST_RESERVED_SYSTEM_CONTRACT = 350L;
    private static final long LAST_RESERVED_SYSTEM_CONTRACT = 399L;
    private static final long FIRST_POST_SYSTEM_FILE_ENTITY = 200L;
    private static final long FIRST_MISC_ACCOUNT_NUM = 900L;
    private static final List<ServiceEndpoint> UNKNOWN_HAPI_ENDPOINT =
            List.of(V053AddressBookSchema.endpointFor("1.0.0.0", 1));

    private static final EnumSet<ResponseCodeEnum> SUCCESSES =
            EnumSet.of(SUCCESS, SUCCESS_BUT_MISSING_EXPECTED_OPERATION);
    private static final Consumer<Dispatch> DEFAULT_DISPATCH_ON_SUCCESS = dispatch -> {};

    private final InitTrigger initTrigger;
    private final BlocklistParser blocklistParser = new BlocklistParser();
    private final FileServiceImpl fileService;
    private final ParentTxnFactory parentTxnFactory;
    private final StreamMode streamMode;
    private final NetworkInfo networkInfo;
    private final DispatchProcessor dispatchProcessor;
    private final ConfigProvider configProvider;
    private final EntityIdFactory idFactory;
    private final ServicesRegistry servicesRegistry;
    private final BlockRecordManager blockRecordManager;
    private final BlockStreamManager blockStreamManager;
    private final ExchangeRateManager exchangeRateManager;
    private final HederaRecordCache recordCache;
    private final StartupNetworks startupNetworks;
    private final StakePeriodChanges stakePeriodChanges;
    private final SelfNodeAccountIdManager selfNodeAccountIdManager;
    private final WrappedRecordBlockHashMigration wrappedRecordBlockHashMigration;
    private int nextDispatchNonce = 1;

    @FunctionalInterface
    public interface StateChangeStreaming {
        void doStreamingChanges(
                @NonNull WritableStates writableStates,
                @Nullable WritableStates entityIdWritableStates,
                @NonNull Runnable action);
    }

    /**
     * Constructs a new {@link SystemTransactions}.
     */
    @Inject
    public SystemTransactions(
            @NonNull final InitTrigger initTrigger,
            @NonNull final ParentTxnFactory parentTxnFactory,
            @NonNull final FileServiceImpl fileService,
            @NonNull final NetworkInfo networkInfo,
            @NonNull final ConfigProvider configProvider,
            @NonNull final DispatchProcessor dispatchProcessor,
            @NonNull final AppContext appContext,
            @NonNull final ServicesRegistry servicesRegistry,
            @NonNull final BlockRecordManager blockRecordManager,
            @NonNull final BlockStreamManager blockStreamManager,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final HederaRecordCache recordCache,
            @NonNull final StartupNetworks startupNetworks,
            @NonNull final StakePeriodChanges stakePeriodChanges,
            @NonNull final SelfNodeAccountIdManager selfNodeAccountIdManager,
            @NonNull final WrappedRecordBlockHashMigration wrappedRecordBlockHashMigration) {
        this.initTrigger = requireNonNull(initTrigger);
        this.fileService = requireNonNull(fileService);
        this.parentTxnFactory = requireNonNull(parentTxnFactory);
        this.networkInfo = requireNonNull(networkInfo);
        this.dispatchProcessor = requireNonNull(dispatchProcessor);
        this.configProvider = requireNonNull(configProvider);
        this.streamMode = configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .streamMode();
        this.idFactory = appContext.idFactory();
        this.servicesRegistry = requireNonNull(servicesRegistry);
        this.blockRecordManager = requireNonNull(blockRecordManager);
        this.blockStreamManager = requireNonNull(blockStreamManager);
        this.exchangeRateManager = requireNonNull(exchangeRateManager);
        this.recordCache = requireNonNull(recordCache);
        this.startupNetworks = requireNonNull(startupNetworks);
        this.stakePeriodChanges = requireNonNull(stakePeriodChanges);
        this.selfNodeAccountIdManager = requireNonNull(selfNodeAccountIdManager);
        this.wrappedRecordBlockHashMigration = requireNonNull(wrappedRecordBlockHashMigration);
    }

    /**
     * Used to reset the system transaction dispatch nonce at the start of a round.
     */
    public void resetNextDispatchNonce() {
        nextDispatchNonce = 1;
    }

    /**
     * Sets up genesis state for the system.
     *
     * @param now the current time
     * @param state the state to set up
     * @param stateChangeStreaming the callback to stream KV state changes
     */
    public void doGenesisSetup(
            @NonNull final Instant now,
            @NonNull final State state,
            @NonNull final StateChangeStreaming stateChangeStreaming) {
        requireNonNull(now);
        requireNonNull(state);
        final var config = configProvider.getConfiguration();
        // Do genesis setup per-service, forcing the PlatformState singleton to be externalized first
        final var writablePlatformStates = state.getWritableStates(PlatformStateService.NAME);
        stateChangeStreaming.doStreamingChanges(writablePlatformStates, null, () -> {
            // Externalize the platform state singleton as it is with a little in-place write
            final var platformStateSingleton =
                    writablePlatformStates.<PlatformState>getSingleton(PLATFORM_STATE_STATE_ID);
            platformStateSingleton.put(platformStateSingleton.get());
        });
        for (final var r : servicesRegistry.registrations()) {
            final var service = r.service();
            if (PlatformStateService.NAME.equals(service.getServiceName())) {
                continue;
            }
            // Maybe EmptyWritableStates if the service's schemas register no state definitions at all
            final var writableStates = state.getWritableStates(service.getServiceName());
            stateChangeStreaming.doStreamingChanges(
                    writableStates, null, () -> service.doGenesisSetup(writableStates, config));
        }

        final AtomicReference<Consumer<Dispatch>> onSuccess = new AtomicReference<>(DEFAULT_DISPATCH_ON_SUCCESS);
        final var systemContext = newSystemContext(
                now,
                state,
                dispatch -> onSuccess.get().accept(dispatch),
                UseReservedConsensusTimes.YES,
                TriggerStakePeriodSideEffects.NO);

        final var ledgerConfig = config.getConfigData(LedgerConfig.class);
        final var accountsConfig = config.getConfigData(AccountsConfig.class);
        final var bootstrapConfig = config.getConfigData(BootstrapConfig.class);
        final var systemKey =
                Key.newBuilder().ed25519(bootstrapConfig.genesisPublicKey()).build();
        final var systemAutoRenewPeriod = new Duration(ledgerConfig.autoRenewPeriodMaxDuration());
        // Create the system accounts
        for (int i = 1, n = ledgerConfig.numSystemAccounts(); i <= n; i++) {
            final long num = i;
            systemContext.dispatchCreation(
                    b -> b.memo("Synthetic system creation")
                            .cryptoCreateAccount(CryptoCreateTransactionBody.newBuilder()
                                    .key(systemKey)
                                    .autoRenewPeriod(systemAutoRenewPeriod)
                                    .initialBalance(
                                            num == accountsConfig.treasury() ? ledgerConfig.totalTinyBarFloat() : 0L)
                                    .build())
                            .build(),
                    i);
        }
        // Create the treasury clones
        for (long i : LongStream.rangeClosed(FIRST_POST_SYSTEM_FILE_ENTITY, ledgerConfig.numReservedSystemEntities())
                .filter(j -> j < FIRST_RESERVED_SYSTEM_CONTRACT || j > LAST_RESERVED_SYSTEM_CONTRACT)
                .toArray()) {
            systemContext.dispatchCreation(
                    b -> b.memo("Synthetic zero-balance treasury clone")
                            .cryptoCreateAccount(CryptoCreateTransactionBody.newBuilder()
                                    .key(systemKey)
                                    .autoRenewPeriod(systemAutoRenewPeriod)
                                    .build())
                            .build(),
                    i);
        }
        // Create the staking reward accounts
        for (long i : List.of(accountsConfig.stakingRewardAccount(), accountsConfig.nodeRewardAccount())) {
            systemContext.dispatchCreation(
                    b -> b.memo("Release 0.24.1 migration record")
                            .cryptoCreateAccount(CryptoCreateTransactionBody.newBuilder()
                                    .key(IMMUTABILITY_SENTINEL_KEY)
                                    .autoRenewPeriod(systemAutoRenewPeriod)
                                    .build())
                            .build(),
                    i);
        }
        // Create fee collection account
        systemContext.dispatchCreation(
                b -> b.memo("Fee collection account creation record")
                        .cryptoCreateAccount(CryptoCreateTransactionBody.newBuilder()
                                .key(IMMUTABILITY_SENTINEL_KEY)
                                .autoRenewPeriod(systemAutoRenewPeriod)
                                .build())
                        .build(),
                accountsConfig.feeCollectionAccount());
        // Create the miscellaneous accounts
        final var hederaConfig = config.getConfigData(HederaConfig.class);
        for (long i : LongStream.range(FIRST_MISC_ACCOUNT_NUM, hederaConfig.firstUserEntity())
                .toArray()) {
            systemContext.dispatchCreation(
                    b -> b.cryptoCreateAccount(CryptoCreateTransactionBody.newBuilder()
                                    .key(systemKey)
                                    .autoRenewPeriod(systemAutoRenewPeriod)
                                    .build())
                            .build(),
                    i);
        }
        // If requested, create accounts with aliases from the blocklist
        if (accountsConfig.blocklistEnabled()) {
            for (final var info : blocklistParser.parse(accountsConfig.blocklistResource())) {
                systemContext.dispatchAdmin(b -> b.cryptoCreateAccount(CryptoCreateTransactionBody.newBuilder()
                        .key(systemKey)
                        .autoRenewPeriod(systemAutoRenewPeriod)
                        .receiverSigRequired(true)
                        .declineReward(true)
                        .alias(info.evmAddress())
                        .memo(info.memo())
                        .build()));
            }
        }

        // Create the address book nodes
        final var stakingConfig = config.getConfigData(StakingConfig.class);
        final var numStoredPeriods = stakingConfig.rewardHistoryNumStoredPeriods();
        final var nodeAdminKeys = parseEd25519NodeAdminKeysFrom(bootstrapConfig.nodeAdminKeysPath());
        final List<NodeStake> nodeStakes = new ArrayList<>();
        for (final var nodeInfo : networkInfo.addressBook()) {
            final var adminKey = nodeAdminKeys.getOrDefault(nodeInfo.nodeId(), systemKey);
            if (adminKey != systemKey) {
                log.info("Override admin key for node{} is :: {}", nodeInfo.nodeId(), adminKey);
            }
            final var hapiEndpoints =
                    nodeInfo.hapiEndpoints().isEmpty() ? UNKNOWN_HAPI_ENDPOINT : nodeInfo.hapiEndpoints();
            onSuccess.set(dispatch -> {
                final var stack = dispatch.stack();
                final var writableStakingInfoStore = new WritableStakingInfoStore(
                        stack.getWritableStates(TokenService.NAME),
                        new WritableEntityIdStoreImpl(stack.getWritableStates(EntityIdService.NAME)));
                if (writableStakingInfoStore.get(nodeInfo.nodeId()) == null) {
                    log.info("Creating staking info for node{}", nodeInfo.nodeId());
                    final var rewardSumHistory = new Long[numStoredPeriods + 1];
                    Arrays.fill(rewardSumHistory, 0L);
                    final var stakingNodeInfo = StakingNodeInfo.newBuilder()
                            .nodeNumber(nodeInfo.nodeId())
                            .maxStake(stakingConfig.maxStake())
                            .minStake(stakingConfig.minStake())
                            .rewardSumHistory(Arrays.asList(rewardSumHistory))
                            .weight(DEFAULT_GENESIS_WEIGHT)
                            .build();
                    writableStakingInfoStore.putAndIncrementCount(nodeInfo.nodeId(), stakingNodeInfo);
                    stack.commitFullStack();
                    nodeStakes.add(fromStakingInfo(0L, stakingNodeInfo));
                }
            });
            systemContext.dispatchCreation(
                    b -> {
                        final var nodeCreate = NodeCreateTransactionBody.newBuilder()
                                .adminKey(adminKey)
                                .accountId(nodeInfo.accountId())
                                .description(formatNodeName(nodeInfo.nodeId()))
                                .gossipEndpoint(nodeInfo.gossipEndpoints())
                                .gossipCaCertificate(nodeInfo.sigCertBytes())
                                .serviceEndpoint(hapiEndpoints)
                                .declineReward(true);
                        Optional.ofNullable(nodeInfo.grpcCertHash()).ifPresent(nodeCreate::grpcCertificateHash);
                        b.nodeCreate(nodeCreate.build());
                    },
                    nodeInfo.nodeId());
        }
        networkInfo.updateFrom(state);

        // Now that the onSuccess callback has executed for all the node create transactions, set the callback back to
        // its benign, do-nothing default
        onSuccess.set(DEFAULT_DISPATCH_ON_SUCCESS);

        // Now that the node metadata is correct, create the system files
        final var nodeStore = new ReadableStoreFactoryImpl(state).readableStore(ReadableNodeStore.class);
        fileService.createSystemEntities(systemContext, nodeStore);

        // And dispatch a node stake update transaction for mirror node benefit
        final var nodeStakeUpdate = EndOfStakingPeriodUtils.newNodeStakeUpdate(
                lastInstantOfPreviousPeriodFor(now), nodeStakes, stakingConfig, 0L, 0L, 0L, 0L);
        systemContext.dispatchAdmin(b -> b.memo(END_OF_PERIOD_MEMO).nodeStakeUpdate(nodeStakeUpdate));
    }

    /**
     * Sets up post-upgrade state for the system.
     * @param now the current time
     * @param state the state to set up
     */
    public void doPostUpgradeSetup(@NonNull final Instant now, @NonNull final State state) {
        final var systemContext = newSystemContext(
                now, state, dispatch -> {}, UseReservedConsensusTimes.YES, TriggerStakePeriodSideEffects.YES);
        final var config = configProvider.getConfiguration();

        // We update the node details file from the address book that resulted from all pre-upgrade HAPI node changes
        final var nodesConfig = config.getConfigData(NodesConfig.class);
        if (nodesConfig.enableDAB()) {
            final var nodeStore = new ReadableStoreFactoryImpl(state).readableStore(ReadableNodeStore.class);
            fileService.updateAddressBookAndNodeDetailsAfterFreeze(systemContext, nodeStore);
        }
        selfNodeAccountIdManager.setSelfNodeAccountId(networkInfo.selfNodeInfo().accountId());

        // And then we update the system files for fees schedules, throttles, override properties, and override
        // permissions from any upgrade files that are present in the configured directory
        final var filesConfig = config.getConfigData(FilesConfig.class);
        final var adminConfig = config.getConfigData(NetworkAdminConfig.class);
        final List<AutoEntityUpdate<Bytes>> autoSysFileUpdates = new ArrayList<>(List.of(
                new AutoEntityUpdate<>(
                        (ctx, bytes) ->
                                dispatchSynthFileUpdate(ctx, createFileID(filesConfig.feeSchedules(), config), bytes),
                        adminConfig.upgradeFeeSchedulesFile(),
                        SystemTransactions::parseFeeSchedules),
                new AutoEntityUpdate<>(
                        (ctx, bytes) -> dispatchSynthFileUpdate(
                                ctx, createFileID(filesConfig.throttleDefinitions(), config), bytes),
                        adminConfig.upgradeThrottlesFile(),
                        SystemTransactions::parseThrottles),
                new AutoEntityUpdate<>(
                        (ctx, bytes) -> dispatchSynthFileUpdate(
                                ctx, createFileID(filesConfig.networkProperties(), config), bytes),
                        adminConfig.upgradePropertyOverridesFile(),
                        in -> parseConfig("override network properties", in)),
                new AutoEntityUpdate<>(
                        (ctx, bytes) -> dispatchSynthFileUpdate(
                                ctx, createFileID(filesConfig.hapiPermissions(), config), bytes),
                        adminConfig.upgradePermissionOverridesFile(),
                        in -> parseConfig("override HAPI permissions", in))));
        final var feesConfig = config.getConfigData(FeesConfig.class);
        if (feesConfig.createSimpleFeeSchedule()) {
            final var simpleFeesFileId = createFileID(filesConfig.simpleFeesSchedules(), config);
            final var filesState =
                    state.getReadableStates(FileService.NAME).<FileID, File>get(V0490FileSchema.FILES_STATE_ID);
            if (filesState.get(simpleFeesFileId) == null) {
                log.info(
                        "Creating simple fee schedule file {}.{}.{} (upgrading from pre-simple-fees version)",
                        simpleFeesFileId.shardNum(),
                        simpleFeesFileId.realmNum(),
                        simpleFeesFileId.fileNum());
                fileService.fileSchema().createGenesisSimpleFeesSchedule(systemContext);
            }
            autoSysFileUpdates.add(new AutoEntityUpdate<>(
                    (ctx, bytes) -> dispatchSynthFileUpdate(
                            ctx, createFileID(filesConfig.simpleFeesSchedules(), config), bytes),
                    adminConfig.upgradeSimpleFeeSchedulesFile(),
                    SystemTransactions::parseSimpleFeesSchedules));
        }

        autoSysFileUpdates.forEach(update -> update.tryIfPresent(adminConfig.upgradeSysFilesLoc(), systemContext));
        final var autoNodeAdminKeyUpdates = new AutoEntityUpdate<Map<Long, Key>>(
                (ctx, nodeAdminKeys) -> nodeAdminKeys.forEach(
                        (nodeId, key) -> ctx.dispatchAdmin(b -> b.nodeUpdate(NodeUpdateTransactionBody.newBuilder()
                                .nodeId(nodeId)
                                .adminKey(key)
                                .build()))),
                adminConfig.upgradeNodeAdminKeysFile(),
                SystemTransactions::parseNodeAdminKeys);
        autoNodeAdminKeyUpdates.tryIfPresent(adminConfig.upgradeSysFilesLoc(), systemContext);

        // Apply the deferred state-write from the wrapped record block hash migration, if any
        final var migrationResult = wrappedRecordBlockHashMigration.result();
        if (migrationResult != null) {
            final var blockInfoState = state.getWritableStates(BlockRecordService.NAME)
                    .<BlockInfo>getSingleton(V0490BlockRecordSchema.BLOCKS_STATE_ID);
            final var blockInfo = requireNonNull(blockInfoState.get());
            final var updatedBlockInfo = blockInfo
                    .copyBuilder()
                    .blockHashes(migrationResult.blockHashes())
                    .previousWrappedRecordBlockRootHash(migrationResult.previousWrappedRecordBlockRootHash())
                    .wrappedIntermediatePreviousBlockRootHashes(
                            migrationResult.wrappedIntermediatePreviousBlockRootHashes())
                    .wrappedIntermediateBlockRootsLeafCount(migrationResult.wrappedIntermediateBlockRootsLeafCount())
                    .build();
            blockInfoState.put(updatedBlockInfo);
            ((WritableSingletonStateBase<BlockInfo>) blockInfoState).commit();
            log.info("Applied wrapped record block hash migration result to state");
        }
    }

    /**
     * Dispatches a synthetic node fee payment crypto transfer for the current staking period.
     *
     * @param state The state.
     * @param now The current time.
     * @param transfers The transfers to dispatch.
     */
    public void dispatchNodePayments(
            @NonNull final State state, @NonNull final Instant now, @NonNull final TransferList transfers) {
        requireNonNull(state);
        requireNonNull(now);
        requireNonNull(transfers);

        if (transfers.accountAmounts().isEmpty()) {
            log.info("No fees to distribute for nodes");
            return;
        }
        final var systemContext = newSystemContext(
                now, state, dispatch -> {}, UseReservedConsensusTimes.NO, TriggerStakePeriodSideEffects.YES);
        systemContext.dispatchAdmin(b -> b.memo("Synthetic node fees payment")
                .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                        .transfers(transfers)
                        .build())
                .build());
    }

    /**
     * Externalizes the ledger id and associated verification key for recursive chain-of-trust proofs.
     *
     * @param state the current state
     * @param now the consensus time for the synthetic transaction
     * @param ledgerId the new ledger id
     * @param proofKeys the proof keys for the new ledger id
     * @param targetNodeWeights the weights of the nodes in the target roster
     * @param historyProofVerificationKey the verification key for the new ledger id
     */
    public void externalizeLedgerId(
            @NonNull final State state,
            @NonNull final Instant now,
            @NonNull final Bytes ledgerId,
            @NonNull final List<ProofKey> proofKeys,
            @NonNull final SortedMap<Long, Long> targetNodeWeights,
            @NonNull final Bytes historyProofVerificationKey) {
        requireNonNull(now);
        requireNonNull(ledgerId);
        requireNonNull(proofKeys);
        requireNonNull(targetNodeWeights);
        requireNonNull(historyProofVerificationKey);
        final var systemContext = newSystemContext(
                now, state, dispatch -> {}, UseReservedConsensusTimes.NO, TriggerStakePeriodSideEffects.YES);
        final List<LedgerIdNodeContribution> contributions = proofKeys.stream()
                .map(proofKey -> LedgerIdNodeContribution.newBuilder()
                        .nodeId(proofKey.nodeId())
                        .historyProofKey(proofKey.key())
                        .weight(targetNodeWeights.get(proofKey.nodeId()))
                        .build())
                .toList();
        systemContext.dispatchAdmin(b -> b.memo("Ledger id")
                .ledgerIdPublication(LedgerIdPublicationTransactionBody.newBuilder()
                        .ledgerId(ledgerId)
                        .nodeContributions(contributions)
                        .historyProofVerificationKey(historyProofVerificationKey)
                        .build()));
    }

    /**
     * Dispatches a synthetic node reward crypto transfer for the given active node accounts.
     * If the {@link NodesConfig#minPerPeriodNodeRewardUsd()} is greater than zero, inactive nodes will receive the minimum node
     * reward.
     *
     * @param state The state.
     * @param now The current time.
     * @param activeNodeIds The list of active node ids.
     * @param perNodeReward The per node reward.
     * @param nodeRewardsAccountId The node rewards account id.
     * @param rewardAccountBalance The reward account balance.
     * @param minNodeReward The minimum node reward.
     * @param rosterEntries The list of roster entries.
     */
    public void dispatchNodeRewards(
            @NonNull final State state,
            @NonNull final Instant now,
            @NonNull final List<Long> activeNodeIds,
            final long perNodeReward,
            @NonNull final AccountID nodeRewardsAccountId,
            final long rewardAccountBalance,
            final long minNodeReward,
            @NonNull final List<RosterEntry> rosterEntries) {
        requireNonNull(state);
        requireNonNull(now);
        requireNonNull(activeNodeIds);
        requireNonNull(nodeRewardsAccountId);
        final var systemContext = newSystemContext(
                now, state, dispatch -> {}, UseReservedConsensusTimes.NO, TriggerStakePeriodSideEffects.YES);
        final var activeNodeAccountIds = activeNodeIds.stream()
                .map(id -> systemContext.networkInfo().nodeInfo(id))
                .filter(nodeInfo -> nodeInfo != null && !nodeInfo.declineReward())
                .map(NodeInfo::accountId)
                .toList();
        final var inactiveNodeAccountIds = rosterEntries.stream()
                .map(RosterEntry::nodeId)
                .filter(id -> !activeNodeIds.contains(id))
                .map(id -> systemContext.networkInfo().nodeInfo(id))
                .filter(nodeInfo -> nodeInfo != null && !nodeInfo.declineReward())
                .map(NodeInfo::accountId)
                .toList();
        if (activeNodeAccountIds.isEmpty() && (minNodeReward <= 0 || inactiveNodeAccountIds.isEmpty())) {
            // No eligible rewards to distribute
            return;
        }
        log.info("Found active node accounts {}", activeNodeAccountIds);
        if (minNodeReward > 0 && !inactiveNodeAccountIds.isEmpty()) {
            log.info(
                    "Found inactive node accounts {} that will receive minimum node reward {}",
                    inactiveNodeAccountIds,
                    minNodeReward);
        }
        // Check if rewardAccountBalance is enough to distribute rewards. If the balance is not enough, distribute
        // rewards to active nodes only. If the balance is enough, distribute rewards to both active and inactive nodes.
        final long activeTotal = activeNodeAccountIds.size() * perNodeReward;
        final long inactiveTotal = minNodeReward > 0 ? inactiveNodeAccountIds.size() * minNodeReward : 0L;

        if (rewardAccountBalance <= activeTotal) {
            final long activeNodeReward = rewardAccountBalance / activeNodeAccountIds.size();
            log.info("Balance insufficient for all, rewarding active nodes only: {} tinybars each", activeNodeReward);
            if (activeNodeReward > 0) {
                dispatchSynthNodeRewards(systemContext, activeNodeAccountIds, nodeRewardsAccountId, activeNodeReward);
            }
        } else {
            final long activeNodeReward =
                    activeNodeAccountIds.isEmpty() ? 0 : activeTotal / activeNodeAccountIds.size();
            final long totalInactiveNodesReward =
                    Math.min(Math.max(0, rewardAccountBalance - activeTotal), inactiveTotal);
            final long inactiveNodeReward =
                    inactiveNodeAccountIds.isEmpty() ? 0 : totalInactiveNodesReward / inactiveNodeAccountIds.size();
            log.info(
                    "Paying active nodes {} tinybars each, inactive nodes {} tinybars each",
                    activeNodeReward,
                    inactiveNodeReward);
            dispatchSynthNodeRewards(
                    systemContext,
                    activeNodeAccountIds,
                    nodeRewardsAccountId,
                    activeNodeReward,
                    inactiveNodeAccountIds,
                    inactiveNodeReward);
        }
    }

    public boolean dispatchTransplantUpdates(final State state, final Instant now, final long currentRoundNum) {
        requireNonNull(state);
        requireNonNull(now);
        final var readableStoreFactory = new ReadableStoreFactoryImpl(state);
        final var rosterStore = readableStoreFactory.readableStore(ReadableRosterStore.class);
        final var nodeStore = readableStoreFactory.readableStore(ReadableNodeStore.class);
        final var systemContext = newSystemContext(
                now, state, dispatch -> {}, UseReservedConsensusTimes.YES, TriggerStakePeriodSideEffects.YES);
        final var network = startupNetworks.overrideNetworkFor(currentRoundNum - 1, configProvider.getConfiguration());
        if (rosterStore.isTransplantInProgress() && network.isPresent()) {
            log.info("Roster transplant in progress, dispatching node updates for round {}", currentRoundNum - 1);
            final var overrideNodes = network.get().nodeMetadata().stream()
                    .filter(NodeMetadata::hasRosterEntry)
                    .map(NodeMetadata::rosterEntryOrThrow)
                    .map(RosterEntry::nodeId)
                    .toList();
            for (final var meta : network.get().nodeMetadata()) {
                final var node = meta.node();
                if (node == null) {
                    continue;
                }
                final var currentNode = nodeStore.get(node.nodeId());
                if (currentNode == null || currentNode.deleted()) {
                    // Node is new in the override roster, dispatch a node creation transaction
                    systemContext.dispatchCreation(
                            b -> b.memo("Synthetic node creation")
                                    .nodeCreate(NodeCreateTransactionBody.newBuilder()
                                            .adminKey(node.adminKey())
                                            .accountId(node.accountId())
                                            .description(node.description())
                                            .gossipEndpoint(node.gossipEndpoint())
                                            .gossipCaCertificate(node.gossipCaCertificate())
                                            .serviceEndpoint(node.serviceEndpoint())
                                            .declineReward(node.declineReward())
                                            .grpcProxyEndpoint(node.grpcProxyEndpoint())
                                            .grpcCertificateHash(node.grpcCertificateHash())
                                            .build())
                                    .build(),
                            node.nodeId());
                    log.info("Node {} is new in override network and is being created", node.nodeId());
                } else {
                    // Node is in the override roster and in current state, dispatch a node update transaction
                    systemContext.dispatchAdmin(b -> b.memo("Synthetic node update")
                            .nodeUpdate(NodeUpdateTransactionBody.newBuilder()
                                    .nodeId(node.nodeId())
                                    .adminKey(node.adminKey())
                                    .description(node.description())
                                    .gossipEndpoint(node.gossipEndpoint())
                                    .gossipCaCertificate(node.gossipCaCertificate())
                                    .serviceEndpoint(node.serviceEndpoint())
                                    .declineReward(node.declineReward())
                                    .grpcProxyEndpoint(node.grpcProxyEndpoint())
                                    .grpcCertificateHash(node.grpcCertificateHash())
                                    .build()));
                    log.info("Node {} in state is part of the override network and is being updated", node.nodeId());
                }
            }
            final var numNodes = readableStoreFactory
                    .readableStore(ReadableEntityIdStore.class)
                    .numNodes();
            for (var i = 0; i < numNodes; i++) {
                final long nodeId = i;
                final var existingNode = nodeStore.get(i);
                if (existingNode != null && !overrideNodes.contains(nodeId) && !existingNode.deleted()) {
                    // Node is in the current state but not in the override roster, mark it as deleted
                    systemContext.dispatchAdmin(b -> b.memo("Synthetic node deletion")
                            .nodeDelete(NodeDeleteTransactionBody.newBuilder()
                                    .nodeId(nodeId)
                                    .build()));
                    log.info("Node {} in state is not part of the override network and is being marked deleted", i);
                }
            }
            log.info("Roster transplant completed, node updates dispatched");
            return true;
        }
        return false;
    }

    /**
     * Defines an update based on a new representation of one or more system entities within a context.
     *
     * @param <T> the type of the update representation
     */
    @FunctionalInterface
    private interface AutoUpdate<T> {
        void doUpdate(@NonNull SystemContext systemContext, @NonNull T update);
    }

    /**
     * Process object encapsulating the automatic update of a system entity. Attempts to parse a
     * representation of an update from a given file and then apply it within a system context
     * using the given {@link AutoUpdate} function.
     *
     * @param updateFileName the name of the upgrade file
     * @param updateParser the function to parse the upgrade file
     * @param <T> the type of the update representation
     */
    private record AutoEntityUpdate<T>(
            @NonNull AutoUpdate<T> autoUpdate,
            @NonNull String updateFileName,
            @NonNull Function<InputStream, T> updateParser) {
        /**
         * Attempts to update the system file using the given system context if the corresponding upgrade file is
         * present at the given location and can be parsed with this update's parser.
         */
        void tryIfPresent(@NonNull final String postUpgradeLoc, @NonNull final SystemContext systemContext) {
            final var path = Paths.get(postUpgradeLoc, updateFileName);
            if (!Files.exists(path)) {
                log.info(
                        "No post-upgrade file for {} found at {}, not updating", updateFileName, path.toAbsolutePath());
                return;
            }
            try (final var fin = Files.newInputStream(path)) {
                final T update;
                try {
                    update = updateParser.apply(fin);
                } catch (Exception e) {
                    log.error("Failed to parse update file at {}", path.toAbsolutePath(), e);
                    return;
                }
                log.info("Dispatching synthetic update based on contents of {}", path.toAbsolutePath());
                autoUpdate.doUpdate(systemContext, update);
            } catch (IOException e) {
                log.error("Failed to read update file at {}", path.toAbsolutePath(), e);
            }
        }
    }

    /**
     * Returns the timestamp to use for startup work state change consensus time in the block stream.
     *
     * @param firstEventTime the timestamp of the first event in the current round
     */
    public Instant firstReservedSystemTimeFor(@NonNull final Instant firstEventTime) {
        requireNonNull(firstEventTime);
        final var config = configProvider.getConfiguration();
        final var consensusConfig = config.getConfigData(ConsensusConfig.class);
        return firstEventTime
                // Avoid overlap with a possible user transaction first in the event
                .minusNanos(1)
                // Avoid overlap with possible preceding records of this user transaction
                .minusNanos(consensusConfig.handleMaxPrecedingRecords())
                // Then back up to the first reserved system transaction time
                .minusNanos(config.getConfigData(SchedulingConfig.class).reservedSystemTxnNanos())
                // And at genesis, further step back to accommodate creating system entities
                .minusNanos(
                        initTrigger == GENESIS
                                ? (int) config.getConfigData(HederaConfig.class).firstUserEntity()
                                : 0);
    }

    /**
     * Whether a context for system transactions should use reserved prior consensus times, or pick up from
     * the time given to the transaction context factory.
     */
    private enum UseReservedConsensusTimes {
        YES,
        NO
    }

    /**
     * Whether the dispatches in a context for system transactions should trigger stake period boundary
     * side effects.
     */
    private enum TriggerStakePeriodSideEffects {
        YES,
        NO
    }

    private SystemContext newSystemContext(
            @NonNull final Instant now,
            @NonNull final State state,
            @NonNull final Consumer<Dispatch> onSuccess,
            @NonNull final UseReservedConsensusTimes useReservedConsensusTimes,
            @NonNull final TriggerStakePeriodSideEffects triggerStakePeriodSideEffects) {
        final var config = configProvider.getConfiguration();
        final boolean useReserved = useReservedConsensusTimes == UseReservedConsensusTimes.YES;
        final var firstConsTime = useReserved ? firstReservedSystemTimeFor(now) : now;
        final boolean applyStakePeriodSideEffects = triggerStakePeriodSideEffects == TriggerStakePeriodSideEffects.YES;
        // The number of dispatches we can do in this context is determined by the number of available consensus times,
        // where each dispatch also has an earlier nanosecond free for a preceding NODE_STAKE_UPDATE if this context is
        // applying stake period side effects
        final var remainingDispatches = new AtomicInteger(
                useReserved
                        ? (int) java.time.Duration.between(firstConsTime, now).toNanos()
                                / (applyStakePeriodSideEffects ? 2 : 1)
                        : 1);
        final AtomicReference<Instant> nextConsTime = new AtomicReference<>(firstConsTime);
        final var systemAdminId = idFactory.newAccountId(
                config.getConfigData(AccountsConfig.class).systemAdmin());
        // Use whatever node happens to be first in the address book as the "creator"
        final var creatorInfo = networkInfo.addressBook().getFirst();
        final var validDuration =
                new Duration(config.getConfigData(HederaConfig.class).transactionMaxValidDuration());

        return new SystemContext() {
            @Override
            public boolean hasDispatchesRemaining() {
                return remainingDispatches.get() > 0;
            }

            @Override
            public void dispatchAdmin(@NonNull final Consumer<TransactionBody.Builder> spec) {
                requireNonNull(spec);
                final var builder = TransactionBody.newBuilder()
                        .transactionValidDuration(validDuration)
                        .transactionID(TransactionID.newBuilder()
                                .accountID(systemAdminId)
                                .transactionValidStart(asTimestamp(now()))
                                .nonce(nextDispatchNonce++)
                                .build());
                spec.accept(builder);
                final var body = builder.build();
                final var output = dispatch(body, 0, triggerStakePeriodSideEffects);
                final var statuses = output.preferringBlockRecordSource().identifiedReceipts().stream()
                        .map(RecordSource.IdentifiedReceipt::receipt)
                        .map(TransactionReceipt::status)
                        .toList();
                if (!SUCCESSES.containsAll(statuses)) {
                    log.warn("Failed to dispatch system transaction {} - {}", body, statuses);
                }
            }

            @Override
            public void dispatchCreation(@NonNull final Consumer<TransactionBody.Builder> spec, final long entityNum) {
                requireNonNull(spec);
                final var builder = TransactionBody.newBuilder()
                        .transactionValidDuration(validDuration)
                        .transactionID(TransactionID.newBuilder()
                                .accountID(systemAdminId)
                                .transactionValidStart(asTimestamp(now()))
                                .nonce(nextDispatchNonce++)
                                .build());
                spec.accept(builder);
                dispatchCreation(builder.build(), entityNum);
            }

            @Override
            public void dispatchCreation(@NonNull final TransactionBody body, final long entityNum) {
                requireNonNull(body);
                dispatch(body, entityNum, triggerStakePeriodSideEffects);
            }

            @NonNull
            @Override
            public Configuration configuration() {
                return config;
            }

            @NonNull
            @Override
            public NetworkInfo networkInfo() {
                return networkInfo;
            }

            @NonNull
            @Override
            public Instant now() {
                return nextConsTime.get();
            }

            private HandleOutput dispatch(
                    @NonNull final TransactionBody body,
                    final long entityNum,
                    @NonNull final TriggerStakePeriodSideEffects triggerStakePeriodSideEffects) {
                if (remainingDispatches.decrementAndGet() < 0) {
                    throw new IllegalStateException("No more dispatches remaining in the system context");
                }
                final boolean applyStakePeriodSideEffects =
                        triggerStakePeriodSideEffects == TriggerStakePeriodSideEffects.YES;
                final int maxNanosUsed = applyStakePeriodSideEffects ? 2 : 1;
                final var now = nextConsTime.getAndUpdate(then -> then.plusNanos(maxNanosUsed));
                if (streamMode != BLOCKS) {
                    blockRecordManager.startUserTransaction(now, state);
                }
                final var handleOutput = executeSystem(
                        state,
                        now,
                        creatorInfo,
                        systemAdminId,
                        body,
                        entityNum,
                        onSuccess,
                        applyStakePeriodSideEffects);
                if (streamMode != BLOCKS) {
                    final var records =
                            ((LegacyListRecordSource) handleOutput.recordSourceOrThrow()).precomputedRecords();
                    blockRecordManager.endUserTransaction(records.stream(), state);
                }
                if (streamMode != RECORDS) {
                    handleOutput.blockRecordSourceOrThrow().forEachItem(blockStreamManager::writeItem);
                }
                return handleOutput;
            }
        };
    }

    /**
     * Executes the scheduled transaction against the given state at the given time and returns
     * the output that should be externalized in the block stream. (And if still producing records,
     * the precomputed records.)
     * <p>
     * Never throws an exception without a fundamental breakdown of the system invariants. If
     * there is an internal error when executing the transaction, returns stream output of just the
     * scheduled transaction with a {@link ResponseCodeEnum#FAIL_INVALID} transaction result, and
     * no other side effects.
     *
     * @param state the state to execute the transaction against
     * @param now the time to execute the transaction at
     * @param creatorInfo the node info of the creator of the transaction
     * @param payerId the payer of the transaction
     * @param body the transaction to execute
     * @param nextEntityNum if not zero, the next entity number to use for the transaction
     * @param onSuccess the action to take after the transaction is successfully dispatched
     * @param applyStakePeriodSideEffects the flag indicating if this is a genesis transaction
     * @return the stream output from executing the transaction
     */
    private HandleOutput executeSystem(
            @NonNull final State state,
            @NonNull final Instant now,
            @NonNull final NodeInfo creatorInfo,
            @NonNull final AccountID payerId,
            @NonNull final TransactionBody body,
            final long nextEntityNum,
            @NonNull final Consumer<Dispatch> onSuccess,
            final boolean applyStakePeriodSideEffects) {
        final var parentTxn =
                parentTxnFactory.createSystemTxn(state, creatorInfo, now, INTERNAL_TRANSACTION, payerId, body);
        parentTxn.initBaseBuilder(exchangeRateManager.exchangeRates());
        final var dispatch = parentTxnFactory.createDispatch(parentTxn, parentTxn.baseBuilder(), ignore -> true, NODE);
        stakePeriodChanges.advanceTimeTo(parentTxn, applyStakePeriodSideEffects);
        try {
            long prevEntityNum;
            if (dispatch.txnInfo().functionality() == NODE_CREATE) {
                WritableSingletonState<EntityCounts> countsBefore =
                        dispatch.stack().getWritableStates(EntityIdService.NAME).getSingleton(ENTITY_COUNTS_STATE_ID);
                prevEntityNum = requireNonNull(countsBefore.get()).numNodes();
                countsBefore.put(requireNonNull(countsBefore.get())
                        .copyBuilder()
                        .numNodes(nextEntityNum)
                        .build());
            } else {
                WritableSingletonState<EntityNumber> controlledNum =
                        dispatch.stack().getWritableStates(EntityIdService.NAME).getSingleton(ENTITY_ID_STATE_ID);
                prevEntityNum = requireNonNull(controlledNum.get()).number();
                if (nextEntityNum != 0) {
                    controlledNum.put(new EntityNumber(nextEntityNum - 1));
                }
            }

            dispatchProcessor.processDispatch(dispatch);
            final boolean isSuccess =
                    SUCCESSES.contains(dispatch.streamBuilder().status());
            if (!isSuccess) {
                log.error(
                        "Failed to dispatch system transaction {}{} - {}",
                        body,
                        nextEntityNum == 0 ? "" : (" for entity #" + nextEntityNum),
                        dispatch.streamBuilder().status());
            } else {
                onSuccess.accept(dispatch);
            }
            if (dispatch.txnInfo().functionality() == NODE_CREATE) {
                WritableSingletonState<EntityCounts> countsBefore =
                        dispatch.stack().getWritableStates(EntityIdService.NAME).getSingleton(ENTITY_COUNTS_STATE_ID);
                countsBefore.put(requireNonNull(countsBefore.get())
                        .copyBuilder()
                        .numNodes(isSuccess ? Math.max(nextEntityNum + 1, prevEntityNum) : prevEntityNum)
                        .build());
            } else {
                WritableSingletonState<EntityNumber> controlledNum =
                        dispatch.stack().getWritableStates(EntityIdService.NAME).getSingleton(ENTITY_ID_STATE_ID);
                if (nextEntityNum != 0) {
                    controlledNum.put(new EntityNumber(prevEntityNum));
                }
            }
            dispatch.stack().commitFullStack();
            final var handleOutput =
                    parentTxn.stack().buildHandleOutput(parentTxn.consensusNow(), exchangeRateManager.exchangeRates());
            recordCache.addRecordSource(
                    creatorInfo.nodeId(),
                    parentTxn.txnInfo().transactionID(),
                    HederaRecordCache.DueDiligenceFailure.NO,
                    handleOutput.preferringBlockRecordSource());
            return handleOutput;
        } catch (final Exception e) {
            log.error("{} - exception thrown while handling system transaction", ALERT_MESSAGE, e);
            return failInvalidStreamItems(parentTxn, exchangeRateManager.exchangeRates(), streamMode, recordCache);
        }
    }

    private static Bytes parseFeeSchedules(@NonNull final InputStream in) {
        try {
            final var bytes = in.readAllBytes();
            final var feeSchedules = V0490FileSchema.parseFeeSchedules(bytes);
            return CurrentAndNextFeeSchedule.PROTOBUF.toBytes(feeSchedules);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Bytes parseSimpleFeesSchedules(@NonNull final InputStream in) {
        try {
            final var bytes = in.readAllBytes();
            final var feeSchedules = V0490FileSchema.parseSimpleFeesSchedules(bytes);
            return FeeSchedule.PROTOBUF.toBytes(feeSchedules);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Bytes parseThrottles(@NonNull final InputStream in) {
        try {
            final var json = new String(in.readAllBytes());
            return Bytes.wrap(V0490FileSchema.parseThrottleDefinitions(json));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Bytes parseConfig(@NonNull String purpose, @NonNull final InputStream in) {
        try {
            final var content = new String(in.readAllBytes());
            return parseConfigList(purpose, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<Long, Key> parseNodeAdminKeys(@NonNull final InputStream in) {
        try {
            final var json = new String(in.readAllBytes());
            return V053AddressBookSchema.parseEd25519NodeAdminKeys(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
