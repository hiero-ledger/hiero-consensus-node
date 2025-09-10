// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.BUSY;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.blocks.BlockStreamManager.PendingWork.GENESIS_WORK;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCK_INFO_STATE_KEY;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.SCHEDULED;
import static com.hedera.node.app.state.logging.TransactionStateLogger.logStartEvent;
import static com.hedera.node.app.state.logging.TransactionStateLogger.logStartRound;
import static com.hedera.node.app.state.logging.TransactionStateLogger.logStartUserTransaction;
import static com.hedera.node.app.state.logging.TransactionStateLogger.logStartUserTransactionPreHandleResultP2;
import static com.hedera.node.app.state.logging.TransactionStateLogger.logStartUserTransactionPreHandleResultP3;
import static com.hedera.node.app.workflows.handle.TransactionType.ORDINARY_TRANSACTION;
import static com.hedera.node.app.workflows.handle.TransactionType.POST_UPGRADE_TRANSACTION;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static com.swirlds.platform.system.InitTrigger.EVENT_STREAM_RECOVERY;
import static java.time.Instant.EPOCH;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.input.EventHeader;
import com.hedera.hapi.block.stream.input.ParentEventReference;
import com.hedera.hapi.block.stream.input.RoundHeader;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.impl.ImmediateStateChangeListener;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.impl.ReadableHintsStoreImpl;
import com.hedera.node.app.hints.impl.WritableHintsStoreImpl;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.impl.WritableHistoryStoreImpl;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.info.CurrentPlatformStatus;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.roster.ActiveRosters;
import com.hedera.node.app.roster.RosterService;
import com.hedera.node.app.service.schedule.ExecutableTxn;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.WritableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import com.hedera.node.app.service.token.impl.handlers.staking.StakeInfoHelper;
import com.hedera.node.app.service.token.impl.handlers.staking.StakePeriodManager;
import com.hedera.node.app.services.NodeRewardManager;
import com.hedera.node.app.spi.api.ServiceApiProvider;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.state.HederaRecordCache.DueDiligenceFailure;
import com.hedera.node.app.state.recordcache.LegacyListRecordSource;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.store.StoreFactoryImpl;
import com.hedera.node.app.throttle.CongestionMetrics;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.workflows.OpWorkflowMetrics;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.handle.cache.CacheWarmer;
import com.hedera.node.app.workflows.handle.record.SystemTransactions;
import com.hedera.node.app.workflows.handle.steps.HollowAccountCompletions;
import com.hedera.node.app.workflows.handle.steps.ParentTxn;
import com.hedera.node.app.workflows.handle.steps.ParentTxnFactory;
import com.hedera.node.app.workflows.handle.steps.StakePeriodChanges;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.ConsensusConfig;
import com.hedera.node.config.data.SchedulingConfig;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.ReadablePlatformStateStore;
import com.swirlds.platform.state.service.WritablePlatformStateStore;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.roster.ReadableRosterStoreImpl;
import org.hiero.consensus.roster.WritableRosterStore;

/**
 * The handle workflow that is responsible for handling the next {@link Round} of transactions.
 */
@Singleton
public class HandleWorkflow {
    private static final Logger logger = LogManager.getLogger(HandleWorkflow.class);

    public static final String ALERT_MESSAGE = "Possibly CATASTROPHIC failure";
    public static final String SYSTEM_ENTITIES_CREATED_MSG = "System entities created";

    private final StreamMode streamMode;
    private final NetworkInfo networkInfo;
    private final StakePeriodChanges stakePeriodChanges;
    private final DispatchProcessor dispatchProcessor;
    private final BlockRecordManager blockRecordManager;
    private final BlockStreamManager blockStreamManager;
    private final CacheWarmer cacheWarmer;
    private final OpWorkflowMetrics opWorkflowMetrics;
    private final ThrottleServiceManager throttleServiceManager;
    private final InitTrigger initTrigger;
    private final HollowAccountCompletions hollowAccountCompletions;
    private final SystemTransactions systemTransactions;
    private final StakeInfoHelper stakeInfoHelper;
    private final HederaRecordCache recordCache;
    private final ExchangeRateManager exchangeRateManager;
    private final StakePeriodManager stakePeriodManager;
    private final List<StateChanges.Builder> migrationStateChanges;
    private final ParentTxnFactory parentTxnFactory;
    private final HintsService hintsService;
    private final HistoryService historyService;
    private final ConfigProvider configProvider;
    private final ImmediateStateChangeListener immediateStateChangeListener;
    private final ScheduleService scheduleService;
    private final CongestionMetrics congestionMetrics;
    private final CurrentPlatformStatus currentPlatformStatus;
    private final BlockHashSigner blockHashSigner;
    private final BlockBufferService blockBufferService;
    private final Map<Class<?>, ServiceApiProvider<?>> apiProviders;

    @Nullable
    private final AtomicBoolean systemEntitiesCreatedFlag;

    // The last second since the epoch at which the metrics were updated; this does not affect transaction handling
    private long lastMetricUpdateSecond;
    // The last second for which this workflow has confirmed all scheduled transactions are executed
    private long lastExecutedSecond;
    private final NodeRewardManager nodeRewardManager;
    private final PlatformStateFacade platformStateFacade;
    // Flag to indicate whether we have checked for transplant updates after JVM started
    private boolean checkedForTransplant;
    // Flag whether the 0.65 system account cleanup has been done; can be removed after that release
    private boolean systemAccountCleanupDone;

    @Inject
    public HandleWorkflow(
            @NonNull final NetworkInfo networkInfo,
            @NonNull final StakePeriodChanges stakePeriodChanges,
            @NonNull final DispatchProcessor dispatchProcessor,
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockRecordManager blockRecordManager,
            @NonNull final BlockStreamManager blockStreamManager,
            @NonNull final CacheWarmer cacheWarmer,
            @NonNull final OpWorkflowMetrics opWorkflowMetrics,
            @NonNull final ThrottleServiceManager throttleServiceManager,
            @NonNull final InitTrigger initTrigger,
            @NonNull final HollowAccountCompletions hollowAccountCompletions,
            @NonNull final SystemTransactions systemTransactions,
            @NonNull final StakeInfoHelper stakeInfoHelper,
            @NonNull final HederaRecordCache recordCache,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final StakePeriodManager stakePeriodManager,
            @NonNull final List<StateChanges.Builder> migrationStateChanges,
            @NonNull final ParentTxnFactory parentTxnFactory,
            @NonNull final ImmediateStateChangeListener immediateStateChangeListener,
            @NonNull final ScheduleService scheduleService,
            @NonNull final HintsService hintsService,
            @NonNull final HistoryService historyService,
            @NonNull final CongestionMetrics congestionMetrics,
            @NonNull final CurrentPlatformStatus currentPlatformStatus,
            @NonNull final BlockHashSigner blockHashSigner,
            @Nullable final AtomicBoolean systemEntitiesCreatedFlag,
            @NonNull final NodeRewardManager nodeRewardManager,
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final BlockBufferService blockBufferService,
            @NonNull final Map<Class<?>, ServiceApiProvider<?>> apiProviders) {
        this.networkInfo = requireNonNull(networkInfo);
        this.stakePeriodChanges = requireNonNull(stakePeriodChanges);
        this.dispatchProcessor = requireNonNull(dispatchProcessor);
        this.blockRecordManager = requireNonNull(blockRecordManager);
        this.blockStreamManager = requireNonNull(blockStreamManager);
        this.cacheWarmer = requireNonNull(cacheWarmer);
        this.opWorkflowMetrics = requireNonNull(opWorkflowMetrics);
        this.throttleServiceManager = requireNonNull(throttleServiceManager);
        this.initTrigger = requireNonNull(initTrigger);
        this.hollowAccountCompletions = requireNonNull(hollowAccountCompletions);
        this.systemTransactions = requireNonNull(systemTransactions);
        this.stakeInfoHelper = requireNonNull(stakeInfoHelper);
        this.recordCache = requireNonNull(recordCache);
        this.exchangeRateManager = requireNonNull(exchangeRateManager);
        this.stakePeriodManager = requireNonNull(stakePeriodManager);
        this.migrationStateChanges = new ArrayList<>(migrationStateChanges);
        this.parentTxnFactory = requireNonNull(parentTxnFactory);
        this.configProvider = requireNonNull(configProvider);
        this.immediateStateChangeListener = requireNonNull(immediateStateChangeListener);
        this.scheduleService = requireNonNull(scheduleService);
        this.congestionMetrics = requireNonNull(congestionMetrics);
        this.streamMode = configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .streamMode();
        this.hintsService = requireNonNull(hintsService);
        this.historyService = requireNonNull(historyService);
        this.blockHashSigner = requireNonNull(blockHashSigner);
        this.currentPlatformStatus = requireNonNull(currentPlatformStatus);
        this.nodeRewardManager = requireNonNull(nodeRewardManager);
        this.systemEntitiesCreatedFlag = systemEntitiesCreatedFlag;
        this.platformStateFacade = requireNonNull(platformStateFacade);
        this.blockBufferService = requireNonNull(blockBufferService);
        this.apiProviders = requireNonNull(apiProviders);
    }

    /**
     * Handles the next {@link Round}
     *
     * @param state the writable {@link State} that this round will work on
     * @param round the next {@link Round} that needs to be processed
     * @param stateSignatureTxnCallback A callback to be called when encountering a {@link StateSignatureTransaction}
     */
    public void handleRound(
            @NonNull final State state,
            @NonNull final Round round,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTxnCallback) {
        logStartRound(round);
        blockBufferService.ensureNewBlocksPermitted();
        cacheWarmer.warm(state, round);
        if (streamMode != RECORDS) {
            blockStreamManager.startRound(round, state);
            blockStreamManager.writeItem(BlockItem.newBuilder()
                    .roundHeader(new RoundHeader(round.getRoundNum()))
                    .build());
            if (!migrationStateChanges.isEmpty()) {
                final var startupConsTime = systemTransactions.firstReservedSystemTimeFor(
                        round.iterator().next().getConsensusTimestamp());
                migrationStateChanges.forEach(builder -> blockStreamManager.writeItem(BlockItem.newBuilder()
                        .stateChanges(builder.consensusTimestamp(asTimestamp(startupConsTime))
                                .build())
                        .build()));
                migrationStateChanges.clear();
            }
        }
        systemTransactions.resetNextDispatchNonce();
        recordCache.resetRoundReceipts();
        boolean transactionsDispatched = false;

        // Dispatch transplant updates for the nodes in override network (non-prod environments);
        // ensure we don't do this in the same round as externalizing migration state changes to
        // avoid complicated edge cases in setting consensus times for block items
        if (migrationStateChanges.isEmpty() && !checkedForTransplant) {
            boolean dispatchedTransplantUpdates = false;
            try {
                final var now = streamMode == RECORDS
                        ? round.getConsensusTimestamp()
                        : round.iterator().next().getConsensusTimestamp();
                dispatchedTransplantUpdates =
                        systemTransactions.dispatchTransplantUpdates(state, now, round.getRoundNum());
                transactionsDispatched |= dispatchedTransplantUpdates;
            } catch (Exception e) {
                logger.error("Failed to dispatch transplant updates", e);
            } finally {
                checkedForTransplant = true;
                if (dispatchedTransplantUpdates) {
                    final var writableStates = state.getWritableStates(RosterService.NAME);
                    final var writableRosterStore = new WritableRosterStore(writableStates);
                    writableRosterStore.updateTransplantInProgress(false);
                    ((CommittableWritableStates) writableStates).commit();
                    logger.info("Transplant in progress is set to false in the roster store");
                }
            }
        }

        // If only producing a record stream, no reason to do any TSS work (since it is
        // output exclusively in a block stream)
        if (streamMode != RECORDS) {
            configureTssCallbacks(state);
            try {
                reconcileTssState(state, round.getConsensusTimestamp());
            } catch (Exception e) {
                logger.error("{} trying to reconcile TSS state", ALERT_MESSAGE, e);
            }
        }
        try {
            final int receiptEntriesBatchSize = configProvider
                    .getConfiguration()
                    .getConfigData(BlockStreamConfig.class)
                    .receiptEntriesBatchSize();
            transactionsDispatched |= handleEvents(state, round, receiptEntriesBatchSize, stateSignatureTxnCallback);
            try {
                final var lastConsTime = streamMode == RECORDS
                        ? blockRecordManager.lastUsedConsensusTime()
                        : blockStreamManager.lastUsedConsensusTime();
                if (lastConsTime.isAfter(EPOCH)) {
                    transactionsDispatched |= nodeRewardManager.maybeRewardActiveNodes(
                            state, lastConsTime.plusNanos(1), systemTransactions);
                }
            } catch (Exception e) {
                logger.warn("Failed to reward active nodes", e);
            }
            // Inform the BlockRecordManager that the round is complete, so it can update running hashes in state
            // from results computed in background threads. The running hash has to be included in state, but we want
            // to synchronize with background threads as infrequently as possible; per round is the best we can do
            // from the perspective of the legacy record stream.
            if (transactionsDispatched && streamMode != BLOCKS) {
                blockRecordManager.endRound(state);
            }

            // Update the latest freeze round after everything is handled
            if (platformStateFacade.isFreezeRound(state, round)) {
                // If this is a freeze round, we need to update the freeze info state
                final var platformStateStore =
                        new WritablePlatformStateStore(state.getWritableStates(PlatformStateService.NAME));
                platformStateStore.setLatestFreezeRound(round.getRoundNum());
            }
        } finally {
            // Even if there is an exception somewhere, we need to commit the receipts of any handled transactions
            // to the state so these transactions cannot be replayed in future rounds
            recordCache.commitReceipts(
                    state, round.getConsensusTimestamp(), immediateStateChangeListener, blockStreamManager, streamMode);
        }
    }

    /**
     * Applies all effects of the events in the given round to the given state, writing stream items
     * that capture these effects in the process.
     *
     * @param state the state to apply the effects to
     * @param round the round to apply the effects of
     * @param receiptEntriesBatchSize The maximum number of receipts to accumulate in a batch before committing
     * @param stateSignatureTxnCallback A callback to be called when encountering a {@link StateSignatureTransaction}
     */
    private boolean handleEvents(
            @NonNull final State state,
            @NonNull final Round round,
            final int receiptEntriesBatchSize,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTxnCallback) {
        boolean transactionsDispatched = false;
        final var storeFactory = new ReadableStoreFactory(state);
        final var platformStateStore = storeFactory.getStore(ReadablePlatformStateStore.class);
        for (final var event : round) {
            if (streamMode != RECORDS) {
                writeEventHeader(event);
            }
            final var creator = networkInfo.nodeInfo(event.getCreatorId().id());
            if (creator == null) {
                if (event.getEventCore().birthRound() > platformStateStore.getLatestFreezeRound()) {
                    // We were given an event for a node that does not exist in the address book and was not from
                    // a strictly earlier birth round number prior to the last freeze round number. This will be logged
                    // as a warning, as this should never happen, and we will skip the event. The platform should
                    // guarantee that we never receive an event that isn't associated with the address book, and every
                    // node in the address book must have an account ID, since you cannot delete an account belonging
                    // to a node, and you cannot change the address book non-deterministically.
                    logger.warn(
                            "Received event with birth round {}, last freeze round is {}, from node {} "
                                    + "which is not in the address book",
                            event.getEventCore().birthRound(),
                            platformStateStore.getLatestFreezeRound(),
                            event.getCreatorId());
                }
                continue;
            }

            final Consumer<StateSignatureTransaction> simplifiedStateSignatureTxnCallback = txn -> {
                final var scopedTxn = new ScopedSystemTransaction<>(event.getCreatorId(), event.getBirthRound(), txn);
                stateSignatureTxnCallback.accept(scopedTxn);
            };

            // log start of event to transaction state log
            logStartEvent(event, creator);
            // handle each transaction of the event
            for (final var it = event.consensusTransactionIterator(); it.hasNext(); ) {
                final var platformTxn = it.next();
                try {
                    transactionsDispatched |= handlePlatformTransaction(
                            state,
                            creator,
                            platformTxn,
                            event.getEventCore().birthRound(),
                            simplifiedStateSignatureTxnCallback);
                } catch (final Exception e) {
                    logger.fatal(
                            "Possibly CATASTROPHIC failure while running the handle workflow. "
                                    + "While this node may not die right away, it is in a bad way, most likely fatally.",
                            e);
                }
            }
            recordCache.maybeCommitReceiptsBatch(
                    state,
                    round.getConsensusTimestamp(),
                    immediateStateChangeListener,
                    receiptEntriesBatchSize,
                    blockStreamManager,
                    streamMode);
        }
        final boolean isGenesis =
                switch (streamMode) {
                    case RECORDS ->
                        blockRecordManager.consTimeOfLastHandledTxn().equals(EPOCH);
                    case BLOCKS, BOTH -> blockStreamManager.pendingWork() == GENESIS_WORK;
                };
        if (isGenesis) {
            final var genesisEventTime = round.iterator().next().getConsensusTimestamp();
            logger.info("Doing genesis setup before {}", genesisEventTime);
            systemTransactions.doGenesisSetup(genesisEventTime, state);
            transactionsDispatched = true;
            if (streamMode != RECORDS) {
                blockStreamManager.confirmPendingWorkFinished();
            }
            logger.info(SYSTEM_ENTITIES_CREATED_MSG);
            requireNonNull(systemEntitiesCreatedFlag).set(true);
        }
        // Update all throttle metrics once per round
        throttleServiceManager.updateAllMetrics();
        return transactionsDispatched;
    }

    /**
     * Writes an event header to the block stream. The event header contains:
     * 1. The event core data
     * 2. References to parent events (either as event descriptors or indices)
     * 3. A boolean, which if true, the middle bit of the event's signature is set.
     * <p>
     * The method first tracks the event hash in the block stream manager, then builds a list of parent
     * event references. For each parent event, it either:
     * - Uses the full event descriptor if the parent is not in the current block
     * - Uses an index reference if the parent is in the current block
     *
     * @param event the consensus event to write the header for
     */
    private void writeEventHeader(ConsensusEvent event) {
        blockStreamManager.trackEventHash(event.getHash());
        List<ParentEventReference> parents = new ArrayList<>();
        final Iterator<EventDescriptorWrapper> iterator = event.allParentsIterator();
        while (iterator.hasNext()) {
            final EventDescriptorWrapper parent = iterator.next();
            Optional<Integer> parentHash = blockStreamManager.getEventIndex(parent.hash());
            if (parentHash.isEmpty()) {
                parents.add(ParentEventReference.newBuilder()
                        .eventDescriptor(parent.eventDescriptor())
                        .build());
            } else {
                parents.add(ParentEventReference.newBuilder()
                        .index(parentHash.get())
                        .build());
            }
        }
        final var headerItem = BlockItem.newBuilder()
                .eventHeader(new EventHeader(event.getEventCore(), parents))
                .build();
        blockStreamManager.writeItem(headerItem);
    }

    /**
     * Handles a platform transaction. This method is responsible for creating a {@link ParentTxn} and
     * executing the workflow for the transaction. This produces a stream of records that are then passed to the
     * {@link BlockRecordManager} to be externalized.
     *
     * @param state the writable {@link State} that this transaction will work on
     * @param creator the {@link NodeInfo} of the creator of the transaction
     * @param txn the {@link ConsensusTransaction} to be handled
     * @param eventBirthRound the birth round of the event that this transaction belongs to
     * @return {@code true} if the transaction was a user transaction, {@code false} if a system transaction
     */
    private boolean handlePlatformTransaction(
            @NonNull final State state,
            @NonNull final NodeInfo creator,
            @NonNull final ConsensusTransaction txn,
            final long eventBirthRound,
            @NonNull final Consumer<StateSignatureTransaction> stateSignatureTxnCallback) {
        final var handleStart = System.nanoTime();

        // Always use platform-assigned time for user transaction, c.f. https://hips.hedera.com/hip/hip-993
        final var consensusNow = txn.getConsensusTimestamp();
        var type = ORDINARY_TRANSACTION;
        stakePeriodManager.setCurrentStakePeriodFor(consensusNow);
        boolean startsNewRecordFile = false;
        if (streamMode != BLOCKS) {
            startsNewRecordFile = blockRecordManager.willOpenNewBlock(consensusNow, state);
            if (streamMode == RECORDS && startsNewRecordFile) {
                type = typeOfBoundary(state);
            }
        }
        if (streamMode != RECORDS) {
            type = switch (blockStreamManager.pendingWork()) {
                case POST_UPGRADE_WORK -> POST_UPGRADE_TRANSACTION;
                default -> ORDINARY_TRANSACTION;
            };
        }
        if (type == POST_UPGRADE_TRANSACTION) {
            logger.info("Doing post-upgrade setup @ {}", consensusNow);
            systemTransactions.doPostUpgradeSetup(consensusNow, state);
            if (streamMode != RECORDS) {
                blockStreamManager.confirmPendingWorkFinished();
            }
            // Since we track node stake metadata separately from the future address book (FAB),
            // we need to update that stake metadata from any node additions or deletions that
            // just took effect; it would be nice to unify the FAB and stake metadata in the future.
            final var writableTokenStates = state.getWritableStates(TokenService.NAME);
            final var writableEntityIdStates = state.getWritableStates(EntityIdService.NAME);
            doStreamingKVChanges(
                    writableTokenStates,
                    writableEntityIdStates,
                    () -> stakeInfoHelper.adjustPostUpgradeStakes(
                            networkInfo,
                            configProvider.getConfiguration(),
                            new WritableStakingInfoStore(
                                    writableTokenStates, new WritableEntityIdStore(writableEntityIdStates)),
                            new WritableNetworkStakingRewardsStore(writableTokenStates)));
            if (streamMode == RECORDS) {
                // Only update this if we are relying on RecordManager state for post-upgrade processing
                blockRecordManager.markMigrationRecordsStreamed();
            }
        } else {
            if (!systemAccountCleanupDone) {
                // Ensure the system account cleanup is finished post-upgrade
                systemAccountCleanupDone = systemTransactions.do066SystemAccountCleanup(consensusNow, state);
            }
        }
        final var userTxn =
                parentTxnFactory.createUserTxn(state, creator, txn, consensusNow, stateSignatureTxnCallback);
        if (userTxn == null) {
            return false;
        } else if (streamMode != BLOCKS && startsNewRecordFile) {
            blockRecordManager.startUserTransaction(consensusNow, state);
        }

        final var handleOutput = executeSubmittedParent(userTxn, eventBirthRound, state);
        if (streamMode != BLOCKS) {
            final var records = ((LegacyListRecordSource) handleOutput.recordSourceOrThrow()).precomputedRecords();
            blockRecordManager.endUserTransaction(records.stream(), state);
        }
        if (streamMode != RECORDS) {
            handleOutput.blockRecordSourceOrThrow().forEachItem(blockStreamManager::writeItem);
        } else if (handleOutput.lastAssignedConsensusTime().isAfter(consensusNow)) {
            blockRecordManager.setLastUsedConsensusTime(handleOutput.lastAssignedConsensusTime(), state);
        }

        opWorkflowMetrics.updateDuration(userTxn.functionality(), (int) (System.nanoTime() - handleStart));
        congestionMetrics.updateMultiplier(userTxn.txnInfo(), userTxn.readableStoreFactory());

        var executionStart = streamMode == RECORDS
                ? blockRecordManager.lastIntervalProcessTime()
                : blockStreamManager.lastIntervalProcessTime();
        if (executionStart.equals(EPOCH)) {
            executionStart = userTxn.consensusNow();
        }
        try {
            // We execute as many schedules expiring in [lastIntervalProcessTime, consensusNow]
            // as there are available consensus times and execution slots (ordinarily there will
            // be more than enough of both, but we must be prepared for the edge cases)
            executeAsManyScheduled(state, executionStart, userTxn.consensusNow(), userTxn.creatorInfo());
        } catch (Exception e) {
            logger.error(
                    "{} - unhandled exception while executing schedules between [{}, {}]",
                    ALERT_MESSAGE,
                    executionStart,
                    userTxn.consensusNow(),
                    e);
            // This should never happen, but if it does, we skip over everything in the interval to
            // avoid being stuck in a crash loop here
            if (streamMode != RECORDS) {
                blockStreamManager.setLastIntervalProcessTime(userTxn.consensusNow());
            } else {
                blockRecordManager.setLastIntervalProcessTime(userTxn.consensusNow(), state);
            }
        }
        return true;
    }

    /**
     * Executes as many transactions scheduled to expire in the interval {@code [executionStart, consensusNow]} as
     * possible from the given state, given some context of the triggering user transaction.
     * <p>
     * As a side effect on the workflow internal state, updates the {@link BlockStreamManager}'s last interval process
     * time to the latest time known to have been processed; and the {@link #lastExecutedSecond} value to the last
     * second of the interval for which all scheduled transactions were executed.
     *
     * @param state the state to execute scheduled transactions from
     * @param executionStart the start of the interval to execute transactions in
     * @param consensusNow the consensus time at which the user transaction triggering this execution was processed
     * @param creatorInfo the node info of the user transaction creator
     */
    private void executeAsManyScheduled(
            @NonNull final State state,
            @NonNull final Instant executionStart,
            @NonNull final Instant consensusNow,
            @NonNull final NodeInfo creatorInfo) {
        // Non-final right endpoint of the execution interval, in case we cannot do all the scheduled work
        var executionEnd = consensusNow;
        // We only construct an Iterator<ExecutableTxn> if this is not genesis, and we haven't already
        // created and exhausted iterators through the last second in the interval
        if (executionEnd.getEpochSecond() > lastExecutedSecond) {
            final var config = configProvider.getConfiguration();
            final var schedulingConfig = config.getConfigData(SchedulingConfig.class);
            final var consensusConfig = config.getConfigData(ConsensusConfig.class);
            // Since the next platform-assigned consensus time may be as early as (now + separationNanos),
            // we must ensure that even if the last scheduled execution time is followed by the maximum
            // number of child transactions, the last child's assigned time will be strictly before the
            // first of the next consensus time's possible preceding children; that is, strictly before
            // (now + separationNanos - reservedSystemTxnNanos) - (maxAfter + maxBefore + 1)
            final var lastUsableTime = consensusNow.plusNanos(schedulingConfig.consTimeSeparationNanos()
                    - schedulingConfig.reservedSystemTxnNanos()
                    - (consensusConfig.handleMaxFollowingRecords() + consensusConfig.handleMaxPrecedingRecords() + 1));
            // The first possible time for the next execution is strictly after the last execution time
            // consumed for the triggering user transaction; plus the maximum number of preceding children
            var lastTime = streamMode == RECORDS
                    ? blockRecordManager.lastUsedConsensusTime()
                    : blockStreamManager.lastUsedConsensusTime();
            var nextTime = lastTime.plusNanos(consensusConfig.handleMaxPrecedingRecords() + 1);
            final var entityIdWritableStates = state.getWritableStates(EntityIdService.NAME);
            final var writableEntityIdStore = new WritableEntityIdStore(entityIdWritableStates);
            // Now we construct the iterator and start executing transactions in this interval
            final var iter = scheduleService.executableTxns(
                    executionStart,
                    consensusNow,
                    StoreFactoryImpl.from(state, ScheduleService.NAME, config, writableEntityIdStore, apiProviders));

            final var writableStates = state.getWritableStates(ScheduleService.NAME);
            // Configuration sets a maximum number of execution slots per user transaction
            int n = schedulingConfig.maxExecutionsPerUserTxn();
            while (iter.hasNext() && !nextTime.isAfter(lastUsableTime) && n > 0) {
                final var executableTxn = iter.next();
                if (schedulingConfig.longTermEnabled()) {
                    stakePeriodManager.setCurrentStakePeriodFor(nextTime);
                    if (streamMode != BLOCKS) {
                        blockRecordManager.startUserTransaction(nextTime, state);
                    }
                    final var handleOutput = executeScheduled(state, nextTime, creatorInfo, executableTxn);
                    if (streamMode != RECORDS) {
                        handleOutput.blockRecordSourceOrThrow().forEachItem(blockStreamManager::writeItem);
                    } else if (handleOutput.lastAssignedConsensusTime().isAfter(consensusNow)) {
                        blockRecordManager.setLastUsedConsensusTime(handleOutput.lastAssignedConsensusTime(), state);
                    }
                    if (streamMode != BLOCKS) {
                        final var records =
                                ((LegacyListRecordSource) handleOutput.recordSourceOrThrow()).precomputedRecords();
                        blockRecordManager.endUserTransaction(records.stream(), state);
                    }
                }
                executionEnd = executableTxn.nbf();
                doStreamingKVChanges(writableStates, entityIdWritableStates, iter::remove);
                lastTime = streamMode == RECORDS
                        ? blockRecordManager.lastUsedConsensusTime()
                        : blockStreamManager.lastUsedConsensusTime();
                nextTime = lastTime.plusNanos(consensusConfig.handleMaxPrecedingRecords() + 1);
                n--;
            }
            // The purgeUntilNext() iterator extension purges any schedules with wait_until_expiry=false
            // that expire after the last schedule returned from next(), until either the next executable
            // schedule or the iterator boundary is reached
            doStreamingKVChanges(writableStates, entityIdWritableStates, iter::purgeUntilNext);
            // If the iterator is not exhausted, we can only mark the second _before_ the last-executed NBF time
            // as complete; if it is exhausted, we mark the rightmost second of the interval as complete
            if (iter.hasNext()) {
                lastExecutedSecond = executionEnd.getEpochSecond() - 1;
            } else {
                // We exhausted the iterator, so jump back ahead to the interval right endpoint
                executionEnd = consensusNow;
                lastExecutedSecond = consensusNow.getEpochSecond();
            }
        }
        // Update our last-processed time with where we ended
        if (streamMode != RECORDS) {
            blockStreamManager.setLastIntervalProcessTime(executionEnd);
        } else {
            blockRecordManager.setLastIntervalProcessTime(executionEnd, state);
        }
    }

    /**
     * Type inference helper to compute the base builder for a {@link ParentTxn} derived from a
     * {@link ExecutableTxn}.
     *
     * @param <T> the type of the stream builder
     * @param executableTxn the executable transaction to compute the base builder for
     * @param parentTxn the user transaction derived from the executable transaction
     * @return the base builder for the user transaction
     */
    private <T extends StreamBuilder> T baseBuilderFor(
            @NonNull final ExecutableTxn<T> executableTxn, @NonNull final ParentTxn parentTxn) {
        return parentTxn.initBaseBuilder(
                exchangeRateManager.exchangeRates(), executableTxn.builderType(), executableTxn.builderSpec());
    }

    /**
     * Executes the user transaction and returns the output that should be externalized in the
     * block stream. (And if still producing records, the precomputed records.)
     * <p>
     * Never throws an exception without a fundamental breakdown of the system invariants. If
     * there is an internal error when executing the transaction, returns stream output of
     * just the transaction with a {@link ResponseCodeEnum#FAIL_INVALID} transaction result,
     * and no other side effects.
     *
     * @param parentTxn the user transaction to execute
     * @param state the state to commit any direct changes against
     * @param eventBirthRound the round in which the event was born
     * @return the stream output from executing the transaction
     */
    private HandleOutput executeSubmittedParent(
            @NonNull final ParentTxn parentTxn, final long eventBirthRound, @NonNull final State state) {
        try {
            final var platformStateStore =
                    new ReadablePlatformStateStore(state.getReadableStates(PlatformStateService.NAME));
            if (this.initTrigger != EVENT_STREAM_RECOVERY
                    && eventBirthRound <= platformStateStore.getLatestFreezeRound()) {
                if (streamMode != RECORDS) {
                    blockStreamManager.setLastTopLevelTime(parentTxn.consensusNow());
                }
                if (streamMode != BLOCKS) {
                    blockRecordManager.setLastTopLevelTime(parentTxn.consensusNow(), parentTxn.state());
                }
                initializeBuilderInfo(parentTxn.baseBuilder(), parentTxn.txnInfo(), exchangeRateManager.exchangeRates())
                        .status(BUSY);
                // Flushes the BUSY builder to the stream, no other side effects
                parentTxn.stack().commitTransaction(parentTxn.baseBuilder());
            } else {
                final var dispatch = parentTxnFactory.createDispatch(parentTxn, exchangeRateManager.exchangeRates());
                stakePeriodChanges.advanceTimeTo(parentTxn, true);
                logPreDispatch(parentTxn);
                hollowAccountCompletions.completeHollowAccounts(parentTxn, dispatch);
                dispatchProcessor.processDispatch(dispatch);
                updateWorkflowMetrics(parentTxn);
            }
            final var handleOutput =
                    parentTxn.stack().buildHandleOutput(parentTxn.consensusNow(), exchangeRateManager.exchangeRates());
            recordCache.addRecordSource(
                    parentTxn.creatorInfo().nodeId(),
                    parentTxn.txnInfo().transactionID(),
                    parentTxn.preHandleResult().dueDiligenceFailure(),
                    handleOutput.preferringBlockRecordSource());
            return handleOutput;
        } catch (final Exception e) {
            logger.error("{} - exception thrown while handling user transaction", ALERT_MESSAGE, e);
            return HandleOutput.failInvalidStreamItems(
                    parentTxn, exchangeRateManager.exchangeRates(), streamMode, recordCache);
        }
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
     * @param consensusNow the time to execute the transaction at
     * @return the stream output from executing the transaction
     */
    private HandleOutput executeScheduled(
            @NonNull final State state,
            @NonNull final Instant consensusNow,
            @NonNull final NodeInfo creatorInfo,
            @NonNull final ExecutableTxn<? extends StreamBuilder> executableTxn) {
        final var scheduledTxn = parentTxnFactory.createSystemTxn(
                state, creatorInfo, consensusNow, ORDINARY_TRANSACTION, executableTxn.payerId(), executableTxn.body());
        final var baseBuilder = baseBuilderFor(executableTxn, scheduledTxn);
        final var dispatch =
                parentTxnFactory.createDispatch(scheduledTxn, baseBuilder, executableTxn.keyVerifier(), SCHEDULED);
        stakePeriodChanges.advanceTimeTo(scheduledTxn, true);
        try {
            dispatchProcessor.processDispatch(dispatch);
            final var handleOutput = scheduledTxn
                    .stack()
                    .buildHandleOutput(scheduledTxn.consensusNow(), exchangeRateManager.exchangeRates());
            recordCache.addRecordSource(
                    scheduledTxn.creatorInfo().nodeId(),
                    scheduledTxn.txnInfo().transactionID(),
                    DueDiligenceFailure.NO,
                    handleOutput.preferringBlockRecordSource());
            return handleOutput;
        } catch (final Exception e) {
            logger.error("{} - exception thrown while handling scheduled transaction", ALERT_MESSAGE, e);
            return HandleOutput.failInvalidStreamItems(
                    scheduledTxn, exchangeRateManager.exchangeRates(), streamMode, recordCache);
        }
    }

    /**
     * Commits an action with side effects while capturing its key/value state changes and writing them to the
     * block stream.
     *
     * @param writableStates the writable states to commit the action to
     * @param entityIdWritableStates if not null, the writable states for the entity ID service
     * @param action the action to commit
     */
    private void doStreamingKVChanges(
            @NonNull final WritableStates writableStates,
            @Nullable final WritableStates entityIdWritableStates,
            @NonNull final Runnable action) {
        if (streamMode != RECORDS) {
            immediateStateChangeListener.resetKvStateChanges(null);
        }
        action.run();
        ((CommittableWritableStates) writableStates).commit();
        if (entityIdWritableStates != null) {
            ((CommittableWritableStates) entityIdWritableStates).commit();
        }
        if (streamMode != RECORDS) {
            final var changes = immediateStateChangeListener.getKvStateChanges();
            if (!changes.isEmpty()) {
                blockStreamManager.writeItem((now) -> BlockItem.newBuilder()
                        .stateChanges(new StateChanges(now, new ArrayList<>(changes)))
                        .build());
            }
        }
    }

    /**
     * Updates the metrics for the handle workflow.
     */
    private void updateWorkflowMetrics(@NonNull final ParentTxn parentTxn) {
        if (parentTxn.consensusNow().getEpochSecond() > lastMetricUpdateSecond) {
            opWorkflowMetrics.switchConsensusSecond();
            lastMetricUpdateSecond = parentTxn.consensusNow().getEpochSecond();
        }
    }

    /**
     * Initializes the base builder of the given user transaction initialized with its transaction information. The
     * record builder is initialized with the {@link SignedTransaction}, its original serialization, its transaction
     * id, and memo; as well as the exchange rate.
     *
     * @param builder the base builder
     * @param txnInfo the transaction information
     * @param exchangeRateSet the active exchange rate set
     * @return the initialized base builder
     */
    public static StreamBuilder initializeBuilderInfo(
            @NonNull final StreamBuilder builder,
            @NonNull final TransactionInfo txnInfo,
            @NonNull final ExchangeRateSet exchangeRateSet) {
        return builder.signedTx(txnInfo.signedTx())
                .functionality(txnInfo.functionality())
                .serializedSignedTx(txnInfo.serializedSignedTx())
                .transactionID(txnInfo.txBody().transactionIDOrThrow())
                .exchangeRate(exchangeRateSet)
                .memo(txnInfo.txBody().memo());
    }

    /**
     * Configure the TSS callbacks for the given state.
     *
     * @param state the latest state
     */
    private void configureTssCallbacks(@NonNull final State state) {
        final var tssConfig = configProvider.getConfiguration().getConfigData(TssConfig.class);
        if (tssConfig.hintsEnabled()) {
            hintsService.onFinishedConstruction((hintsStore, construction, context) -> {
                // On finishing the genesis construction, use it immediately no matter what
                if (hintsStore.getActiveConstruction().constructionId() == construction.constructionId()) {
                    context.setConstruction(construction);
                } else if (!tssConfig.historyEnabled()) {
                    // When not using history proofs, completing a weight rotation is also immediately actionable
                    final var rosterStore = new ReadableRosterStoreImpl(state.getReadableStates(RosterService.NAME));
                    if (rosterStore.candidateIsWeightRotation()) {
                        hintsService.manageRosterAdoption(
                                hintsStore,
                                requireNonNull(rosterStore.getActiveRoster()),
                                requireNonNull(rosterStore.getCandidateRoster()),
                                requireNonNull(rosterStore.getCandidateRosterHash()),
                                tssConfig.forceHandoffs());
                    }
                }
            });
        }
        if (tssConfig.historyEnabled()) {
            historyService.onFinishedConstruction((historyStore, construction) -> {
                if (historyStore.getActiveConstruction().constructionId() == construction.constructionId()) {
                    // History service has no other action to take on finishing the genesis construction
                    return;
                }
                final var rosterStore = new ReadableRosterStoreImpl(state.getReadableStates(RosterService.NAME));
                if (rosterStore.candidateIsWeightRotation()) {
                    historyStore.handoff(
                            requireNonNull(rosterStore.getActiveRoster()),
                            requireNonNull(rosterStore.getCandidateRoster()),
                            requireNonNull(rosterStore.getCandidateRosterHash()));
                    if (tssConfig.hintsEnabled()) {
                        final var writableHintsStates = state.getWritableStates(HintsService.NAME);
                        final var writableEntityStates = state.getWritableStates(EntityIdService.NAME);
                        final var entityCounters = new WritableEntityIdStore(writableEntityStates);
                        final var hintsStore = new WritableHintsStoreImpl(writableHintsStates, entityCounters);
                        hintsService.manageRosterAdoption(
                                hintsStore,
                                requireNonNull(rosterStore.getActiveRoster()),
                                requireNonNull(rosterStore.getCandidateRoster()),
                                requireNonNull(rosterStore.getCandidateRosterHash()),
                                tssConfig.forceHandoffs());
                    }
                }
            });
        }
    }

    /**
     * Reconciles the state of the TSS system with the active rosters in the given state at the given timestamps.
     * Notice that when TSS is enabled but the signer is not yet ready, <b>only</b> the round timestamp advances,
     * since we don't create block boundaries until we can sign them.
     *
     * @param state the state to use when reconciling the TSS system state with the active rosters
     * @param roundTimestamp the current round timestamp
     */
    private void reconcileTssState(@NonNull final State state, @NonNull final Instant roundTimestamp) {
        final var tssConfig = configProvider.getConfiguration().getConfigData(TssConfig.class);
        if (tssConfig.hintsEnabled() || tssConfig.historyEnabled()) {
            final var rosterStore = new ReadableRosterStoreImpl(state.getReadableStates(RosterService.NAME));
            final var entityCounters = new WritableEntityIdStore(state.getWritableStates(EntityIdService.NAME));
            final var activeRosters = ActiveRosters.from(rosterStore);
            final var isActive = currentPlatformStatus.get() == ACTIVE;
            if (tssConfig.hintsEnabled()) {
                final var crsWritableStates = state.getWritableStates(HintsService.NAME);
                final var workTime =
                        blockHashSigner.isReady() ? blockStreamManager.lastUsedConsensusTime() : roundTimestamp;
                doStreamingKVChanges(
                        crsWritableStates,
                        null,
                        () -> hintsService.executeCrsWork(
                                new WritableHintsStoreImpl(crsWritableStates, entityCounters), workTime, isActive));
                final var hintsWritableStates = state.getWritableStates(HintsService.NAME);
                doStreamingKVChanges(
                        hintsWritableStates,
                        null,
                        () -> hintsService.reconcile(
                                activeRosters,
                                new WritableHintsStoreImpl(hintsWritableStates, entityCounters),
                                roundTimestamp,
                                tssConfig,
                                isActive));
            }
            if (tssConfig.historyEnabled()) {
                final Bytes currentMetadata = tssConfig.hintsEnabled()
                        ? new ReadableHintsStoreImpl(state.getReadableStates(HintsService.NAME), entityCounters)
                                .getActiveVerificationKey()
                        : HintsService.DISABLED_HINTS_METADATA;
                final var historyWritableStates = state.getWritableStates(HistoryService.NAME);
                final var historyStore = new WritableHistoryStoreImpl(historyWritableStates);
                doStreamingKVChanges(
                        historyWritableStates,
                        null,
                        () -> historyService.reconcile(
                                activeRosters,
                                currentMetadata,
                                historyStore,
                                blockStreamManager.lastUsedConsensusTime(),
                                tssConfig,
                                isActive));
            }
        }
    }

    private static void logPreDispatch(@NonNull final ParentTxn parentTxn) {
        if (logger.isDebugEnabled()) {
            logStartUserTransaction(
                    parentTxn.consensusNow(),
                    parentTxn.txnInfo().txBody(),
                    requireNonNull(parentTxn.txnInfo().payerID()));
            logStartUserTransactionPreHandleResultP2(parentTxn.preHandleResult());
            logStartUserTransactionPreHandleResultP3(parentTxn.preHandleResult());
        }
    }

    /**
     * Returns the type of transaction encountering the given state at a block boundary.
     *
     * @param state the boundary state
     * @return the type of the boundary transaction
     */
    private TransactionType typeOfBoundary(@NonNull final State state) {
        final var blockInfo = state.getReadableStates(BlockRecordService.NAME)
                .<BlockInfo>getSingleton(BLOCK_INFO_STATE_KEY)
                .get();
        return !requireNonNull(blockInfo).migrationRecordsStreamed() ? POST_UPGRADE_TRANSACTION : ORDINARY_TRANSACTION;
    }
}
