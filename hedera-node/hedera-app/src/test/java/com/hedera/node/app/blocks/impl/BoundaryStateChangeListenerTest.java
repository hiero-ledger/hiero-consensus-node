// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.hapi.block.stream.output.StateChange.ChangeOperationOneOfType.SINGLETON_UPDATE;
import static com.swirlds.state.StateChangeListener.StateType.SINGLETON;
import static com.swirlds.state.merkle.StateUtils.stateIdFor;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.primitives.ProtoString;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema;
import com.hedera.node.app.metrics.StoreMetricsImpl;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.spi.metrics.StoreMetricsService;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.node.config.data.SchedulingConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.node.config.data.TopicsConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BoundaryStateChangeListenerTest {
    private static final int STATE_ID = 1;
    private static final int STATE_ID_ENTITY_COUNTS = 41;
    public static final ProtoString PROTO_STRING = new ProtoString("test");
    public static final EntityCounts ENTITY_COUNTS = EntityCounts.newBuilder()
            .numNfts(1)
            .numAccounts(2)
            .numAirdrops(3)
            .numAliases(4)
            .numTokens(5)
            .numTokenRelations(6)
            .numContractBytecodes(7)
            .numNodes(8)
            .numFiles(9)
            .numSchedules(10)
            .numContractStorageSlots(11)
            .numTopics(12)
            .numStakingInfos(13)
            .build();

    private BoundaryStateChangeListener listener;

    private Configuration configuration = HederaTestConfigBuilder.createConfig();
    private StoreMetricsServiceImpl storeMetricsService = new StoreMetricsServiceImpl(new NoOpMetrics());

    @BeforeEach
    void setUp() {
        listener = new BoundaryStateChangeListener(storeMetricsService, () -> configuration);
    }

    @Test
    void targetTypeIsSingleton() {
        assertEquals(Set.of(SINGLETON), listener.stateTypes());
    }

    @Test
    void understandsStateIds() {
        final var service = BlockStreamService.NAME;
        final var stateKey = V0560BlockStreamSchema.BLOCK_STREAM_INFO_KEY;
        assertEquals(stateIdFor(service, stateKey), listener.stateIdFor(service, stateKey));
    }

    @Test
    void testAllStateChanges() {
        listener.singletonUpdateChange(STATE_ID, PROTO_STRING);

        List<StateChange> stateChanges = listener.allStateChanges();
        assertEquals(1, stateChanges.size());
    }

    @Test
    void testSingletonUpdateChange() {
        listener.singletonUpdateChange(STATE_ID, PROTO_STRING);

        StateChange stateChange = listener.allStateChanges().getFirst();
        assertEquals(SINGLETON_UPDATE, stateChange.changeOperation().kind());
        assertEquals(STATE_ID, stateChange.stateId());
        assertEquals(PROTO_STRING.value(), stateChange.singletonUpdate().stringValue());
    }

    @Test
    void getAndResetNodeFees() {
        listener.trackCollectedNodeFees(100);
        assertEquals(100, listener.nodeFeesCollected());
    }

    @Test
    void testSingletonUpdateChangeForEntityCounts() {
        listener.singletonUpdateChange(STATE_ID_ENTITY_COUNTS, ENTITY_COUNTS);

        StateChange stateChange = listener.allStateChanges().getFirst();
        assertEquals(SINGLETON_UPDATE, stateChange.changeOperation().kind());
        assertEquals(STATE_ID_ENTITY_COUNTS, stateChange.stateId());
        assertEquals(ENTITY_COUNTS, stateChange.singletonUpdate().entityCountsValue());

        final long nodeCapacity = configuration.getConfigData(NodesConfig.class).maxNumber();
        final long topicCapacity =
                configuration.getConfigData(TopicsConfig.class).maxNumber();
        final ContractsConfig contractsConfig = configuration.getConfigData(ContractsConfig.class);
        final long maxSlotStorageCapacity = contractsConfig.maxKvPairsAggregate();
        final long maxContractsCapacity = contractsConfig.maxNumber();
        final long fileCapacity = configuration.getConfigData(FilesConfig.class).maxNumber();
        final long scheduleCapacity =
                configuration.getConfigData(SchedulingConfig.class).maxNumber();
        final long accountsCapacity =
                configuration.getConfigData(AccountsConfig.class).maxNumber();
        final long airdropCapacity =
                configuration.getConfigData(TokensConfig.class).maxAllowedPendingAirdrops();
        final long nftsCapacity =
                configuration.getConfigData(TokensConfig.class).nftsMaxAllowedMints();
        final long maxRels = configuration.getConfigData(TokensConfig.class).maxAggregateRels();
        final long tokenCapacity =
                configuration.getConfigData(TokensConfig.class).maxNumber();

        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(StoreMetricsService.StoreType.ACCOUNT, accountsCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numAccounts());
        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(StoreMetricsService.StoreType.AIRDROP, airdropCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numAirdrops());
        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(StoreMetricsService.StoreType.NFT, nftsCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numNfts());
        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(StoreMetricsService.StoreType.TOKEN, tokenCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numTokens());
        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(StoreMetricsService.StoreType.TOKEN_RELATION, maxRels))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numTokenRelations());
        assertEquals(
                ((StoreMetricsImpl)
                                storeMetricsService.get(StoreMetricsService.StoreType.CONTRACT, maxContractsCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numContractBytecodes());
        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(StoreMetricsService.StoreType.NODE, nodeCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numNodes());
        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(StoreMetricsService.StoreType.FILE, fileCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numFiles());
        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(StoreMetricsService.StoreType.SCHEDULE, scheduleCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numSchedules());
        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(
                                StoreMetricsService.StoreType.SLOT_STORAGE, maxSlotStorageCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numContractStorageSlots());
        assertEquals(
                ((StoreMetricsImpl) storeMetricsService.get(StoreMetricsService.StoreType.TOPIC, topicCapacity))
                        .getCount()
                        .get(),
                ENTITY_COUNTS.numTopics());
    }
}
