// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.util;

import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_KEY;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_KEY;
import static com.hedera.node.app.service.token.AliasUtils.asKeyFromAlias;
import static com.hedera.node.app.service.token.AliasUtils.extractEvmAddress;
import static com.hedera.node.app.service.token.impl.test.handlers.util.StateBuilderUtil.ACCOUNTS;
import static com.hedera.node.app.service.token.impl.test.handlers.util.StateBuilderUtil.ALIASES;
import static com.hedera.node.app.service.token.impl.test.util.SigReqAdapterUtils.UNSET_STAKED_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoAllowance;
import com.hedera.hapi.node.token.TokenAllowance;
import com.hedera.node.app.ids.ReadableEntityIdStoreImpl;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.EntityIdFactory;
import com.swirlds.state.spi.ReadableSingletonStateBase;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Collections;
import java.util.function.Function;
import org.hiero.consensus.model.utility.CommonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
// FUTURE : Remove this and use CryptoTokenHandlerTestBase instead for all classes extending this class

/**
 * Base class for testing Crypto handlers implementations.
 */
@ExtendWith(MockitoExtension.class)
public class CryptoHandlerTestBase {
    protected static final Configuration configuration = HederaTestConfigBuilder.createConfig();
    protected static final long SHARD =
            configuration.getConfigData(HederaConfig.class).shard();
    protected static final long REALM =
            configuration.getConfigData(HederaConfig.class).realm();
    protected static final EntityIdFactory idFactory = new FakeEntityIdFactoryImpl(SHARD, REALM);
    private static final Function<String, Key.Builder> KEY_BUILDER =
            value -> Key.newBuilder().ed25519(Bytes.wrap(value.getBytes()));
    private static final String A_NAME = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static final String B_NAME = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private static final String C_NAME = "cccccccccccccccccccccccccccccccc";

    public static final Key A_THRESHOLD_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(A_NAME).build(),
                                    KEY_BUILDER.apply(B_NAME).build(),
                                    KEY_BUILDER.apply(C_NAME).build())
                            .build()))
            .build();
    public static final Key A_KEY_LIST = Key.newBuilder()
            .keyList(KeyList.newBuilder()
                    .keys(
                            KEY_BUILDER.apply(A_NAME).build(),
                            KEY_BUILDER.apply(B_NAME).build(),
                            KEY_BUILDER.apply(C_NAME).build()))
            .build();
    public static final Key A_COMPLEX_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(A_NAME).build(),
                                    KEY_BUILDER.apply(B_NAME).build(),
                                    A_THRESHOLD_KEY)))
            .build();
    public static final Key B_COMPLEX_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(A_NAME).build(),
                                    KEY_BUILDER.apply(B_NAME).build(),
                                    A_COMPLEX_KEY)))
            .build();
    public static final Key C_COMPLEX_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(A_NAME).build(),
                                    KEY_BUILDER.apply(B_NAME).build(),
                                    B_COMPLEX_KEY)))
            .build();
    protected final Key key = A_COMPLEX_KEY;
    protected final Key otherKey = C_COMPLEX_KEY;
    protected final AccountID id = idFactory.newAccountId(3);
    protected final AccountID invalidId = idFactory.newAccountId(Long.MAX_VALUE);
    protected final Timestamp consensusTimestamp =
            Timestamp.newBuilder().seconds(1_234_567L).build();
    protected final Instant consensusInstant = Instant.ofEpochSecond(consensusTimestamp.seconds());
    protected final Key accountKey = A_COMPLEX_KEY;
    protected final Long accountNum = id.accountNumOrThrow();

    protected static final Key aPrimitiveKey = Key.newBuilder()
            .ed25519(Bytes.fromHex("3a21030edcc130e13fb5102e7c883535af8c2b0a5a617231f77fd127ce5f3b9a620591"))
            .build();
    protected static final Key aEcdsaKey = Key.newBuilder()
            .ecdsaSecp256k1(Bytes.fromHex("3a210358d7847a8d9a1beb784e367318bad30e89b5d3f3fa1a67f259e40a63e45ad8e5"))
            .build();

    protected static final ProtoBytes edKeyAlias = new ProtoBytes(aPrimitiveKey.ed25519());
    protected static final ProtoBytes ecdsaKeyAlias = new ProtoBytes(aEcdsaKey.ecdsaSecp256k1());
    protected final AccountID alias = idFactory.newAccountIdWithAlias(edKeyAlias.value());
    protected final AccountID ecdsaAlias = idFactory.newAccountIdWithAlias(ecdsaKeyAlias.value());
    protected Bytes aliasEvmAddress = null;
    protected final byte[] evmAddress = CommonUtils.unhex("6aea3773ea468a814d954e6dec795bfee7d76e26");
    protected final ContractID contractAlias =
            ContractID.newBuilder().evmAddress(Bytes.wrap(evmAddress)).build();
    /*Contracts */
    protected final ContractID contract =
            ContractID.newBuilder().contractNum(1234).build();
    protected final AccountID deleteAccountId = idFactory.newAccountId(3213);
    protected final AccountID transferAccountId = idFactory.newAccountId(32134);
    protected final Long deleteAccountNum = deleteAccountId.accountNumOrThrow();
    protected final Long transferAccountNum = transferAccountId.accountNum();

    protected final TokenID nft = TokenID.newBuilder().tokenNum(56789).build();
    protected final TokenID token = TokenID.newBuilder().tokenNum(6789).build();
    protected final AccountID spender = idFactory.newAccountId(12345);
    protected final AccountID delegatingSpender = idFactory.newAccountId(1234567);
    protected final AccountID owner = idFactory.newAccountId(123456);
    protected final Key ownerKey = B_COMPLEX_KEY;
    protected final CryptoAllowance cryptoAllowance = CryptoAllowance.newBuilder()
            .spender(spender)
            .owner(owner)
            .amount(10L)
            .build();
    protected final TokenAllowance tokenAllowance = TokenAllowance.newBuilder()
            .spender(spender)
            .amount(10L)
            .tokenId(token)
            .owner(owner)
            .build();
    protected static final long defaultAutoRenewPeriod = 7200000L;
    protected static final long payerBalance = 10_000L;
    protected MapReadableKVState<ProtoBytes, AccountID> readableAliases;

    protected MapReadableKVState<AccountID, Account> readableAccounts;
    protected MapWritableKVState<ProtoBytes, AccountID> writableAliases;
    protected MapWritableKVState<AccountID, Account> writableAccounts;
    protected Account account;
    protected ReadableAccountStore readableStore;
    protected WritableAccountStore writableStore;

    protected Account deleteAccount;

    protected Account transferAccount;

    @Mock
    protected ReadableStates readableStates;

    @Mock(strictness = LENIENT)
    protected WritableStates writableStates;

    protected ReadableEntityCounters readableEntityCounters;
    protected WritableEntityCounters writableEntityCounters;

    /**
     * Set up the test environment.
     */
    @BeforeEach
    public void setUp() {
        account = givenValidAccount(accountNum);
        deleteAccount = givenValidAccount(deleteAccountNum)
                .copyBuilder()
                .accountId(deleteAccountId)
                .key(accountKey)
                .numberPositiveBalances(0)
                .numberTreasuryTitles(0)
                .build();
        transferAccount = givenValidAccount(transferAccountNum)
                .copyBuilder()
                .accountId(transferAccountId)
                .key(key)
                .build();
        refreshStoresWithCurrentTokenOnlyInReadable();
    }

    protected void basicMetaAssertions(final PreHandleContext context, final int keysSize) {
        assertThat(context.requiredNonPayerKeys()).hasSize(keysSize);
    }

    protected void resetStores() {
        givenEntityCounters();
        readableAccounts = emptyReadableAccountStateBuilder().build();
        writableAccounts = emptyWritableAccountStateBuilder().build();
        readableAliases = emptyReadableAliasStateBuilder().build();
        writableAliases = emptyWritableAliasStateBuilder().build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(readableAccounts);
        given(readableStates.<ProtoBytes, AccountID>get(ALIASES)).willReturn(readableAliases);
        given(writableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(writableAccounts);
        given(writableStates.<ProtoBytes, AccountID>get(ALIASES)).willReturn(writableAliases);
        readableStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
        final var configuration = HederaTestConfigBuilder.createConfig();
        writableStore = new WritableAccountStore(writableStates, writableEntityCounters);
    }

    protected void refreshStoresWithCurrentTokenOnlyInReadable() {
        givenEntityCounters();
        readableAccounts = readableAccountState();
        writableAccounts = emptyWritableAccountStateBuilder().build();
        readableAliases = readableAliasState();
        writableAliases = emptyWritableAliasStateBuilder().build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(readableAccounts);
        given(readableStates.<ProtoBytes, AccountID>get(ALIASES)).willReturn(readableAliases);
        given(writableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(writableAccounts);
        given(writableStates.<ProtoBytes, AccountID>get(ALIASES)).willReturn(writableAliases);

        readableStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
        final var configuration = HederaTestConfigBuilder.createConfig();
        writableStore = new WritableAccountStore(writableStates, writableEntityCounters);
    }

    private void givenEntityCounters() {
        given(writableStates.getSingleton(ENTITY_ID_STATE_KEY))
                .willReturn(new WritableSingletonStateBase<>(
                        ENTITY_ID_STATE_KEY, () -> EntityNumber.newBuilder().build(), c -> {}));
        given(writableStates.getSingleton(ENTITY_COUNTS_KEY))
                .willReturn(new WritableSingletonStateBase<>(ENTITY_COUNTS_KEY, () -> EntityCounts.DEFAULT, c -> {}));
        given(readableStates.getSingleton(ENTITY_ID_STATE_KEY))
                .willReturn(new ReadableSingletonStateBase<>(
                        ENTITY_ID_STATE_KEY, () -> EntityNumber.newBuilder().build()));
        given(readableStates.getSingleton(ENTITY_COUNTS_KEY))
                .willReturn(new ReadableSingletonStateBase<>(ENTITY_COUNTS_KEY, () -> EntityCounts.DEFAULT));
        readableEntityCounters = new ReadableEntityIdStoreImpl(readableStates);
        writableEntityCounters = new WritableEntityIdStore(writableStates);
    }

    protected void refreshStoresWithCurrentTokenInWritable() {
        readableAccounts = readableAccountState();
        writableAccounts = writableAccountStateWithOneKey();
        readableAliases = readableAliasState();
        writableAliases = writableAliasesStateWithOneKey();
        given(readableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(readableAccounts);
        given(readableStates.<ProtoBytes, AccountID>get(ALIASES)).willReturn(readableAliases);
        given(writableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(writableAccounts);
        given(writableStates.<ProtoBytes, AccountID>get(ALIASES)).willReturn(writableAliases);
        readableStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
        final var configuration = HederaTestConfigBuilder.createConfig();
        writableStore = new WritableAccountStore(writableStates, writableEntityCounters);
    }

    @NonNull
    protected MapWritableKVState<AccountID, Account> writableAccountStateWithOneKey() {
        return emptyWritableAccountStateBuilder()
                .value(id, account)
                .value(deleteAccountId, deleteAccount)
                .value(transferAccountId, transferAccount)
                .build();
    }

    @NonNull
    protected MapReadableKVState<AccountID, Account> readableAccountState() {
        return emptyReadableAccountStateBuilder()
                .value(id, account)
                .value(deleteAccountId, deleteAccount)
                .value(transferAccountId, transferAccount)
                .build();
    }

    @NonNull
    protected MapWritableKVState<ProtoBytes, AccountID> writableAliasesStateWithOneKey() {
        return emptyWritableAliasStateBuilder()
                .value(new ProtoBytes(alias.alias()), idFactory.newAccountId(accountNum))
                .value(new ProtoBytes(contractAlias.evmAddress()), idFactory.newAccountId(contract.contractNum()))
                .build();
    }

    @NonNull
    protected MapReadableKVState<ProtoBytes, AccountID> readableAliasState() {
        return emptyReadableAliasStateBuilder()
                .value(new ProtoBytes(alias.alias()), idFactory.newAccountId(accountNum))
                .value(new ProtoBytes(contractAlias.evmAddress()), idFactory.newAccountId(contract.contractNum()))
                .build();
    }

    @NonNull
    protected MapReadableKVState.Builder<AccountID, Account> emptyReadableAccountStateBuilder() {
        return MapReadableKVState.builder(ACCOUNTS);
    }

    @NonNull
    protected MapWritableKVState.Builder<AccountID, Account> emptyWritableAccountStateBuilder() {
        return MapWritableKVState.builder(ACCOUNTS);
    }

    @NonNull
    protected MapWritableKVState.Builder<ProtoBytes, AccountID> emptyWritableAliasStateBuilder() {
        return MapWritableKVState.builder(ALIASES);
    }

    @NonNull
    protected MapReadableKVState.Builder<ProtoBytes, AccountID> emptyReadableAliasStateBuilder() {
        return MapReadableKVState.builder(ALIASES);
    }

    @NonNull
    protected MapReadableKVState<ProtoBytes, AccountID> readableEcdsaKeyAliasState() {
        return emptyReadableAliasStateBuilder()
                .value(new ProtoBytes(ecdsaAlias.alias()), idFactory.newAccountId(accountNum))
                .build();
    }

    @NonNull
    protected MapWritableKVState<ProtoBytes, AccountID> writableAliasesStateWithEcdsaKey() {
        final var aliaskey = asKeyFromAlias(ecdsaAlias.aliasOrThrow());
        aliasEvmAddress = extractEvmAddress(aliaskey);
        return emptyWritableAliasStateBuilder()
                .value(new ProtoBytes(ecdsaAlias.alias()), idFactory.newAccountId(deleteAccountNum))
                .value(new ProtoBytes(aliasEvmAddress), idFactory.newAccountId(deleteAccountNum))
                .build();
    }

    protected Account givenValidAccount(final long accountNum) {
        return new Account(
                idFactory.newAccountId(accountNum),
                alias.alias(),
                key,
                1_234_567L,
                payerBalance,
                "testAccount",
                false,
                1_234L,
                1_234_568L,
                UNSET_STAKED_ID,
                true,
                true,
                TokenID.newBuilder().tokenNum(3L).build(),
                NftID.newBuilder().tokenId(TokenID.newBuilder().tokenNum(2L)).build(),
                1,
                2,
                10,
                2,
                3,
                false,
                2,
                0,
                1000L,
                idFactory.newAccountId(2L),
                72000,
                0,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                2,
                false,
                null,
                null,
                0);
    }

    protected void givenValidContract() {
        account = new Account(
                idFactory.newAccountId(accountNum),
                alias.alias(),
                key,
                1_234_567L,
                payerBalance,
                "testAccount",
                false,
                1_234L,
                1_234_568L,
                UNSET_STAKED_ID,
                true,
                true,
                TokenID.newBuilder().tokenNum(3L).build(),
                NftID.newBuilder().tokenId(TokenID.newBuilder().tokenNum(2L)).build(),
                1,
                2,
                10,
                1,
                3,
                true,
                2,
                0,
                1000L,
                idFactory.newAccountId(2L),
                72000,
                0,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                2,
                false,
                null,
                null,
                0);
    }

    /**
     * Create an account ID with the given shard and realm.
     * @param num the account number
     * @param shard the shard number
     * @param realm the realm number
     * @return the account ID
     */
    protected AccountID accountIDWithShardAndRealm(final long num, final long shard, final long realm) {
        return AccountID.newBuilder()
                .shardNum(shard)
                .realmNum(realm)
                .accountNum(num)
                .build();
    }
}
