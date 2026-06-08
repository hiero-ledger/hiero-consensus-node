// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.hapi.utils.blocks.BlockStreamUtils.stateNameOf;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.swirlds.state.StateChangeListener.StateType.SINGLETON;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.congestion.CongestionLevelStarts;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoString;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.hapi.node.state.token.NodePayments;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.spi.metrics.StoreMetricsService;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.node.config.data.SchedulingConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.node.config.data.TopicsConfig;
import com.hedera.pbj.runtime.OneOf;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.StateChangeListener;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A state change listener that accumulates state changes that are only reported at a block boundary; either
 * because all that affects the root hash is the latest value in state, or it is simply more efficient to report
 * them in bulk. In the current system, these are the singleton and queue updates.
 */
public class BoundaryStateChangeListener implements StateChangeListener {
    private static final Logger log = LogManager.getLogger(BoundaryStateChangeListener.class);

    private static final Set<StateType> TARGET_DATA_TYPES = EnumSet.of(SINGLETON);

    /**
     * Maintains insertion order so we externalize changes in the same order they were applied during genesis.
     */
    private final Map<Integer, StateChange> singletonUpdates = new LinkedHashMap<>();

    @NonNull
    private final StoreMetricsService storeMetricsService;

    @NonNull
    private final Supplier<Configuration> configurationSupplier;

    private long nodeFeesCollected;

    /**
     * Constructor for the {@link BoundaryStateChangeListener} class.
     * @param storeMetricsService the store metrics service
     * @param configurationSupplier the configuration
     */
    public BoundaryStateChangeListener(
            @NonNull final StoreMetricsService storeMetricsService,
            @NonNull final Supplier<Configuration> configurationSupplier) {
        this.storeMetricsService = requireNonNull(storeMetricsService);
        this.configurationSupplier = requireNonNull(configurationSupplier);
    }

    /**
     * Resets the node fees collected in this block.
     */
    public void resetCollectedNodeFees() {
        nodeFeesCollected = 0;
    }

    /**
     * Returns the node fees collected in this block.
     */
    public long nodeFeesCollected() {
        return nodeFeesCollected;
    }

    /**
     * Tracks the collected node fees.
     * @param nodeFeesCollected the node fees collected
     */
    public void trackCollectedNodeFees(final long nodeFeesCollected) {
        this.nodeFeesCollected += nodeFeesCollected;
    }

    /**
     * Resets the state of the listener.
     */
    public void reset() {
        if (!singletonUpdates.isEmpty()) {
            log.info(
                    "Forensic boundary singleton reset clearing {} pending updates in order [{}]",
                    singletonUpdates.size(),
                    pendingSingletonUpdateSummary());
        }
        singletonUpdates.clear();
    }

    /**
     * Returns the number of singleton updates waiting to be externalized at a block boundary.
     */
    public int pendingSingletonUpdateCount() {
        return singletonUpdates.size();
    }

    /**
     * Returns a compact summary of pending singleton updates in externalization order.
     */
    public String pendingSingletonUpdateSummary() {
        return singletonUpdates.keySet().stream()
                .map(BoundaryStateChangeListener::stateIdSummary)
                .collect(Collectors.joining(" -> "));
    }

    /**
     * Returns all the state changes that have been accumulated, preserving insertion order.
     * @return the state changes
     */
    public List<StateChange> allStateChanges() {
        final var allStateChanges = new LinkedList<StateChange>();
        for (final var entry : singletonUpdates.entrySet()) {
            allStateChanges.add(entry.getValue());
        }
        return allStateChanges;
    }

    @Override
    public Set<StateType> stateTypes() {
        return TARGET_DATA_TYPES;
    }

    @Override
    public <V> void singletonUpdateChange(final int stateId, @NonNull final V value) {
        requireNonNull(value, "value must not be null");
        final boolean wasAlreadyPending = singletonUpdates.containsKey(stateId);
        final int position = positionOf(stateId);
        final var singletonUpdate = new SingletonUpdateChange(singletonUpdateChangeValueFor(value));
        final var stateChange = StateChange.newBuilder()
                .stateId(stateId)
                .singletonUpdate(singletonUpdate)
                .build();
        singletonUpdates.put(stateId, stateChange);
        log.info(
                "Forensic boundary singleton {} state={} position={} pendingCount={} value={} kind={}",
                wasAlreadyPending ? "overwrite" : "insert",
                stateIdSummary(stateId),
                position,
                singletonUpdates.size(),
                valueSummary(value),
                singletonUpdate.newValue().kind());
        if (stateId == ENTITY_COUNTS_STATE_ID) {
            updateEntityCountsMetrics((EntityCounts) value);
        }
    }

    private int positionOf(final int stateId) {
        int position = 1;
        for (final var pendingStateId : singletonUpdates.keySet()) {
            if (pendingStateId == stateId) {
                return position;
            }
            position++;
        }
        return singletonUpdates.size() + 1;
    }

    private static String stateIdSummary(final int stateId) {
        try {
            return "%s(%d)".formatted(stateNameOf(stateId), stateId);
        } catch (final IllegalArgumentException e) {
            return "UNKNOWN_STATE(%d)".formatted(stateId);
        }
    }

    private static String valueSummary(@NonNull final Object value) {
        return "%s#%08x".formatted(value.getClass().getSimpleName(), value.hashCode());
    }

    private void updateEntityCountsMetrics(final EntityCounts entityCounts) {
        final var configuration = this.configurationSupplier.get();
        final long nodeCapacity = configuration.getConfigData(NodesConfig.class).maxNumber();
        final var nodeMetrics = storeMetricsService.get(StoreMetricsService.StoreType.NODE, nodeCapacity);
        nodeMetrics.updateCount(entityCounts.numNodes());

        final long topicCapacity =
                configuration.getConfigData(TopicsConfig.class).maxNumber();
        final var topicMetrics = storeMetricsService.get(StoreMetricsService.StoreType.TOPIC, topicCapacity);
        topicMetrics.updateCount(entityCounts.numTopics());

        final ContractsConfig contractsConfig = configuration.getConfigData(ContractsConfig.class);

        final long maxSlotStorageCapacity = contractsConfig.maxKvPairsAggregate();
        final var storageSlotsMetrics =
                storeMetricsService.get(StoreMetricsService.StoreType.SLOT_STORAGE, maxSlotStorageCapacity);
        storageSlotsMetrics.updateCount(entityCounts.numContractStorageSlots());

        final long maxContractsCapacity = contractsConfig.maxNumber();
        final var contractStoreMetrics =
                storeMetricsService.get(StoreMetricsService.StoreType.CONTRACT, maxContractsCapacity);
        contractStoreMetrics.updateCount(entityCounts.numContractBytecodes());

        final long fileCapacity = configuration.getConfigData(FilesConfig.class).maxNumber();
        final var fileMetrics = storeMetricsService.get(StoreMetricsService.StoreType.FILE, fileCapacity);
        fileMetrics.updateCount(entityCounts.numFiles());

        final long scheduleCapacity =
                configuration.getConfigData(SchedulingConfig.class).maxNumber();
        final var scheduleMetrics = storeMetricsService.get(StoreMetricsService.StoreType.SCHEDULE, scheduleCapacity);
        scheduleMetrics.updateCount(entityCounts.numSchedules());

        final long accountsCapacity =
                configuration.getConfigData(AccountsConfig.class).maxNumber();
        final var accountMetrics = storeMetricsService.get(StoreMetricsService.StoreType.ACCOUNT, accountsCapacity);
        accountMetrics.updateCount(entityCounts.numAccounts());

        final long airdropCapacity =
                configuration.getConfigData(TokensConfig.class).maxAllowedPendingAirdrops();
        final var airdropMetrics = storeMetricsService.get(StoreMetricsService.StoreType.AIRDROP, airdropCapacity);
        airdropMetrics.updateCount(entityCounts.numAirdrops());

        final long nftsCapacity =
                configuration.getConfigData(TokensConfig.class).nftsMaxAllowedMints();
        final var nftsMetrics = storeMetricsService.get(StoreMetricsService.StoreType.NFT, nftsCapacity);
        nftsMetrics.updateCount(entityCounts.numNfts());

        final long maxRels = configuration.getConfigData(TokensConfig.class).maxAggregateRels();
        final var tokenRelsMetrics = storeMetricsService.get(StoreMetricsService.StoreType.TOKEN_RELATION, maxRels);
        tokenRelsMetrics.updateCount(entityCounts.numTokenRelations());

        final long tokenCapacity =
                configuration.getConfigData(TokensConfig.class).maxNumber();
        final var tokenMetrics = storeMetricsService.get(StoreMetricsService.StoreType.TOKEN, tokenCapacity);
        tokenMetrics.updateCount(entityCounts.numTokens());
    }

    public static <V> @NonNull OneOf<SingletonUpdateChange.NewValueOneOfType> singletonUpdateChangeValueFor(
            @NonNull V value) {
        switch (value) {
            case BlockInfo blockInfo -> {
                return new OneOf<>(SingletonUpdateChange.NewValueOneOfType.BLOCK_INFO_VALUE, blockInfo);
            }
            case RosterState rosterState -> {
                return new OneOf<>(SingletonUpdateChange.NewValueOneOfType.ROSTER_STATE_VALUE, rosterState);
            }
            case CongestionLevelStarts congestionLevelStarts -> {
                return new OneOf<>(
                        SingletonUpdateChange.NewValueOneOfType.CONGESTION_LEVEL_STARTS_VALUE, congestionLevelStarts);
            }
            case EntityNumber entityNumber -> {
                return new OneOf<>(SingletonUpdateChange.NewValueOneOfType.ENTITY_NUMBER_VALUE, entityNumber.number());
            }
            case ExchangeRateSet exchangeRateSet -> {
                return new OneOf<>(SingletonUpdateChange.NewValueOneOfType.EXCHANGE_RATE_SET_VALUE, exchangeRateSet);
            }
            case NetworkStakingRewards networkStakingRewards -> {
                return new OneOf<>(
                        SingletonUpdateChange.NewValueOneOfType.NETWORK_STAKING_REWARDS_VALUE, networkStakingRewards);
            }
            case NodeRewards nodeRewards -> {
                return new OneOf<>(SingletonUpdateChange.NewValueOneOfType.NODE_REWARDS_VALUE, nodeRewards);
            }
            case NodePayments nodePayments -> {
                return new OneOf<>(SingletonUpdateChange.NewValueOneOfType.NODE_PAYMENTS_VALUE, nodePayments);
            }
            case ProtoBytes protoBytes -> {
                return new OneOf<>(SingletonUpdateChange.NewValueOneOfType.BYTES_VALUE, protoBytes.value());
            }
            case ProtoString protoString -> {
                return new OneOf<>(SingletonUpdateChange.NewValueOneOfType.STRING_VALUE, protoString.value());
            }
            case RunningHashes runningHashes -> {
                return new OneOf<>(SingletonUpdateChange.NewValueOneOfType.RUNNING_HASHES_VALUE, runningHashes);
            }
            case ThrottleUsageSnapshots throttleUsageSnapshots -> {
                return new OneOf<>(
                        SingletonUpdateChange.NewValueOneOfType.THROTTLE_USAGE_SNAPSHOTS_VALUE, throttleUsageSnapshots);
            }
            case Timestamp timestamp -> {
                return new OneOf<>(SingletonUpdateChange.NewValueOneOfType.TIMESTAMP_VALUE, timestamp);
            }
            case BlockStreamInfo blockStreamInfo -> {
                return new OneOf<>(SingletonUpdateChange.NewValueOneOfType.BLOCK_STREAM_INFO_VALUE, blockStreamInfo);
            }
            case PlatformState platformState -> {
                return new OneOf<>(SingletonUpdateChange.NewValueOneOfType.PLATFORM_STATE_VALUE, platformState);
            }
            case HintsConstruction hintsConstruction -> {
                return new OneOf<>(SingletonUpdateChange.NewValueOneOfType.HINTS_CONSTRUCTION_VALUE, hintsConstruction);
            }
            case EntityCounts entityCounts -> {
                return new OneOf<>(SingletonUpdateChange.NewValueOneOfType.ENTITY_COUNTS_VALUE, entityCounts);
            }
            case HistoryProofConstruction historyProofConstruction -> {
                return new OneOf<>(
                        SingletonUpdateChange.NewValueOneOfType.HISTORY_PROOF_CONSTRUCTION_VALUE,
                        historyProofConstruction);
            }
            case CRSState crsState -> {
                return new OneOf<>(SingletonUpdateChange.NewValueOneOfType.CRS_STATE_VALUE, crsState);
            }
            case NodeId highestNodeId -> {
                return new OneOf<>(SingletonUpdateChange.NewValueOneOfType.NODE_ID_VALUE, highestNodeId);
            }
            default ->
                throw new IllegalArgumentException(
                        "Unknown value type " + value.getClass().getName());
        }
    }
}
