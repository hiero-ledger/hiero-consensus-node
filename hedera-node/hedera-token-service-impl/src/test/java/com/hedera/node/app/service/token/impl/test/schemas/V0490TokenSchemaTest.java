// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.schemas;

import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFOS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFOS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.test.schemas.SyntheticAccountsData.DEFAULT_NUM_SYSTEM_ACCOUNTS;
import static com.hedera.node.app.service.token.impl.test.schemas.SyntheticAccountsData.buildConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.ids.schemas.V0490EntityIdSchema;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.services.MigrationContextImpl;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.EmptyReadableStates;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.HashMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class V0490TokenSchemaTest {

    private static final long BEGINNING_ENTITY_ID = 1000;

    private static final AccountID[] ACCT_IDS = new AccountID[1001];

    static {
        IntStream.rangeClosed(1, 1000).forEach(i -> ACCT_IDS[i] = asAccount(0L, 0L, i));
    }

    private MapWritableKVState<AccountID, Account> accounts;
    private WritableStates newStates;
    private Configuration config;

    @Mock
    private StartupNetworks startupNetworks;

    @BeforeEach
    void setUp() {
        accounts = MapWritableKVState.<AccountID, Account>builder(ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL)
                .build();

        newStates = newStatesInstance(
                accounts,
                MapWritableKVState.<Bytes, AccountID>builder(ALIASES_STATE_ID, ALIASES_STATE_LABEL)
                        .build(),
                newWritableEntityIdState(),
                new FunctionWritableSingletonState<>(
                        ENTITY_COUNTS_STATE_ID,
                        ENTITY_COUNTS_STATE_LABEL,
                        () -> EntityCounts.newBuilder().build(),
                        c -> {}));

        config = buildConfig(DEFAULT_NUM_SYSTEM_ACCOUNTS, true);
    }

    @Test
    void initializesStakingDataOnGenesisStart() {
        final var schema = newSubjectWithAllExpected();
        final var migrationContext = new MigrationContextImpl(
                EmptyReadableStates.INSTANCE, newStates, config, config, null, 0L, new HashMap<>(), startupNetworks);

        schema.migrate(migrationContext);

        final var nodeRewardsStateResult = newStates.<NodeInfo>getSingleton(STAKING_NETWORK_REWARDS_STATE_ID);
        assertThat(nodeRewardsStateResult.isModified()).isTrue();
        final var nodeInfoStateResult = newStates.<EntityNumber, StakingNodeInfo>get(STAKING_INFOS_STATE_ID);
        assertThat(nodeInfoStateResult.isModified()).isFalse();
    }

    private WritableSingletonState<EntityNumber> newWritableEntityIdState() {
        return new FunctionWritableSingletonState<>(
                V0490EntityIdSchema.ENTITY_ID_STATE_ID,
                V0490EntityIdSchema.ENTITY_ID_STATE_LABEL,
                () -> new EntityNumber(BEGINNING_ENTITY_ID),
                c -> {});
    }

    private MapWritableStates newStatesInstance(
            final MapWritableKVState<AccountID, Account> accts,
            final MapWritableKVState<Bytes, AccountID> aliases,
            final WritableSingletonState<EntityNumber> entityIdState,
            final WritableSingletonState<EntityCounts> entityCountsState) {
        //noinspection ReturnOfNull
        return MapWritableStates.builder()
                .state(accts)
                .state(aliases)
                .state(MapWritableKVState.builder(STAKING_INFOS_STATE_ID, STAKING_INFOS_STATE_LABEL)
                        .build())
                .state(new FunctionWritableSingletonState<>(
                        STAKING_NETWORK_REWARDS_STATE_ID, STAKING_NETWORK_REWARDS_STATE_LABEL, () -> null, c -> {}))
                .state(entityIdState)
                .state(entityCountsState)
                .build();
    }

    private V0490TokenSchema newSubjectWithAllExpected() {
        return new V0490TokenSchema();
    }
}
