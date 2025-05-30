// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.schemas;

import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFO_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_KEY;
import static com.hedera.node.app.service.token.impl.test.handlers.staking.EndOfStakingPeriodUpdaterTest.NODE_NUM_1;
import static com.hedera.node.app.service.token.impl.test.handlers.staking.EndOfStakingPeriodUpdaterTest.NODE_NUM_2;
import static com.hedera.node.app.service.token.impl.test.handlers.staking.EndOfStakingPeriodUpdaterTest.NODE_NUM_3;
import static com.hedera.node.app.service.token.impl.test.handlers.staking.EndOfStakingPeriodUpdaterTest.STAKING_INFO_1;
import static com.hedera.node.app.service.token.impl.test.handlers.staking.EndOfStakingPeriodUpdaterTest.STAKING_INFO_2;
import static com.hedera.node.app.service.token.impl.test.handlers.staking.EndOfStakingPeriodUpdaterTest.STAKING_INFO_3;
import static com.hedera.node.app.service.token.impl.test.schemas.SyntheticAccountsData.DEFAULT_NUM_SYSTEM_ACCOUNTS;
import static com.hedera.node.app.service.token.impl.test.schemas.SyntheticAccountsData.buildConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.schemas.V0490EntityIdSchema;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema;
import com.hedera.node.app.services.MigrationContextImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.StartupNetworks;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.Comparator;
import java.util.HashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V0530TokenSchemaTest {

    private final V0530TokenSchema subject = new V0530TokenSchema();

    @Mock
    private StartupNetworks startupNetworks;

    @Test
    @DisplayName("verify states to create")
    void verifyStatesToCreate() {
        var sortedResult = subject.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateKey))
                .toList();

        final var firstStateDef = sortedResult.getFirst();
        assertThat(firstStateDef.stateKey()).isEqualTo("PENDING_AIRDROPS");
        assertThat(firstStateDef.keyCodec()).isEqualTo(PendingAirdropId.PROTOBUF);
        assertThat(firstStateDef.valueCodec()).isEqualTo(AccountPendingAirdrop.PROTOBUF);
    }

    @Test
    void setsStakingInfoMinStakeToZero() {
        final var accounts = MapWritableKVState.<AccountID, Account>builder(
                        TokenService.NAME, V0490TokenSchema.ACCOUNTS_KEY)
                .build();
        final var entityIdState = new FunctionWritableSingletonState<>(
                TokenService.NAME, V0490EntityIdSchema.ENTITY_ID_STATE_KEY, () -> new EntityNumber(1000), c -> {});

        final var stakingInfosState = new MapWritableKVState.Builder<EntityNumber, StakingNodeInfo>(
                        TokenService.NAME, STAKING_INFO_KEY)
                .value(NODE_NUM_1, STAKING_INFO_1)
                .value(NODE_NUM_2, STAKING_INFO_2)
                .value(NODE_NUM_3, STAKING_INFO_3)
                .build();
        final var previousStates = newStatesInstance(
                accounts,
                MapWritableKVState.<Bytes, AccountID>builder(TokenService.NAME, ALIASES_KEY)
                        .build(),
                entityIdState,
                stakingInfosState,
                new FunctionWritableSingletonState<>(
                        EntityIdService.NAME,
                        ENTITY_COUNTS_KEY,
                        () -> EntityCounts.newBuilder().build(),
                        c -> {}));
        final var newStates = newStatesInstance(
                accounts,
                MapWritableKVState.<Bytes, AccountID>builder(TokenService.NAME, ALIASES_KEY)
                        .build(),
                entityIdState,
                stakingInfosState,
                new FunctionWritableSingletonState<>(
                        EntityIdService.NAME,
                        ENTITY_COUNTS_KEY,
                        () -> EntityCounts.newBuilder().build(),
                        c -> {}));
        final var config = buildConfig(DEFAULT_NUM_SYSTEM_ACCOUNTS, true);

        final var schema = new V0530TokenSchema();
        schema.migrate(new MigrationContextImpl(
                previousStates, newStates, config, config, null, 0L, new HashMap<>(), startupNetworks));

        final var updatedStates = newStates.get(STAKING_INFO_KEY);
        // sets minStake on all nodes to 0
        assertThat(updatedStates
                .get(NODE_NUM_1)
                .equals(STAKING_INFO_1.copyBuilder().minStake(0).build()));
        assertThat(updatedStates
                .get(NODE_NUM_2)
                .equals(STAKING_INFO_2.copyBuilder().minStake(0).build()));
        assertThat(updatedStates
                .get(NODE_NUM_3)
                .equals(STAKING_INFO_3.copyBuilder().minStake(0).build()));
    }

    private MapWritableStates newStatesInstance(
            final MapWritableKVState<AccountID, Account> accts,
            final MapWritableKVState<Bytes, AccountID> aliases,
            final WritableSingletonState<EntityNumber> entityIdState,
            final MapWritableKVState<EntityNumber, StakingNodeInfo> stakingInfo,
            final WritableSingletonState<EntityCounts> entityCounts) {
        //noinspection ReturnOfNull
        return MapWritableStates.builder()
                .state(accts)
                .state(aliases)
                .state(stakingInfo)
                .state(new FunctionWritableSingletonState<>(
                        TokenService.NAME, STAKING_NETWORK_REWARDS_KEY, () -> null, c -> {}))
                .state(entityIdState)
                .state(entityCounts)
                .build();
    }
}
