// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.schemas;

import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_KEY;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFO_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_KEY;
import static com.hedera.node.app.service.token.impl.test.schemas.SyntheticAccountsData.DEFAULT_NUM_SYSTEM_ACCOUNTS;
import static com.hedera.node.app.service.token.impl.test.schemas.SyntheticAccountsData.EVM_ADDRESSES;
import static com.hedera.node.app.service.token.impl.test.schemas.SyntheticAccountsData.NUM_RESERVED_SYSTEM_ENTITIES;
import static com.hedera.node.app.service.token.impl.test.schemas.SyntheticAccountsData.buildConfig;
import static com.swirlds.state.test.fixtures.merkle.TestSchema.CURRENT_VERSION;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.ids.AppEntityIdFactory;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.ids.schemas.V0490EntityIdSchema;
import com.hedera.node.app.service.token.impl.schemas.SyntheticAccountCreator;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.services.MigrationContextImpl;
import com.hedera.node.app.spi.fixtures.info.FakeNetworkInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.StartupNetworks;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.lifecycle.info.NodeInfo;
import com.swirlds.state.spi.EmptyReadableStates;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.HashMap;
import java.util.stream.IntStream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class V0490TokenSchemaTest {
    private static final long NON_GENESIS_ROUND = 123L;
    private static final long BEGINNING_ENTITY_ID = 1000;

    private static final AccountID[] ACCT_IDS = new AccountID[1001];

    static {
        IntStream.rangeClosed(1, 1000).forEach(i -> ACCT_IDS[i] = asAccount(0L, 0L, i));
    }

    private static final long[] NON_CONTRACT_RESERVED_NUMS =
            V0490TokenSchema.nonContractSystemNums(NUM_RESERVED_SYSTEM_ENTITIES);

    static {
        // Precondition check
        Assertions.assertThat(NON_CONTRACT_RESERVED_NUMS).hasSize(501);
    }

    private MapWritableKVState<AccountID, Account> accounts;
    private WritableStates newStates;
    private Configuration config;
    private NetworkInfo networkInfo;
    private WritableEntityIdStore entityIdStore;

    @Mock
    private StartupNetworks startupNetworks;

    @BeforeEach
    void setUp() {
        accounts = MapWritableKVState.<AccountID, Account>builder(V0490TokenSchema.ACCOUNTS_KEY)
                .build();

        newStates = newStatesInstance(
                accounts,
                MapWritableKVState.<Bytes, AccountID>builder(ALIASES_KEY).build(),
                newWritableEntityIdState(),
                new WritableSingletonStateBase<>(
                        ENTITY_COUNTS_KEY, () -> EntityCounts.newBuilder().build(), c -> {}));

        entityIdStore = new WritableEntityIdStore(newStates);

        networkInfo = new FakeNetworkInfo();

        config = buildConfig(DEFAULT_NUM_SYSTEM_ACCOUNTS, true);
    }

    @Test
    void nonGenesisDoesntCreate() {
        // To simulate a non-genesis case, we'll add a single account object to the `previousStates` param
        accounts = MapWritableKVState.<AccountID, Account>builder(V0490TokenSchema.ACCOUNTS_KEY)
                .value(ACCT_IDS[1], Account.DEFAULT)
                .build();
        final var nonEmptyPrevStates = newStatesInstance(
                accounts,
                MapWritableKVState.<Bytes, AccountID>builder(ALIASES_KEY).build(),
                newWritableEntityIdState(),
                new WritableSingletonStateBase<>(
                        ENTITY_COUNTS_KEY, () -> EntityCounts.newBuilder().build(), c -> {}));
        final var schema = newSubjectWithAllExpected();
        final var migrationContext = new MigrationContextImpl(
                nonEmptyPrevStates,
                newStates,
                config,
                config,
                networkInfo,
                entityIdStore,
                CURRENT_VERSION,
                NON_GENESIS_ROUND,
                new HashMap<>(),
                startupNetworks,
                new AppEntityIdFactory(config));

        schema.migrate(migrationContext);

        final var acctsStateResult = newStates.<AccountID, Account>get(ACCOUNTS_KEY);
        assertThat(acctsStateResult.isModified()).isFalse();
        final var nodeRewardsStateResult = newStates.<NodeInfo>getSingleton(STAKING_NETWORK_REWARDS_KEY);
        assertThat(nodeRewardsStateResult.isModified()).isFalse();
        final var nodeInfoStateResult = newStates.<EntityNumber, StakingNodeInfo>get(STAKING_INFO_KEY);
        assertThat(nodeInfoStateResult.isModified()).isFalse();
    }

    @Test
    void initializesStakingDataOnGenesisStart() {
        final var schema = newSubjectWithAllExpected();
        final var migrationContext = new MigrationContextImpl(
                EmptyReadableStates.INSTANCE,
                newStates,
                config,
                config,
                networkInfo,
                entityIdStore,
                null,
                0L,
                new HashMap<>(),
                startupNetworks,
                new AppEntityIdFactory(config));

        schema.migrate(migrationContext);

        final var nodeRewardsStateResult = newStates.<NodeInfo>getSingleton(STAKING_NETWORK_REWARDS_KEY);
        assertThat(nodeRewardsStateResult.isModified()).isTrue();
        final var nodeInfoStateResult = newStates.<EntityNumber, StakingNodeInfo>get(STAKING_INFO_KEY);
        assertThat(nodeInfoStateResult.isModified()).isTrue();
    }

    @Test
    void createsAllAccountsOnGenesisStart() {
        final var schema = newSubjectWithAllExpected();
        final var migrationContext = new MigrationContextImpl(
                EmptyReadableStates.INSTANCE,
                newStates,
                config,
                config,
                networkInfo,
                entityIdStore,
                null,
                0L,
                new HashMap<>(),
                startupNetworks,
                new AppEntityIdFactory(config));

        schema.migrate(migrationContext);

        final var acctsStateResult = newStates.<AccountID, Account>get(ACCOUNTS_KEY);
        Assertions.assertThat(acctsStateResult).isNotNull();

        // Verify created system accounts
        for (int i = 1; i < DEFAULT_NUM_SYSTEM_ACCOUNTS; i++) {
            assertThat(acctsStateResult.get(ACCT_IDS[i])).isNotNull();
        }

        // Verify created staking accounts
        assertThat(acctsStateResult.get(ACCT_IDS[800])).isNotNull();
        assertThat(acctsStateResult.get(ACCT_IDS[801])).isNotNull();

        // Verify created multipurpose accounts
        for (int i = 900; i < 1001; i++) {
            assertThat(acctsStateResult.get(ACCT_IDS[i])).isNotNull();
        }

        // Verify created treasury clones
        for (final long nonContractSysNum : NON_CONTRACT_RESERVED_NUMS) {
            if (nonContractSysNum < DEFAULT_NUM_SYSTEM_ACCOUNTS) {
                // we've already checked that this account is not null (there's some overlap with system accounts), so
                // skip checking this account again
                continue;
            }
            assertThat(acctsStateResult.get(ACCT_IDS[(int) nonContractSysNum])).isNotNull();
        }

        // Verify created blocklist account OBJECTS
        final long expectedBlocklistIndex = BEGINNING_ENTITY_ID + EVM_ADDRESSES.length;
        for (int i = 3001; i <= expectedBlocklistIndex; i++) {
            final var acct =
                    acctsStateResult.get(AccountID.newBuilder().accountNum(i).build());
            assertThat(acct).isNotNull();
            assertThat(acct.alias()).isEqualTo(Bytes.fromHex(EVM_ADDRESSES[i - (int) BEGINNING_ENTITY_ID - 1]));
        }

        // Finally, verify that the size is exactly as expected
        assertThat(acctsStateResult.size())
                .isEqualTo(
                        // All the system accounts
                        DEFAULT_NUM_SYSTEM_ACCOUNTS
                                +
                                // Both of the two staking accounts
                                2
                                +
                                // All the misc accounts
                                101
                                +
                                // All treasury clones (which is 501 - <overlap between treasury clones and sys
                                // accounts)
                                388
                                +
                                // All the blocklist accounts
                                6);
    }

    @Test
    void blocklistNotEnabled() {
        final var schema = newSubjectWithAllExpected();

        // None of the blocklist accounts will exist, but they shouldn't be created since blocklists aren't enabled
        config = buildConfig(DEFAULT_NUM_SYSTEM_ACCOUNTS, false);
        final var migrationContext = new MigrationContextImpl(
                EmptyReadableStates.INSTANCE,
                newStates,
                config,
                config,
                networkInfo,
                entityIdStore,
                CURRENT_VERSION,
                NON_GENESIS_ROUND,
                new HashMap<>(),
                startupNetworks,
                new AppEntityIdFactory(config));

        schema.migrate(migrationContext);

        final var acctsStateResult = newStates.<AccountID, Account>get(ACCOUNTS_KEY);
        final var aliasStateResult = newStates.<Bytes, AccountID>get(ALIASES_KEY);
        for (int i = 0; i < EVM_ADDRESSES.length; i++) {
            assertThat(acctsStateResult.get(AccountID.newBuilder()
                            .accountNum(BEGINNING_ENTITY_ID + 1 + i)
                            .build()))
                    .isNull();
            assertThat(aliasStateResult.get(Bytes.fromHex(EVM_ADDRESSES[i]))).isNull();
        }
    }

    @Test
    void onlyExpectedIdsUsed() {
        final var schema = newSubjectWithAllExpected();
        schema.migrate(new MigrationContextImpl(
                EmptyReadableStates.INSTANCE,
                newStates,
                config,
                config,
                networkInfo,
                entityIdStore,
                null,
                0L,
                new HashMap<>(),
                startupNetworks,
                new AppEntityIdFactory(config)));

        // Verify contract entity IDs aren't used
        for (int i = 350; i < 400; i++) {
            assertThat(accounts.contains(ACCT_IDS[i])).isFalse();
        }

        // Verify entity IDs between system and staking reward accounts aren't used
        for (int i = 751; i < 800; i++) {
            assertThat(accounts.contains(asAccount(0L, 0L, i))).isFalse();
        }

        // Verify entity IDs between staking rewards and misc accounts are empty
        for (int i = 802; i < 900; i++) {
            assertThat(accounts.contains(asAccount(0L, 0L, i))).isFalse();
        }
    }

    private WritableSingletonState<EntityNumber> newWritableEntityIdState() {
        return new WritableSingletonStateBase<>(
                V0490EntityIdSchema.ENTITY_ID_STATE_KEY, () -> new EntityNumber(BEGINNING_ENTITY_ID), c -> {});
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
                .state(MapWritableKVState.builder(V0490TokenSchema.STAKING_INFO_KEY)
                        .build())
                .state(new WritableSingletonStateBase<>(STAKING_NETWORK_REWARDS_KEY, () -> null, c -> {}))
                .state(entityIdState)
                .state(entityCountsState)
                .build();
    }

    private V0490TokenSchema newSubjectWithAllExpected() {
        return new V0490TokenSchema(new SyntheticAccountCreator());
    }
}
