// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.NODE_CREATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS_BUT_MISSING_EXPECTED_OPERATION;
import static com.hedera.hapi.util.HapiUtils.ACCOUNT_ID_COMPARATOR;
import static com.hedera.hapi.util.HapiUtils.FUNDING_ACCOUNT_EXPIRY;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_KEY;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_KEY;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_KEY;
import static com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl.TOPICS_KEY;
import static com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema.BYTECODE_KEY;
import static com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema.STORAGE_KEY;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.BLOBS_KEY;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.dispatchSynthFileUpdate;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.parseConfigList;
import static com.hedera.node.app.service.schedule.impl.schemas.V0570ScheduleSchema.SCHEDULED_COUNTS_KEY;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHelper.asAccountAmounts;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.NFTS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFO_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKENS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKEN_RELS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema.AIRDROPS_KEY;
import static com.hedera.node.app.spi.workflows.DispatchOptions.independentDispatch;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.transactionWith;
import static com.hedera.node.app.util.FileUtilities.createFileID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.addressbook.NodeCreateTransactionBody;
import com.hedera.hapi.node.addressbook.NodeUpdateTransactionBody;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.CurrentAndNextFeeSchedule;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.impl.records.NodeCreateStreamBuilder;
import com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema;
import com.hedera.node.app.service.consensus.ConsensusService;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.node.app.service.networkadmin.impl.schemas.SyntheticNodeCreator;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.schemas.SyntheticAccountCreator;
import com.hedera.node.app.service.token.records.GenesisAccountStreamBuilder;
import com.hedera.node.app.service.token.records.TokenContext;
import com.hedera.node.app.spi.workflows.SystemContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.workflows.handle.Dispatch;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.StateLifecycles;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class is responsible for storing the system accounts created during node startup, and then creating
 * the corresponding synthetic records when a consensus time becomes available.
 */
@Singleton
public class SystemSetup {
    private static final Logger log = LogManager.getLogger(SystemSetup.class);

    private static final EnumSet<ResponseCodeEnum> SUCCESSES =
            EnumSet.of(SUCCESS, SUCCESS_BUT_MISSING_EXPECTED_OPERATION);

    private static final String SYSTEM_ACCOUNT_CREATION_MEMO = "Synthetic system creation";
    private static final String STAKING_MEMO = "Release 0.24.1 migration record";
    private static final String TREASURY_CLONE_MEMO = "Synthetic zero-balance treasury clone";
    private static final Comparator<Account> ACCOUNT_COMPARATOR =
            Comparator.comparing(Account::accountId, ACCOUNT_ID_COMPARATOR);
    public static final Comparator<Node> NODE_COMPARATOR = Comparator.comparing(Node::nodeId, Long::compare);

    private SortedSet<Account> systemAccounts = new TreeSet<>(ACCOUNT_COMPARATOR);
    private SortedSet<Account> stakingAccounts = new TreeSet<>(ACCOUNT_COMPARATOR);
    private SortedSet<Account> miscAccounts = new TreeSet<>(ACCOUNT_COMPARATOR);
    private SortedSet<Account> treasuryClones = new TreeSet<>(ACCOUNT_COMPARATOR);
    private SortedSet<Account> blocklistAccounts = new TreeSet<>(ACCOUNT_COMPARATOR);
    private SortedSet<Node> genesisNodes = new TreeSet<>(NODE_COMPARATOR);

    private final AtomicInteger nextDispatchNonce = new AtomicInteger(1);
    private final FileServiceImpl fileService;
    private final SyntheticAccountCreator syntheticAccountCreator;
    private final SyntheticNodeCreator syntheticNodeCreator;

    /**
     * Constructs a new {@link SystemSetup}.
     */
    @Inject
    public SystemSetup(
            @NonNull final FileServiceImpl fileService,
            @NonNull final SyntheticAccountCreator syntheticAccountCreator,
            @NonNull final SyntheticNodeCreator syntheticNodeCreator) {
        this.fileService = requireNonNull(fileService);
        this.syntheticAccountCreator = requireNonNull(syntheticAccountCreator);
        this.syntheticNodeCreator = requireNonNull(syntheticNodeCreator);
    }

    /**
     * Sets up genesis state for the system.
     *
     * @param dispatch the genesis transaction dispatch
     */
    public void doGenesisSetup(@NonNull final Dispatch dispatch) {
        final var systemContext = systemContextFor(dispatch);
        final var nodeStore = dispatch.handleContext().storeFactory().readableStore(ReadableNodeStore.class);
        fileService.createSystemEntities(systemContext, nodeStore);
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
                        SystemSetup::parseFeeSchedules),
                new AutoEntityUpdate<>(
                        (ctx, bytes) -> dispatchSynthFileUpdate(
                                ctx, createFileID(filesConfig.throttleDefinitions(), config), bytes),
                        adminConfig.upgradeThrottlesFile(),
                        SystemSetup::parseThrottles),
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
                SystemSetup::parseNodeAdminKeys);
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

    /**
     * Called only once, before handling the first transaction in network history. Externalizes
     * side effects of genesis setup done in
     * {@link StateLifecycles#onStateInitialized(MerkleNodeState, Platform, InitTrigger, SoftwareVersion)}.
     *
     * @throws NullPointerException if called more than once
     */
    public void externalizeInitSideEffects(
            @NonNull final TokenContext context, @NonNull final ExchangeRateSet exchangeRateSet) {
        requireNonNull(context);
        requireNonNull(exchangeRateSet);
        final var firstConsensusTime = context.consensusTime();
        log.info("Doing genesis setup at {}", firstConsensusTime);
        // The account creator registers all its synthetics accounts based on the
        // given genesis configuration via callbacks on this object, so following
        // references to our field sets are guaranteed to be non-empty as appropriate
        syntheticAccountCreator.generateSyntheticAccounts(
                context.configuration(),
                this::systemAccounts,
                this::stakingAccounts,
                this::treasuryClones,
                this::miscAccounts,
                this::blocklistAccounts);

        syntheticNodeCreator.generateSyntheticNodes(context.readableStore(ReadableNodeStore.class), this::nodes);

        if (!systemAccounts.isEmpty()) {
            createAccountRecordBuilders(systemAccounts, context, SYSTEM_ACCOUNT_CREATION_MEMO, exchangeRateSet);
            log.info(" - Queued {} system account records", systemAccounts.size());
            systemAccounts = null;
        }

        if (!treasuryClones.isEmpty()) {
            createAccountRecordBuilders(treasuryClones, context, TREASURY_CLONE_MEMO, exchangeRateSet);
            log.info("Queued {} treasury clone account records", treasuryClones.size());
            treasuryClones = null;
        }

        if (!stakingAccounts.isEmpty()) {
            final var implicitAutoRenewPeriod = FUNDING_ACCOUNT_EXPIRY - firstConsensusTime.getEpochSecond();
            createAccountRecordBuilders(
                    stakingAccounts, context, STAKING_MEMO, implicitAutoRenewPeriod, exchangeRateSet);
            log.info(" - Queued {} staking account records", stakingAccounts.size());
            stakingAccounts = null;
        }

        if (!miscAccounts.isEmpty()) {
            createAccountRecordBuilders(miscAccounts, context, null, exchangeRateSet);
            log.info("Queued {} misc account records", miscAccounts.size());
            miscAccounts = null;
        }

        if (!blocklistAccounts.isEmpty()) {
            createAccountRecordBuilders(blocklistAccounts, context, null, exchangeRateSet);
            log.info("Queued {} blocklist account records", blocklistAccounts.size());
            blocklistAccounts = null;
        }

        if (!genesisNodes.isEmpty()) {
            createNodeRecordBuilders(genesisNodes, context, exchangeRateSet);
            log.info(" - Queued {} node create records", genesisNodes.size());
            genesisNodes = null;
        }
    }

    private void systemAccounts(@NonNull final SortedSet<Account> accounts) {
        requireNonNull(systemAccounts, "Genesis records already exported").addAll(requireNonNull(accounts));
    }

    private void stakingAccounts(@NonNull final SortedSet<Account> accounts) {
        requireNonNull(stakingAccounts, "Genesis records already exported").addAll(requireNonNull(accounts));
    }

    private void miscAccounts(@NonNull final SortedSet<Account> accounts) {
        requireNonNull(miscAccounts, "Genesis records already exported").addAll(requireNonNull(accounts));
    }

    private void treasuryClones(@NonNull final SortedSet<Account> accounts) {
        requireNonNull(treasuryClones, "Genesis records already exported").addAll(requireNonNull(accounts));
    }

    private void blocklistAccounts(@NonNull final SortedSet<Account> accounts) {
        requireNonNull(blocklistAccounts, "Genesis records already exported").addAll(requireNonNull(accounts));
    }

    private void nodes(@NonNull final SortedSet<Node> nodes) {
        requireNonNull(genesisNodes, "Genesis records already exported").addAll(requireNonNull(nodes));
    }

    private void createAccountRecordBuilders(
            @NonNull final SortedSet<Account> map,
            @NonNull final TokenContext context,
            @Nullable final String recordMemo,
            @NonNull final ExchangeRateSet exchangeRateSet) {
        createAccountRecordBuilders(map, context, recordMemo, null, exchangeRateSet);
    }

    private void createNodeRecordBuilders(
            SortedSet<Node> nodes,
            @NonNull final TokenContext context,
            @NonNull final ExchangeRateSet exchangeRateSet) {
        for (final Node node : nodes) {
            final var recordBuilder =
                    context.addPrecedingChildRecordBuilder(NodeCreateStreamBuilder.class, NODE_CREATE);
            recordBuilder.nodeID(node.nodeId()).exchangeRate(exchangeRateSet);

            final var op = newNodeCreate(node);
            final var bodyBuilder = TransactionBody.newBuilder().nodeCreate(op);
            final var body = bodyBuilder.build();
            recordBuilder.transaction(transactionWith(body));
            recordBuilder.status(SUCCESS);

            log.debug("Queued synthetic NodeCreate for node {}", node);
        }
    }

    private void createAccountRecordBuilders(
            @NonNull final SortedSet<Account> accts,
            @NonNull final TokenContext context,
            @Nullable final String recordMemo,
            @Nullable final Long overrideAutoRenewPeriod,
            @NonNull final ExchangeRateSet exchangeRateSet) {
        for (final Account account : accts) {
            // Since this is only called at genesis, the active savepoint's preceding record capacity will be
            // Integer.MAX_VALUE and this will never fail with MAX_CHILD_RECORDS_EXCEEDED (c.f., HandleWorkflow)
            final var recordBuilder =
                    context.addPrecedingChildRecordBuilder(GenesisAccountStreamBuilder.class, CRYPTO_CREATE);
            recordBuilder.accountID(account.accountIdOrThrow()).exchangeRate(exchangeRateSet);
            if (recordMemo != null) {
                recordBuilder.memo(recordMemo);
            }

            final var op = newCryptoCreate(account);
            if (overrideAutoRenewPeriod != null) {
                op.autoRenewPeriod(Duration.newBuilder().seconds(overrideAutoRenewPeriod));
            }
            final var bodyBuilder = TransactionBody.newBuilder().cryptoCreateAccount(op);
            if (recordMemo != null) {
                bodyBuilder.memo(recordMemo);
            }
            final var body = bodyBuilder.build();
            recordBuilder.transaction(transactionWith(body));

            final var balance = account.tinybarBalance();
            if (balance != 0) {
                var accountID = AccountID.newBuilder()
                        .accountNum(account.accountId().accountNumOrElse(0L))
                        .shardNum(account.accountId().shardNum())
                        .realmNum(account.accountId().realmNum())
                        .build();

                recordBuilder.transferList(TransferList.newBuilder()
                        .accountAmounts(asAccountAmounts(Map.of(accountID, balance)))
                        .build());
            }
            recordBuilder.status(SUCCESS);

            log.debug("Queued synthetic CryptoCreate for {} account {}", recordMemo, account);
        }
    }

    private static CryptoCreateTransactionBody.Builder newCryptoCreate(@NonNull final Account account) {
        return CryptoCreateTransactionBody.newBuilder()
                .key(account.key())
                .memo(account.memo())
                .declineReward(account.declineReward())
                .receiverSigRequired(account.receiverSigRequired())
                .autoRenewPeriod(Duration.newBuilder()
                        .seconds(account.autoRenewSeconds())
                        .build())
                .initialBalance(account.tinybarBalance())
                .alias(account.alias());
    }

    private static NodeCreateTransactionBody.Builder newNodeCreate(Node node) {
        return NodeCreateTransactionBody.newBuilder()
                .accountId(node.accountId())
                .description(node.description())
                .gossipEndpoint(node.gossipEndpoint())
                .serviceEndpoint(node.serviceEndpoint())
                .gossipCaCertificate(node.gossipCaCertificate())
                .grpcCertificateHash(node.grpcCertificateHash())
                .adminKey(node.adminKey());
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
