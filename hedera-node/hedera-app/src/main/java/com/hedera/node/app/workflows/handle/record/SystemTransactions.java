// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS_BUT_MISSING_EXPECTED_OPERATION;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_KEY;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_KEY;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_KEY;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.parseEd25519NodeAdminKeysFrom;
import static com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl.TOPICS_KEY;
import static com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema.BYTECODE_KEY;
import static com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema.STORAGE_KEY;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.BLOBS_KEY;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.dispatchSynthFileUpdate;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.parseConfigList;
import static com.hedera.node.app.service.schedule.impl.schemas.V0570ScheduleSchema.SCHEDULED_COUNTS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.NFTS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFO_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKENS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKEN_RELS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema.AIRDROPS_KEY;
import static com.hedera.node.app.spi.key.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.spi.workflows.DispatchOptions.independentDispatch;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.NODE;
import static com.hedera.node.app.util.FileUtilities.createFileID;
import static com.hedera.node.app.workflows.handle.HandleOutput.failInvalidStreamItems;
import static com.hedera.node.app.workflows.handle.HandleWorkflow.ALERT_MESSAGE;
import static com.hedera.node.app.workflows.handle.TransactionType.ORDINARY_TRANSACTION;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.node.config.types.StreamMode.BOTH;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static com.swirlds.platform.roster.RosterUtils.formatNodeName;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.addressbook.NodeCreateTransactionBody;
import com.hedera.hapi.node.addressbook.NodeUpdateTransactionBody;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.CurrentAndNextFeeSchedule;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema;
import com.hedera.node.app.service.consensus.ConsensusService;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.BlocklistParser;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.workflows.SystemContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.state.recordcache.LegacyListRecordSource;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.handle.Dispatch;
import com.hedera.node.app.workflows.handle.DispatchProcessor;
import com.hedera.node.app.workflows.handle.HandleOutput;
import com.hedera.node.app.workflows.handle.steps.ParentTxnFactory;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.BootstrapConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.node.config.data.SchedulingConfig;
import com.hedera.node.config.data.StakingConfig;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.EntityIdFactory;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.LongStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

    private final BlocklistParser blocklistParser = new BlocklistParser();
    private final AtomicInteger nextDispatchNonce = new AtomicInteger(1);
    private final FileServiceImpl fileService;
    private final ParentTxnFactory parentTxnFactory;
    private final StreamMode streamMode;
    private final NetworkInfo networkInfo;
    private final DispatchProcessor dispatchProcessor;
    private final ConfigProvider configProvider;
    private final EntityIdFactory idFactory;
    private final BlockRecordManager blockRecordManager;
    private final BlockStreamManager blockStreamManager;
    private final ExchangeRateManager exchangeRateManager;
    private final HederaRecordCache recordCache;
    private final Function<SemanticVersion, SoftwareVersion> softwareVersionFactory;

    /**
     * Constructs a new {@link SystemTransactions}.
     */
    @Inject
    public SystemTransactions(
            @NonNull final ParentTxnFactory parentTxnFactory,
            @NonNull final FileServiceImpl fileService,
            @NonNull final NetworkInfo networkInfo,
            @NonNull final ConfigProvider configProvider,
            @NonNull final DispatchProcessor dispatchProcessor,
            @NonNull final AppContext appContext,
            @NonNull final BlockRecordManager blockRecordManager,
            @NonNull final BlockStreamManager blockStreamManager,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final HederaRecordCache recordCache,
            @NonNull final Function<SemanticVersion, SoftwareVersion> softwareVersionFactory) {
        this.fileService = requireNonNull(fileService);
        this.parentTxnFactory = requireNonNull(parentTxnFactory);
        this.networkInfo = networkInfo;
        this.dispatchProcessor = dispatchProcessor;
        this.configProvider = requireNonNull(configProvider);
        this.streamMode = configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .streamMode();
        this.idFactory = appContext.idFactory();
        this.blockRecordManager = requireNonNull(blockRecordManager);
        this.blockStreamManager = requireNonNull(blockStreamManager);
        this.exchangeRateManager = requireNonNull(exchangeRateManager);
        this.recordCache = requireNonNull(recordCache);
        this.softwareVersionFactory = requireNonNull(softwareVersionFactory);
    }

    /**
     * Sets up genesis state for the system.
     *
     * @param now the current time
     * @param state the state to set up
     */
    public void doGenesisSetup(@NonNull final Instant now, @NonNull final State state) {
        requireNonNull(now);
        requireNonNull(state);
        final AtomicReference<Consumer<Dispatch>> onSuccess = new AtomicReference<>(dispatch -> {});
        final var systemContext =
                newSystemContext(now, state, dispatch -> onSuccess.get().accept(dispatch));

        final var config = configProvider.getConfiguration();
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
        // For a slightly more intuitive stream, now create the system files (which come next numerically)
        final var nodeStore = new ReadableStoreFactory(state).getStore(ReadableNodeStore.class);
        fileService.createSystemEntities(systemContext, nodeStore);
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
        for (final var nodeInfo : networkInfo.addressBook()) {
            final var adminKey = nodeAdminKeys.getOrDefault(nodeInfo.nodeId(), systemKey);
            if (adminKey != systemKey) {
                log.info("Override admin key for node{} is :: {}", nodeInfo.nodeId(), adminKey);
            }
            final var hapiEndpoints =
                    nodeInfo.hapiEndpoints().isEmpty() ? UNKNOWN_HAPI_ENDPOINT : nodeInfo.hapiEndpoints();
            onSuccess.set(dispatch -> {
                final var stack = dispatch.stack();
                final var writableNodeStore = new WritableStakingInfoStore(
                        stack.getWritableStates(TokenService.NAME),
                        new WritableEntityIdStore(stack.getWritableStates(EntityIdService.NAME)));
                final var rewardSumHistory = new Long[numStoredPeriods + 1];
                Arrays.fill(rewardSumHistory, 0L);
                writableNodeStore.putAndIncrementCount(
                        nodeInfo.nodeId(),
                        StakingNodeInfo.newBuilder()
                                .nodeNumber(nodeInfo.nodeId())
                                .maxStake(stakingConfig.maxStake())
                                .minStake(stakingConfig.minStake())
                                .rewardSumHistory(Arrays.asList(rewardSumHistory))
                                .weight(DEFAULT_GENESIS_WEIGHT)
                                .build());
                stack.commitSystemStateChanges();
            });
            systemContext.dispatchAdmin(b -> b.nodeCreate(NodeCreateTransactionBody.newBuilder()
                    .adminKey(adminKey)
                    .accountId(nodeInfo.accountId())
                    .description(formatNodeName(nodeInfo.nodeId()))
                    .gossipEndpoint(nodeInfo.gossipEndpoints())
                    .gossipCaCertificate(nodeInfo.sigCertBytes())
                    .serviceEndpoint(hapiEndpoints)
                    .build()));
        }
        networkInfo.updateFrom(state);
    }

    /**
     * Sets up post-upgrade state for the system.
     *
     * @param dispatch the post-upgrade transaction dispatch
     */
    public void doPostUpgradeSetup(@NonNull final Dispatch dispatch) {
        final var systemContext = systemContextFor(dispatch);
        final var config = dispatch.config();

        // We update the node details file from the address book that resulted from all pre-upgrade HAPI node changes
        final var nodesConfig = config.getConfigData(NodesConfig.class);
        if (nodesConfig.enableDAB()) {
            final var nodeStore = dispatch.handleContext().storeFactory().readableStore(ReadableNodeStore.class);
            fileService.updateAddressBookAndNodeDetailsAfterFreeze(systemContext, nodeStore);
            dispatch.stack().commitFullStack();
        }

        // And then we update the system files for fees schedules, throttles, override properties, and override
        // permissions from any upgrade files that are present in the configured directory
        final var filesConfig = config.getConfigData(FilesConfig.class);
        final var adminConfig = config.getConfigData(NetworkAdminConfig.class);
        final List<AutoEntityUpdate<Bytes>> autoSysFileUpdates = List.of(
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
                        in -> parseConfig("override HAPI permissions", in)));
        autoSysFileUpdates.forEach(update -> {
            if (update.tryIfPresent(adminConfig.upgradeSysFilesLoc(), systemContext)) {
                dispatch.stack().commitFullStack();
            }
        });
        final var autoNodeAdminKeyUpdates = new AutoEntityUpdate<Map<Long, Key>>(
                (ctx, nodeAdminKeys) -> nodeAdminKeys.forEach(
                        (nodeId, key) -> ctx.dispatchAdmin(b -> b.nodeUpdate(NodeUpdateTransactionBody.newBuilder()
                                .nodeId(nodeId)
                                .adminKey(key)
                                .build()))),
                adminConfig.upgradeNodeAdminKeysFile(),
                SystemTransactions::parseNodeAdminKeys);
        if (autoNodeAdminKeyUpdates.tryIfPresent(adminConfig.upgradeSysFilesLoc(), systemContext)) {
            dispatch.stack().commitFullStack();
        }
    }

    /**
     * Initialize the entity counts in entityId service from the post-upgrade and genesis state.
     * This should only be done as part of 0.59.0 post upgrade step.
     * This code is deprecated and should be removed
     * after 0.59.0 release.
     *
     * @param dispatch the transaction dispatch
     */
    @Deprecated
    public void initializeEntityCounts(@NonNull final Dispatch dispatch) {
        final var stack = dispatch.stack();
        final var entityCountsState =
                stack.getWritableStates(EntityIdService.NAME).getSingleton(ENTITY_COUNTS_KEY);
        final var builder = EntityCounts.newBuilder();

        final var tokenService = stack.getReadableStates(TokenService.NAME);
        final var numAccounts = tokenService.get(ACCOUNTS_KEY).size();
        final var numAliases = tokenService.get(ALIASES_KEY).size();
        final var numTokens = tokenService.get(TOKENS_KEY).size();
        final var numTokenRelations = tokenService.get(TOKEN_RELS_KEY).size();
        final var numNfts = tokenService.get(NFTS_KEY).size();
        final var numAirdrops = tokenService.get(AIRDROPS_KEY).size();
        final var numStakingInfos = tokenService.get(STAKING_INFO_KEY).size();

        final var numTopics =
                stack.getReadableStates(ConsensusService.NAME).get(TOPICS_KEY).size();
        final var numFiles =
                stack.getReadableStates(FileServiceImpl.NAME).get(BLOBS_KEY).size();
        final var numNodes =
                stack.getReadableStates(AddressBookService.NAME).get(NODES_KEY).size();
        final var numSchedules = stack.getReadableStates(ScheduleService.NAME)
                .get(SCHEDULED_COUNTS_KEY)
                .size();

        final var contractService = stack.getReadableStates(ContractService.NAME);
        final var numContractBytecodes = contractService.get(BYTECODE_KEY).size();
        final var numContractStorageSlots = contractService.get(STORAGE_KEY).size();

        log.info(
                """
                         Entity size from state:
                         Accounts: {},\s
                         Aliases: {},\s
                         Tokens: {},\s
                         TokenRelations: {},\s
                         NFTs: {},\s
                         Airdrops: {},\s
                         StakingInfos: {},\s
                         Topics: {},\s
                         Files: {},\s
                         Nodes: {},\s
                         Schedules: {},\s
                         ContractBytecodes: {},\s
                         ContractStorageSlots: {}
                        \s""",
                numAccounts,
                numAliases,
                numTokens,
                numTokenRelations,
                numNfts,
                numAirdrops,
                numStakingInfos,
                numTopics,
                numFiles,
                numNodes,
                numSchedules,
                numContractBytecodes,
                numContractStorageSlots);

        final var entityCountsUpdated = builder.numAccounts(numAccounts)
                .numAliases(numAliases)
                .numTokens(numTokens)
                .numTokenRelations(numTokenRelations)
                .numNfts(numNfts)
                .numAirdrops(numAirdrops)
                .numStakingInfos(numStakingInfos)
                .numTopics(numTopics)
                .numFiles(numFiles)
                .numNodes(numNodes)
                .numSchedules(numSchedules)
                .numContractBytecodes(numContractBytecodes)
                .numContractStorageSlots(numContractStorageSlots)
                .build();

        entityCountsState.put(entityCountsUpdated);
        log.info("Initialized entity counts for post-upgrade state to {}", entityCountsUpdated);
        dispatch.stack().commitFullStack();
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
     * @param updateParser   the function to parse the upgrade file
     * @param <T>            the type of the update representation
     */
    private record AutoEntityUpdate<T>(
            @NonNull AutoUpdate<T> autoUpdate,
            @NonNull String updateFileName,
            @NonNull Function<InputStream, T> updateParser) {
        /**
         * Attempts to update the system file using the given system context if the corresponding upgrade file is
         * present at the given location and can be parsed with this update's parser.
         *
         * @return whether a synthetic update was dispatched
         */
        boolean tryIfPresent(@NonNull final String postUpgradeLoc, @NonNull final SystemContext systemContext) {
            final var path = Paths.get(postUpgradeLoc, updateFileName);
            if (!Files.exists(path)) {
                log.info(
                        "No post-upgrade file for {} found at {}, not updating", updateFileName, path.toAbsolutePath());
                return false;
            }
            try (final var fin = Files.newInputStream(path)) {
                final T update;
                try {
                    update = updateParser.apply(fin);
                } catch (Exception e) {
                    log.error("Failed to parse update file at {}", path.toAbsolutePath(), e);
                    return false;
                }
                log.info("Dispatching synthetic update based on contents of {}", path.toAbsolutePath());
                autoUpdate.doUpdate(systemContext, update);
                return true;
            } catch (IOException e) {
                log.error("Failed to read update file at {}", path.toAbsolutePath(), e);
            }
            return false;
        }
    }

    /**
     * Returns the timestamp to use for startup work state change consensus time in the block stream.
     * @param roundTime the round timestamp
     */
    public Instant startupWorkConsTimeFor(@NonNull final Instant roundTime) {
        requireNonNull(roundTime);
        final var config = configProvider.getConfiguration();
        // Make room for dispatching at least as many transactions as there are system entities
        return roundTime
                .minusNanos(config.getConfigData(SchedulingConfig.class).consTimeSeparationNanos())
                .minusNanos(config.getConfigData(HederaConfig.class).firstUserEntity());
    }

    private SystemContext newSystemContext(
            @NonNull final Instant now, @NonNull final State state, @NonNull final Consumer<Dispatch> onSuccess) {
        final var config = configProvider.getConfiguration();
        final var firstConsTime = startupWorkConsTimeFor(now);
        final AtomicReference<Instant> nextConsTime = new AtomicReference<>(firstConsTime);
        final var systemAdminId = idFactory.newAccountId(
                config.getConfigData(AccountsConfig.class).systemAdmin());
        // Use whatever node happens to be first in the address book as the "creator"
        final var creatorInfo = networkInfo.addressBook().getFirst();
        final var validDuration =
                new Duration(config.getConfigData(HederaConfig.class).transactionMaxValidDuration());
        final AtomicBoolean firstHandled = new AtomicBoolean(true);

        return new SystemContext() {
            @Override
            public void dispatchAdmin(@NonNull final Consumer<TransactionBody.Builder> spec) {
                dispatchCreation(spec, 0);
            }

            @Override
            public void dispatchCreation(@NonNull final Consumer<TransactionBody.Builder> spec, final long entityNum) {
                requireNonNull(spec);
                final var builder = TransactionBody.newBuilder()
                        .transactionValidDuration(validDuration)
                        .transactionID(TransactionID.newBuilder()
                                .accountID(systemAdminId)
                                .transactionValidStart(asTimestamp(now()))
                                .nonce(nextDispatchNonce.getAndIncrement())
                                .build());
                spec.accept(builder);
                dispatchCreation(builder.build(), entityNum);
            }

            @Override
            public void dispatchCreation(@NonNull final TransactionBody body, final long entityNum) {
                requireNonNull(body);
                // System dispatches never have child transactions, so one nano is enough to separate them
                final var now = nextConsTime.getAndUpdate(then -> then.plusNanos(1));
                if (streamMode == BOTH) {
                    blockRecordManager.startUserTransaction(now, state);
                }
                final var handleOutput =
                        executeSystem(state, now, creatorInfo, systemAdminId, body, entityNum, config, onSuccess);
                if (streamMode != BLOCKS) {
                    final var records =
                            ((LegacyListRecordSource) handleOutput.recordSourceOrThrow()).precomputedRecords();
                    blockRecordManager.endUserTransaction(records.stream(), state);
                }
                if (streamMode != RECORDS) {
                    handleOutput.blockRecordSourceOrThrow().forEachItem(blockStreamManager::writeItem);
                    if (firstHandled.compareAndSet(true, false)) {
                        blockStreamManager.setRoundFirstTransactionTime(now);
                    }
                }
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
     * @return the stream output from executing the transaction
     */
    private HandleOutput executeSystem(
            @NonNull final State state,
            @NonNull final Instant now,
            @NonNull final NodeInfo creatorInfo,
            @NonNull final AccountID payerId,
            @NonNull final TransactionBody body,
            final long nextEntityNum,
            @NonNull final Configuration config,
            @NonNull final Consumer<Dispatch> onSuccess) {
        final var parentTxn =
                parentTxnFactory.createSystemTxn(state, creatorInfo, now, ORDINARY_TRANSACTION, payerId, body);
        parentTxn.initBaseBuilder(exchangeRateManager.exchangeRates());
        final var dispatch = parentTxnFactory.createDispatch(parentTxn, parentTxn.baseBuilder(), ignore -> true, NODE);
        blockStreamManager.setLastHandleTime(parentTxn.consensusNow());
        if (streamMode != BLOCKS) {
            // This updates consTimeOfLastHandledTxn as a side effect
            blockRecordManager.advanceConsensusClock(parentTxn.consensusNow(), parentTxn.state());
        }
        try {
            final var controlledNum = (nextEntityNum != 0)
                    ? dispatch.stack()
                            .getWritableStates(EntityIdService.NAME)
                            .<EntityNumber>getSingleton(ENTITY_ID_STATE_KEY)
                    : null;
            if (controlledNum != null) {
                controlledNum.put(new EntityNumber(nextEntityNum - 1));
            }
            dispatchProcessor.processDispatch(dispatch);
            if (!SUCCESSES.contains(dispatch.streamBuilder().status())) {
                log.error(
                        "Failed to dispatch system transaction {}{} - {}",
                        body,
                        nextEntityNum == 0 ? "" : (" for entity #" + nextEntityNum),
                        dispatch.streamBuilder().status());
            } else {
                onSuccess.accept(dispatch);
            }
            if (controlledNum != null) {
                controlledNum.put(new EntityNumber(
                        config.getConfigData(HederaConfig.class).firstUserEntity() - 1));
                dispatch.stack().commitSystemStateChanges();
            }
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

    private SystemContext systemContextFor(@NonNull final Dispatch dispatch) {
        final var config = dispatch.config();
        final var hederaConfig = config.getConfigData(HederaConfig.class);
        final var firstUserNum = config.getConfigData(HederaConfig.class).firstUserEntity();
        final var systemAdminId = AccountID.newBuilder()
                .shardNum(hederaConfig.shard())
                .realmNum(hederaConfig.realm())
                .accountNum(config.getConfigData(AccountsConfig.class).systemAdmin())
                .build();
        return new SystemContext() {
            @Override
            public void dispatchCreation(@NonNull final TransactionBody body, final long entityNum) {
                requireNonNull(body);
                if (entityNum >= firstUserNum) {
                    throw new IllegalArgumentException("Cannot create user entity in a system context");
                }
                final var controlledNum = dispatch.stack()
                        .getWritableStates(EntityIdService.NAME)
                        .<EntityNumber>getSingleton(ENTITY_ID_STATE_KEY);
                controlledNum.put(new EntityNumber(entityNum - 1));
                final var recordBuilder = dispatch.handleContext()
                        .dispatch(independentDispatch(systemAdminId, body, StreamBuilder.class));
                if (!SUCCESSES.contains(recordBuilder.status())) {
                    log.error(
                            "Failed to dispatch system create transaction {} for entity {} - {}",
                            body,
                            entityNum,
                            recordBuilder.status());
                }
                controlledNum.put(new EntityNumber(firstUserNum - 1));
                dispatch.stack().commitSystemStateChanges();
            }

            @Override
            public void dispatchAdmin(@NonNull final Consumer<TransactionBody.Builder> spec) {
                requireNonNull(spec);
                final var bodyBuilder = TransactionBody.newBuilder()
                        .transactionID(TransactionID.newBuilder()
                                .accountID(systemAdminId)
                                .transactionValidStart(asTimestamp(now()))
                                .nonce(nextDispatchNonce.getAndIncrement())
                                .build());
                spec.accept(bodyBuilder);
                final var body = bodyBuilder.build();
                final var streamBuilder = dispatch.handleContext()
                        .dispatch(independentDispatch(systemAdminId, body, StreamBuilder.class));
                if (!SUCCESSES.contains(streamBuilder.status())) {
                    log.error("Failed to dispatch update transaction {} for - {}", body, streamBuilder.status());
                }
            }

            @NonNull
            @Override
            public Configuration configuration() {
                return dispatch.config();
            }

            @NonNull
            @Override
            public NetworkInfo networkInfo() {
                return dispatch.handleContext().networkInfo();
            }

            @NonNull
            @Override
            public Instant now() {
                return dispatch.consensusNow();
            }
        };
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
