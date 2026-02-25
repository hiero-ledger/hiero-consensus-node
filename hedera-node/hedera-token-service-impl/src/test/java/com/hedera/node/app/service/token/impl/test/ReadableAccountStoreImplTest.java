// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static com.hedera.node.app.service.token.impl.TokenServiceImpl.HBARS_TO_TINYBARS;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.hapi.utils.EthSigsUtils;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoHandlerTestBase;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableKVState;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableAccountStoreImplTest extends CryptoHandlerTestBase {

    private ReadableAccountStoreImpl subject;

    @Mock
    private Account account;

    @BeforeEach
    public void setUp() {
        super.setUp();
        readableAccounts = emptyReadableAccountStateBuilder().value(id, account).build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        readableAliases = readableAliasState();
        given(readableStates.<ProtoBytes, AccountID>get(ALIASES_STATE_ID)).willReturn(readableAliases);
        subject = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
    }

    @SuppressWarnings("unchecked")
    @Test
    void getAccount() {
        // given
        given(account.accountId()).willReturn(id);
        given(account.memo()).willReturn("");
        given(account.key()).willReturn(accountKey);
        given(account.expirationSecond()).willReturn(5L);
        given(account.tinybarBalance()).willReturn(7L * HBARS_TO_TINYBARS);
        given(account.memo()).willReturn("Hello World");
        given(account.deleted()).willReturn(true);
        given(account.receiverSigRequired()).willReturn(true);
        given(account.numberOwnedNfts()).willReturn(11L);
        given(account.maxAutoAssociations()).willReturn(13);
        given(account.usedAutoAssociations()).willReturn(17);
        given(account.numberAssociations()).willReturn(19);
        given(account.numberPositiveBalances()).willReturn(23);
        given(account.ethereumNonce()).willReturn(29L);
        given(account.stakedToMe()).willReturn(31L);
        given(account.stakePeriodStart()).willReturn(37L);
        given(account.stakeAtStartOfLastRewardedPeriod()).willReturn(37L);
        given(account.stakedAccountId())
                .willReturn(AccountID.newBuilder()
                        .shardNum(1)
                        .realmNum(2)
                        .accountNum(41L)
                        .build());
        given(account.declineReward()).willReturn(true);
        given(account.autoRenewAccountId())
                .willReturn(AccountID.newBuilder()
                        .shardNum(1)
                        .realmNum(2)
                        .accountNum(53L)
                        .build());
        given(account.autoRenewSeconds()).willReturn(59L);
        given(account.alias()).willReturn(Bytes.wrap(new byte[] {1, 2, 3}));
        given(account.smartContract()).willReturn(true);

        // when
        final var mappedAccount = subject.getAccountById(id);

        // then
        assertThat(mappedAccount).isNotNull();
        assertThat(mappedAccount.key()).isEqualTo(accountKey);
        assertThat(mappedAccount.expirationSecond()).isEqualTo(5L);
        assertThat(mappedAccount.tinybarBalance()).isEqualTo(7L * HBARS_TO_TINYBARS);
        assertThat(mappedAccount.memo()).isEqualTo("Hello World");
        assertThat(mappedAccount.deleted()).isTrue();
        assertThat(mappedAccount.receiverSigRequired()).isTrue();
        assertThat(mappedAccount.numberOwnedNfts()).isEqualTo(11L);
        assertThat(mappedAccount.maxAutoAssociations()).isEqualTo(13);
        assertThat(mappedAccount.usedAutoAssociations()).isEqualTo(17);
        assertThat(mappedAccount.numberAssociations()).isEqualTo(19);
        assertThat(mappedAccount.numberPositiveBalances()).isEqualTo(23);
        assertThat(mappedAccount.ethereumNonce()).isEqualTo(29L);
        assertThat(mappedAccount.stakedToMe()).isEqualTo(31L);
        assertThat(mappedAccount.stakePeriodStart()).isEqualTo(37L);
        assertThat(mappedAccount.stakedAccountId())
                .isEqualTo(AccountID.newBuilder()
                        .shardNum(1)
                        .realmNum(2)
                        .accountNum(41L)
                        .build());
        assertThat(mappedAccount.declineReward()).isTrue();
        assertThat(mappedAccount.stakeAtStartOfLastRewardedPeriod()).isEqualTo(37L);
        assertThat(mappedAccount.autoRenewAccountId().accountNum()).isEqualTo(53L);
        assertThat(mappedAccount.autoRenewSeconds()).isEqualTo(59L);
        assertThat(mappedAccount.accountId()).isEqualTo(id);
        assertThat(mappedAccount.alias()).isEqualTo(Bytes.wrap(new byte[] {1, 2, 3}));
        assertThat(mappedAccount.smartContract()).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void getsEmptyAccount() {
        // given
        given(account.key()).willReturn(accountKey);
        given(account.memo()).willReturn("");
        given(account.accountId()).willReturn(id);

        // when
        final var mappedAccount = subject.getAccountById(id);

        // then
        assertThat(mappedAccount).isNotNull();
        assertThat(mappedAccount.key()).isEqualTo(accountKey);
        assertThat(mappedAccount.expirationSecond()).isZero();
        assertThat(mappedAccount.tinybarBalance()).isZero();
        assertThat(mappedAccount.tinybarBalance()).isZero();
        assertThat(mappedAccount.memo()).isEmpty();
        assertThat(mappedAccount.deleted()).isFalse();
        assertThat(mappedAccount.receiverSigRequired()).isFalse();
        assertThat(mappedAccount.numberOwnedNfts()).isZero();
        assertThat(mappedAccount.maxAutoAssociations()).isZero();
        assertThat(mappedAccount.usedAutoAssociations()).isZero();
        assertThat(mappedAccount.numberAssociations()).isZero();
        assertThat(mappedAccount.numberPositiveBalances()).isZero();
        assertThat(mappedAccount.ethereumNonce()).isZero();
        assertThat(mappedAccount.stakedToMe()).isZero();
        assertThat(mappedAccount.stakePeriodStart()).isZero();
        assertThat(mappedAccount.stakedNodeId()).isZero();
        assertThat(mappedAccount.declineReward()).isFalse();
        assertThat(mappedAccount.stakeAtStartOfLastRewardedPeriod()).isZero();
        assertThat(mappedAccount.autoRenewAccountId()).isNull();
        assertThat(mappedAccount.autoRenewSeconds()).isZero();
        assertThat(mappedAccount.accountId()).isEqualTo(id);
        assertThat(mappedAccount.alias()).isNull();
        assertThat(mappedAccount.smartContract()).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void getsNullIfMissingAccount() {
        readableAccounts = emptyReadableAccountStateBuilder().build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        subject = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
        readableStore = subject;

        final var result = subject.getAccountById(id);
        assertThat(result).isNull();
    }

    @Test
    void doesntRetryEcdsaKeyAliasAsEvmAddressWhenMissingUsingGet() {
        final var aSecp256K1Key = Key.newBuilder()
                .ecdsaSecp256k1(Bytes.fromHex("030101010101010101010101010101010101010101010101010101010101010101"))
                .build();
        final var evmAddress = EthSigsUtils.recoverAddressFromPubKey(
                aSecp256K1Key.ecdsaSecp256k1OrThrow().toByteArray());

        readableAccounts = emptyReadableAccountStateBuilder().value(id, account).build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        readableAliases = emptyReadableAliasStateBuilder()
                .value(new ProtoBytes(Bytes.wrap(evmAddress)), idFactory.newAccountId(accountNum))
                .build();
        given(readableStates.<ProtoBytes, AccountID>get(ALIASES_STATE_ID)).willReturn(readableAliases);

        subject = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);

        final var protoKeyId = AccountID.newBuilder()
                .alias(Key.PROTOBUF.toBytes(aSecp256K1Key))
                .build();
        final var result = subject.getAccountById(protoKeyId);
        assertThat(result).isNull();
    }

    @Test
    void retriesEcdsaKeyAliasAsEvmAddressWhenMissingUsingGetByAlias() {
        final var aSecp256K1Key = Key.newBuilder()
                .ecdsaSecp256k1(Bytes.fromHex("030101010101010101010101010101010101010101010101010101010101010101"))
                .build();
        final var evmAddress = EthSigsUtils.recoverAddressFromPubKey(
                aSecp256K1Key.ecdsaSecp256k1OrThrow().toByteArray());

        readableAccounts = emptyReadableAccountStateBuilder().value(id, account).build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        readableAliases = emptyReadableAliasStateBuilder()
                .value(new ProtoBytes(Bytes.wrap(evmAddress)), idFactory.newAccountId(accountNum))
                .build();
        given(readableStates.<ProtoBytes, AccountID>get(ALIASES_STATE_ID)).willReturn(readableAliases);

        subject = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);

        final var protoKeyId = idFactory.newAccountIdWithAlias(Key.PROTOBUF.toBytes(aSecp256K1Key));
        final var result = subject.getAliasedAccountById(protoKeyId);
        assertThat(result).isNotNull();
    }

    @Test
    void ignoresInvalidEcdsaKey() {
        final var aSecp256K1Key = Key.newBuilder()
                .ecdsaSecp256k1(Bytes.fromHex("990101010101010101010101010101010101010101010101010101010101010101"))
                .build();
        final var protoKeyId = AccountID.newBuilder()
                .alias(Key.PROTOBUF.toBytes(aSecp256K1Key))
                .build();
        final var result = subject.getAccountById(protoKeyId);
        assertThat(result).isNull();
    }

    @Test
    void doesNotRetryNonEcdsaKeyAlias() {
        final var aEd25519Key = Key.newBuilder()
                .ed25519(Bytes.fromHex("0101010101010101010101010101010101010101010101010101010101010101"))
                .build();

        final var protoKeyId =
                AccountID.newBuilder().alias(Key.PROTOBUF.toBytes(aEd25519Key)).build();
        final var result = subject.getAccountById(protoKeyId);
        assertThat(result).isNull();
    }

    @Test
    void ignoresNonsenseAlias() {
        subject = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
        final var nonsenseId = AccountID.newBuilder()
                .alias(Bytes.wrap("Not an alias of any sort"))
                .build();
        final var result = subject.getAccountById(nonsenseId);
        assertThat(result).isNull();
    }

    @Test
    void getAccountIDByAlias() {
        final var accountId = subject.getAccountIDByAlias(0, 0, alias.alias());
        assertThat(accountId).isEqualTo(id);
        final var accountId2 = subject.getAccountIDByAlias(0, 0, Bytes.wrap("test"));
        assertThat(accountId2).isNull();
    }

    @Test
    void getSizeOfState() {
        final var store = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
        assertEquals(readableEntityCounters.getCounterFor(EntityType.ACCOUNT), store.sizeOfAccountState());
    }

    @Test
    void containsWorksAsExpected() {
        // Subject is pre-populated with this ID
        assertThat(subject.contains(id)).isTrue();

        // Pass any account ID that isn't in the store
        assertThat(subject.contains(AccountID.newBuilder()
                        .shardNum(1)
                        .realmNum(2)
                        .accountNum(Long.MAX_VALUE)
                        .build()))
                .isFalse();

        //noinspection DataFlowIssue
        Assertions.assertThatThrownBy(() -> subject.contains(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void warmWarmsUnderlyingState(@Mock ReadableKVState<AccountID, Account> accounts) {
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(accounts);
        final var accountStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
        accountStore.warm(id);
        verify(accounts).warm(id);
    }
}
