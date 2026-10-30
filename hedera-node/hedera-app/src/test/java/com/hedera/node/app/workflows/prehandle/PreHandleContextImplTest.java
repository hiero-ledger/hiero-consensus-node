// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.prehandle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNRESOLVABLE_REQUIRED_SIGNERS;
import static com.hedera.hapi.util.HapiUtils.EMPTY_KEY_LIST;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static com.hedera.node.app.workflows.prehandle.PreHandleContextListUpdatesTest.A_COMPLEX_KEY;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.SO_FAR_SO_GOOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fixtures.Scenarios;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.key.KeyRequirement;
import com.hedera.node.app.spi.store.ReadableStoreFactory;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PreHandleContextImplTest implements Scenarios {
    private static final AccountID PAYER = AccountID.newBuilder().accountNum(3L).build();

    private static final Key payerKey = A_COMPLEX_KEY;

    private static final Key otherKey = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(1)
                    .keys(KeyList.newBuilder()
                            .keys(Key.newBuilder()
                                    .contractID(ContractID.newBuilder()
                                            .contractNum(123456L)
                                            .build())
                                    .ed25519(Bytes.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                                    .build())))
            .build();

    @Mock
    ReadableStoreFactory storeFactory;

    @Mock(strictness = LENIENT)
    ReadableAccountStore accountStore;

    @Mock
    Account account;

    @Mock
    Configuration configuration;

    @Mock
    NodeInfo creatorInfo;

    @Mock
    TransactionDispatcher dispatcher;

    @Mock
    private TransactionChecker transactionChecker;

    private PreHandleContextImpl subject;

    @BeforeEach
    void setup() throws PreCheckException {
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountById(PAYER)).willReturn(account);
        given(account.keyOrThrow()).willReturn(payerKey);
        given(account.accountIdOrThrow()).willReturn(PAYER);

        final var txn = createAccountTransaction();
        subject =
                new PreHandleContextImpl(storeFactory, txn, configuration, dispatcher, transactionChecker, creatorInfo);
    }

    @Test
    void gettersWork() {
        subject.requireKey(otherKey);

        assertThat(subject.body()).isEqualTo(createAccountTransaction());
        assertThat(subject.payerKey()).isEqualTo(payerKey);
        assertThat(subject.requiredNonPayerKeys()).isEqualTo(Set.of(otherKey));
        assertThat(subject.configuration()).isEqualTo(configuration);
    }

    @Test
    @SuppressWarnings("ConstantConditions")
    void requireInvalidRawKeyRemainsANoOp() {
        assertThat(subject.requireKey(null)).isSameAs(subject);
        assertThat(subject.requireKey(Key.DEFAULT)).isSameAs(subject);
        assertThat(subject.requiredKeyRequirements()).isEmpty();
        assertThat(subject.requiredNonPayerKeys()).isEmpty();
    }

    @Test
    void optionalEmptyKeyRetainsPrecheckFailure() {
        assertThatThrownBy(() -> subject.optionalKey(EMPTY_KEY_LIST))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_ACCOUNT_ID));
        assertThat(subject.optionalKeyRequirements()).isEmpty();
    }

    private TransactionBody createAccountTransaction() {
        final var transactionID = TransactionID.newBuilder()
                .accountID(PAYER)
                .transactionValidStart(Timestamp.newBuilder().seconds(123_456L).build());
        final var createTxnBody = CryptoCreateTransactionBody.newBuilder()
                .key(otherKey)
                .receiverSigRequired(true)
                .memo("Create Account")
                .build();
        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .cryptoCreateAccount(createTxnBody)
                .build();
    }

    @Test
    void creatorInfoWorks() {
        final var result = subject.creatorInfo();
        assertThat(result).isEqualTo(creatorInfo);
    }

    @Test
    @DisplayName("Key/account sets exposed to PreHandleResult are unmodifiable")
    void exposedKeyAndAccountSetsAreUnmodifiable() throws PreCheckException {
        subject.requireKey(otherKey);

        // PreHandleContextImpl exposes unmodifiable views, so a consumer cannot mutate them.
        assertThatThrownBy(() -> subject.requiredNonPayerKeys().add(payerKey))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> subject.optionalNonPayerKeys().add(payerKey))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> subject.requiredHollowAccounts().add(account))
                .isInstanceOf(UnsupportedOperationException.class);

        // Feeding raw, mutable sets (not the unmodifiable views above) proves PreHandleResult does the wrapping
        // itself: the exposed collections still reject mutation...
        final var rawRequiredKeys = new LinkedHashSet<>(subject.requiredNonPayerKeys());
        final var rawOptionalKeys = new LinkedHashSet<>(subject.optionalNonPayerKeys());
        final var rawHollowAccounts = new LinkedHashSet<>(subject.requiredHollowAccounts());
        final var result = new PreHandleResult(
                PAYER,
                payerKey,
                SO_FAR_SO_GOOD,
                OK,
                null,
                rawRequiredKeys,
                rawOptionalKeys,
                rawHollowAccounts,
                Map.of(),
                null,
                0L);
        assertThatThrownBy(() -> result.getRequiredKeys().add(payerKey))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.getOptionalKeys().add(payerKey))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.getHollowAccounts().add(account))
                .isInstanceOf(UnsupportedOperationException.class);

        // ...and that it snapshots them, so mutating the source sets after construction is not visible through
        // the exposed collections.
        rawRequiredKeys.add(payerKey);
        rawOptionalKeys.add(payerKey);
        rawHollowAccounts.add(account);
        assertThat(result.getRequiredKeys()).doesNotContain(payerKey);
        assertThat(result.getOptionalKeys()).doesNotContain(payerKey);
        assertThat(result.getHollowAccounts()).doesNotContain(account);
    }

    @Nested
    @DisplayName("Identity-scoped requirements")
    class IdentityScopedRequirementsTests {
        private final AccountID ownerId =
                AccountID.newBuilder().shardNum(1).realmNum(2).accountNum(1234).build();
        private final AccountID otherOwnerId =
                AccountID.newBuilder().shardNum(1).realmNum(2).accountNum(1235).build();

        @BeforeEach
        void configureAccountValidation() {
            org.mockito.Mockito.lenient()
                    .when(configuration.getConfigData(AccountsConfig.class))
                    .thenReturn(HederaTestConfigBuilder.createConfig().getConfigData(AccountsConfig.class));
        }

        @Test
        void sharedPayerKeyRetainsIndependentAccountAndExplicitRequirements() throws PreCheckException {
            final var owner =
                    Account.newBuilder().accountId(ownerId).key(payerKey).build();
            final var otherOwner =
                    Account.newBuilder().accountId(otherOwnerId).key(payerKey).build();
            given(accountStore.getAccountById(ownerId)).willReturn(owner);
            given(accountStore.getAccountById(otherOwnerId)).willReturn(otherOwner);

            subject.requireKeyOrThrow(ownerId, INVALID_ACCOUNT_ID);
            subject.requireKeyOrThrow(otherOwnerId, INVALID_ACCOUNT_ID);
            subject.requireKey(payerKey);
            subject.requireKeyOrThrow(ownerId, INVALID_ACCOUNT_ID);

            assertThat(subject.requiredKeyRequirements())
                    .containsExactly(
                            KeyRequirement.forAccount(ownerId, payerKey),
                            KeyRequirement.forAccount(otherOwnerId, payerKey),
                            KeyRequirement.forKey(payerKey));
            assertThat(subject.requiredNonPayerKeys()).isEmpty();
            assertThat(subject.payerKeyRequirement()).isEqualTo(KeyRequirement.forAccount(PAYER, payerKey));
        }

        @Test
        void payerAccountRequirementIsCollectedInsteadOfElided() throws PreCheckException {
            given(accountStore.getAccountById(PAYER))
                    .willReturn(
                            Account.newBuilder().accountId(PAYER).key(payerKey).build());

            subject.requireKeyOrThrow(PAYER, INVALID_ACCOUNT_ID);

            assertThat(subject.requiredKeyRequirements()).containsExactly(KeyRequirement.forAccount(PAYER, payerKey));
            assertThat(subject.requiredNonPayerKeys()).isEmpty();
        }

        @Test
        void aliasAndContractAddressResolveToTheSameCanonicalRequirement() throws PreCheckException {
            final var alias = AccountID.newBuilder()
                    .shardNum(ownerId.shardNum())
                    .realmNum(ownerId.realmNum())
                    .alias(Bytes.wrap("canonical-owner-alias"))
                    .build();
            final var contract = ContractID.newBuilder()
                    .shardNum(ownerId.shardNum())
                    .realmNum(ownerId.realmNum())
                    .evmAddress(alias.aliasOrThrow())
                    .build();
            final var owner =
                    Account.newBuilder().accountId(ownerId).key(payerKey).build();
            given(accountStore.getAliasedAccountById(alias)).willReturn(owner);
            given(accountStore.getAccountById(ownerId)).willReturn(owner);
            given(accountStore.getContractById(contract)).willReturn(owner);

            subject.requireAliasedKeyOrThrow(alias, INVALID_ACCOUNT_ID);
            subject.requireKeyOrThrow(ownerId, INVALID_ACCOUNT_ID);
            subject.requireKeyOrThrow(contract, INVALID_CONTRACT_ID);

            assertThat(subject.requiredKeyRequirements()).containsExactly(KeyRequirement.forAccount(ownerId, payerKey));
        }

        @Test
        void optionalPayerEqualKeyKeepsItsScope() throws PreCheckException {
            subject.optionalKey(payerKey);
            subject.optionalKeyRequirement(KeyRequirement.forAccount(ownerId, payerKey));

            assertThat(subject.optionalKeyRequirements())
                    .containsExactly(KeyRequirement.forKey(payerKey), KeyRequirement.forAccount(ownerId, payerKey));
            assertThat(subject.optionalNonPayerKeys()).isEmpty();
        }

        @Test
        void arbitraryFinisherDoesNotConferAccountAuthority() throws PreCheckException {
            given(accountStore.getAccountById(ownerId))
                    .willReturn(Account.newBuilder()
                            .accountId(ownerId)
                            .key(payerKey)
                            .build());

            subject.requireKeyOrThrow(ownerId, ignored -> otherKey, INVALID_ACCOUNT_ID);

            assertThat(subject.requiredKeyRequirements()).containsExactly(KeyRequirement.forKey(otherKey));
        }

        @Test
        void lookupReturnsCanonicalRequirementWithoutCollectingIt() throws PreCheckException {
            final var alias =
                    AccountID.newBuilder().alias(Bytes.wrap("owner-alias")).build();
            given(accountStore.getAccountById(alias))
                    .willReturn(Account.newBuilder()
                            .accountId(ownerId)
                            .key(payerKey)
                            .build());

            assertThat(subject.keyRequirementFor(alias, INVALID_ACCOUNT_ID))
                    .isEqualTo(KeyRequirement.forAccount(ownerId, payerKey));
            assertThat(subject.requiredKeyRequirements()).isEmpty();
        }

        @Test
        void hollowPayerAccountRequiresHollowSignature() throws PreCheckException {
            final var hollowPayerId = AccountID.newBuilder().accountNum(1234L).build();
            final var hollowPayer = Account.newBuilder()
                    .accountId(hollowPayerId)
                    .key(EMPTY_KEY_LIST)
                    .alias(Bytes.wrap(new byte[20]))
                    .build();
            given(accountStore.getAccountById(hollowPayerId)).willReturn(hollowPayer);
            final var context = new PreHandleContextImpl(
                    storeFactory,
                    createAccountTransaction(),
                    hollowPayerId,
                    configuration,
                    dispatcher,
                    transactionChecker,
                    creatorInfo);

            context.requireKeyOrThrow(hollowPayerId, INVALID_ACCOUNT_ID);

            assertThat(context.requiredHollowAccounts()).containsExactly(hollowPayer);
            assertThat(context.requiredKeyRequirements()).isEmpty();
            assertThat(context.requiredNonPayerKeys()).isEmpty();
        }

        @Test
        void composedRequirementWithInvalidAlternativeIsIgnored() {
            final var requirement = KeyRequirement.anyOf(
                    List.of(KeyRequirement.forAccount(ownerId, payerKey), KeyRequirement.forKey(Key.DEFAULT)));

            subject.requireKeyRequirement(requirement);

            assertThat(subject.requiredKeyRequirements()).isEmpty();
            assertThat(subject.requiredNonPayerKeys()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Optional keys methods")
    class OptionalKeysTests {
        private static final Key PAYER_KEY = Key.newBuilder().build();

        @Test
        void optionalKeyAddsKeyToOptionalSet() throws PreCheckException {
            subject.optionalKey(otherKey);
            assertThat(subject.optionalNonPayerKeys()).contains(otherKey);
        }

        @Test
        void optionalKeyDoesNotAddPayerKey() throws PreCheckException {
            subject.optionalKey(PAYER_KEY);
            assertThat(subject.optionalNonPayerKeys()).isEmpty();
        }

        @Test
        void optionalKeysAddsMultipleKeys() throws PreCheckException {
            subject.optionalKeys(Set.of(payerKey, otherKey));
            assertThat(subject.optionalNonPayerKeys()).containsExactlyInAnyOrder(otherKey);
        }
    }

    @Nested
    @DisplayName("requireKeyIfReceiverSigRequired methods")
    class RequireKeyIfReceiverSigRequiredTests {

        @Test
        void requireKeyIfReceiverSigRequiredForAccountIDReturnsEarlyForNullAccount() throws PreCheckException {
            subject.requireKeyIfReceiverSigRequired((AccountID) null, INVALID_ACCOUNT_ID);

            verify(accountStore).getAccountById(PAYER);
            verifyNoMoreInteractions(accountStore);
        }

        @Test
        void requireKeyIfReceiverSigRequiredForAccountIDReturnsEarlyForDefaultAccount() throws PreCheckException {
            subject.requireKeyIfReceiverSigRequired(AccountID.DEFAULT, INVALID_ACCOUNT_ID);

            verify(accountStore).getAccountById(PAYER);
            verifyNoMoreInteractions(accountStore);
        }

        @Test
        void requireKeyIfReceiverSigRequiredForAccountIDThrowsWhenAccountNotFound() {
            final var accountId = AccountID.newBuilder().accountNum(123L).build();
            given(accountStore.getAccountById(accountId)).willReturn(null);

            assertThatThrownBy(() -> subject.requireKeyIfReceiverSigRequired(accountId, INVALID_ACCOUNT_ID))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_ACCOUNT_ID));
        }

        @Test
        void requireKeyIfReceiverSigRequiredForAccountIDReturnsEarlyWhenReceiverSigNotRequired()
                throws PreCheckException {
            final var accountId = AccountID.newBuilder().accountNum(123L).build();
            final var account = mock(Account.class);
            given(accountStore.getAccountById(accountId)).willReturn(account);
            given(account.receiverSigRequired()).willReturn(false);

            subject.requireKeyIfReceiverSigRequired(accountId, INVALID_ACCOUNT_ID);

            verify(account).receiverSigRequired();
            verifyNoMoreInteractions(account);
        }

        @ParameterizedTest
        @MethodSource("degenerateKeys")
        void requireKeyIfReceiverSigRequiredForAccountIDThrowsForDegenerateKey(final Key degenerateKey) {
            final var accountId = AccountID.newBuilder().accountNum(1234L).build();
            final var account = mock(Account.class);
            final var accountsConfig = mock(AccountsConfig.class);
            given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
            given(accountsConfig.stakingRewardAccount()).willReturn(800L);
            given(accountsConfig.nodeRewardAccount()).willReturn(801L);
            given(accountStore.getAccountById(accountId)).willReturn(account);
            given(account.receiverSigRequired()).willReturn(true);
            given(account.accountIdOrThrow()).willReturn(accountId);
            given(account.keyOrElse(any())).willReturn(degenerateKey);
            given(account.key()).willReturn(degenerateKey);

            // A key that cannot be satisfied must not be silently dropped by requireKey(), leaving the
            // receiverSigRequired flag unenforced
            assertThatThrownBy(() -> subject.requireKeyIfReceiverSigRequired(accountId, INVALID_ACCOUNT_ID))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_ACCOUNT_ID));
            assertThat(subject.requiredNonPayerKeys()).isEmpty();
        }

        static Stream<Key> degenerateKeys() {
            final var goodEd25519 =
                    Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
            final var emptyKeyList = Key.newBuilder().keyList(KeyList.DEFAULT).build();
            return Stream.of(
                    emptyKeyList,
                    Key.newBuilder()
                            .keyList(KeyList.newBuilder().keys(emptyKeyList).build())
                            .build(),
                    Key.newBuilder()
                            .keyList(KeyList.newBuilder()
                                    .keys(emptyKeyList, goodEd25519)
                                    .build())
                            .build(),
                    Key.newBuilder()
                            .thresholdKey(ThresholdKey.newBuilder()
                                    .threshold(0)
                                    .keys(KeyList.newBuilder().keys(goodEd25519).build()))
                            .build(),
                    Key.newBuilder().ed25519(Bytes.wrap(new byte[5])).build());
        }

        @Test
        void requireKeyIfReceiverSigRequiredForContractIDReturnsEarlyForNullContract() throws PreCheckException {
            subject.requireKeyIfReceiverSigRequired((ContractID) null, INVALID_CONTRACT_ID);

            verify(accountStore).getAccountById(PAYER);
            verifyNoMoreInteractions(accountStore);
        }

        @Test
        void requireKeyIfReceiverSigRequiredForContractIDThrowsWhenContractNotFound() {
            final var contractID = ContractID.newBuilder().contractNum(123L).build();
            given(accountStore.getContractById(contractID)).willReturn(null);

            assertThatThrownBy(() -> subject.requireKeyIfReceiverSigRequired(contractID, INVALID_CONTRACT_ID))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_CONTRACT_ID));
        }

        @Test
        void requireKeyIfReceiverSigRequiredForContractIDReturnsEarlyWhenReceiverSigNotRequired()
                throws PreCheckException {
            final var contractID = ContractID.newBuilder().contractNum(123L).build();
            final var contract = mock(Account.class);
            given(accountStore.getContractById(contractID)).willReturn(contract);
            given(contract.receiverSigRequired()).willReturn(false);

            subject.requireKeyIfReceiverSigRequired(contractID, INVALID_CONTRACT_ID);

            verify(contract).receiverSigRequired();
            verifyNoMoreInteractions(contract);
        }
    }

    @Nested
    @DisplayName("HollowAccount methods")
    class HollowAccountTests {

        private static final Key EMPTY_KEY_LIST =
                Key.newBuilder().keyList(KeyList.DEFAULT).build();
        private static final Bytes HOLLOW_ALIAS = Bytes.wrap("12345678909876543210");

        @Test
        void requireSignatureForHollowAccountAddsAccountToRequiredSet() {
            final var hollowAccount = mock(Account.class);
            final var accountId = AccountID.newBuilder().accountNum(1234L).build();
            given(hollowAccount.accountId()).willReturn(accountId);
            given(hollowAccount.accountIdOrThrow()).willReturn(accountId);
            given(hollowAccount.keyOrElse(EMPTY_KEY_LIST)).willReturn(EMPTY_KEY_LIST);
            given(hollowAccount.alias()).willReturn(HOLLOW_ALIAS);

            subject.requireSignatureForHollowAccount(hollowAccount);

            assertThat(subject.requiredHollowAccounts()).contains(hollowAccount);
        }

        @Test
        void requireSignatureForHollowAccountThrowsForNonHollowAccount() {
            final var nonHollowAccount = mock(Account.class);
            final var accountId = AccountID.newBuilder().accountNum(123L).build();
            given(nonHollowAccount.accountId()).willReturn(accountId);
            given(nonHollowAccount.accountIdOrThrow()).willReturn(accountId);

            assertThatThrownBy(() -> subject.requireSignatureForHollowAccount(nonHollowAccount))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is not a hollow account");
        }

        @Test
        void optionalSignatureForHollowAccountAddsAccountToOptionalSet() {
            final var hollowAccount = mock(Account.class);
            final var accountId = AccountID.newBuilder().accountNum(1234L).build();
            given(hollowAccount.accountId()).willReturn(accountId);
            given(hollowAccount.accountIdOrThrow()).willReturn(accountId);
            given(hollowAccount.keyOrElse(EMPTY_KEY_LIST)).willReturn(EMPTY_KEY_LIST);
            given(hollowAccount.alias()).willReturn(HOLLOW_ALIAS);

            subject.optionalSignatureForHollowAccount(hollowAccount);

            assertThat(subject.optionalHollowAccounts()).contains(hollowAccount);
        }

        @Test
        void optionalSignatureForHollowAccountThrowsForNonHollowAccount() {
            final var nonHollowAccount = mock(Account.class);
            final var accountId = AccountID.newBuilder().accountNum(123L).build();
            given(nonHollowAccount.accountId()).willReturn(accountId);
            given(nonHollowAccount.accountIdOrThrow()).willReturn(accountId);

            assertThatThrownBy(() -> subject.optionalSignatureForHollowAccount(nonHollowAccount))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is not a hollow account");
        }

        @Test
        void requireSignatureForHollowAccountCreationAddsToRequiredSet() {
            final var alias = Bytes.wrap("hollowAccountAlias");

            subject.requireSignatureForHollowAccountCreation(alias);

            assertThat(subject.requiredHollowAccounts()).hasSize(1);
            assertThat(subject.requiredHollowAccounts().iterator().next().alias())
                    .isEqualTo(alias);
        }
    }

    @Nested
    @DisplayName("GetKeyFromAccount method")
    class GetKeyFromAccountTests {
        @Test
        void getKeyFromAccountThrowsWhenAccountDoesNotExist() {
            final var accountId = AccountID.newBuilder().accountNum(456L).build();
            given(accountStore.getAccountById(accountId)).willReturn(null);

            assertThatThrownBy(() -> subject.getAccountKey(accountId))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_ACCOUNT_ID));
        }

        @Test
        void getKeyFromAccountThrowsWhenAccountIsDeleted() {
            final var accountId = AccountID.newBuilder().accountNum(456L).build();
            final var account = mock(Account.class);
            given(accountStore.getAccountById(accountId)).willReturn(account);
            given(account.deleted()).willReturn(true);

            assertThatThrownBy(() -> subject.getAccountKey(accountId))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(ACCOUNT_DELETED));
        }

        @Test
        void getKeyFromAccountReturnsKeyWhenAccountExists() throws PreCheckException {
            final var accountId = AccountID.newBuilder().accountNum(1456L).build();
            final var account = mock(Account.class);
            final var accountKey =
                    Key.newBuilder().ed25519(Bytes.wrap("account_key")).build();
            final var accountsConfig = mock(AccountsConfig.class);

            given(accountStore.getAccountById(accountId)).willReturn(account);
            given(account.deleted()).willReturn(false);
            given(account.accountIdOrThrow()).willReturn(accountId);
            given(account.keyOrThrow()).willReturn(accountKey);
            given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
            given(accountsConfig.stakingRewardAccount()).willReturn(789L);
            given(accountsConfig.nodeRewardAccount()).willReturn(987L);

            final var result = subject.getAccountKey(accountId);

            assertThat(result).isEqualTo(accountKey);
        }
    }

    @Nested
    @DisplayName("Requesting keys of child transactions")
    final class KeyRequestTest {

        @BeforeEach
        void setup() {
            given(accountStore.getAccountById(ERIN.accountID())).willReturn(ERIN.account());
        }

        @SuppressWarnings("ConstantConditions")
        @Test
        void testAllKeysForTransactionWithInvalidParameters() {
            // given
            final var bob = BOB.accountID();

            // when
            assertThatThrownBy(() -> subject.allKeysForTransaction(null, bob)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> subject.allKeysForTransaction(TransactionBody.DEFAULT, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void testAllKeysForTransactionSuccess() throws PreCheckException {
            // given
            doAnswer(invocation -> {
                        final var innerContext = invocation.getArgument(0, PreHandleContext.class);
                        innerContext.requireKey(BOB.account().key());
                        innerContext.optionalKey(CAROL.account().key());
                        return null;
                    })
                    .when(dispatcher)
                    .dispatchPreHandle(any());

            // when
            final var keys = subject.allKeysForTransaction(TransactionBody.DEFAULT, ERIN.accountID());

            // then
            assertThat(keys.payerKey()).isEqualTo(ERIN.account().key());
            assertThat(keys.requiredNonPayerKeys())
                    .containsExactly(BOB.account().key());
            assertThat(keys.optionalNonPayerKeys())
                    .containsExactly(CAROL.account().key());
        }

        @Test
        void testAllKeysForTransactionWithFailingPureCheck() throws PreCheckException {
            // given
            doThrow(new PreCheckException(INVALID_TRANSACTION_BODY))
                    .when(dispatcher)
                    .dispatchPureChecks(any());

            // then
            assertThatThrownBy(() -> subject.allKeysForTransaction(TransactionBody.DEFAULT, ERIN.accountID()))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_TRANSACTION_BODY));
        }

        @Test
        void testAllKeysForTransactionWithFailingPreHandle() throws PreCheckException {
            // given
            doThrow(new PreCheckException(INSUFFICIENT_ACCOUNT_BALANCE))
                    .when(dispatcher)
                    .dispatchPreHandle(any());

            // gathering keys should not throw exceptions except for inability to read a key.
            assertThatThrownBy(() -> subject.allKeysForTransaction(TransactionBody.DEFAULT, ERIN.accountID()))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(UNRESOLVABLE_REQUIRED_SIGNERS));
        }
    }

    @Nested
    class ForeignContractIdPayerKeyTests {
        // PAYER is account 0.0.3, so a self-referential contract key names contract 0.0.3.
        private static final ContractID SELF =
                ContractID.newBuilder().contractNum(3L).build();
        private static final ContractID FOREIGN =
                ContractID.newBuilder().contractNum(10002L).build();

        @Test
        void foreignContractIdPayerKeyIsRequired() throws PreCheckException {
            final var foreignKey = Key.newBuilder().contractID(FOREIGN).build();
            final var context = subjectWithPayerKey(foreignKey);
            context.requireKey(foreignKey);
            assertThat(context.requiredNonPayerKeys()).containsExactly(foreignKey);
        }

        @Test
        void payerAccountWithForeignContractKeyIsRetainedWithoutDuplicateFeeProjection() throws PreCheckException {
            final var foreignKey = Key.newBuilder().contractID(FOREIGN).build();
            final var context = subjectWithPayerKey(foreignKey);
            final var payer =
                    Account.newBuilder().accountId(PAYER).key(foreignKey).build();
            final var alias =
                    AccountID.newBuilder().alias(Bytes.wrap("payer-alias")).build();
            given(accountStore.getAccountById(PAYER)).willReturn(payer);
            given(accountStore.getAliasedAccountById(alias)).willReturn(payer);
            given(configuration.getConfigData(AccountsConfig.class))
                    .willReturn(HederaTestConfigBuilder.createConfig().getConfigData(AccountsConfig.class));

            context.requireKeyOrThrow(PAYER, INVALID_ACCOUNT_ID);
            context.requireAliasedKeyOrThrow(alias, INVALID_ACCOUNT_ID);

            assertThat(context.requiredKeyRequirements()).containsExactly(KeyRequirement.forAccount(PAYER, foreignKey));
            assertThat(context.requiredNonPayerKeys()).isEmpty();
        }

        @Test
        void payerAccountWithForeignDelegatableKeyIsRetainedWithoutDuplicateFeeProjection() throws PreCheckException {
            final var foreignKey =
                    Key.newBuilder().delegatableContractId(FOREIGN).build();
            final var context = subjectWithPayerKey(foreignKey);

            context.requireKeyRequirement(KeyRequirement.forAccount(PAYER, foreignKey));

            assertThat(context.requiredKeyRequirements()).containsExactly(KeyRequirement.forAccount(PAYER, foreignKey));
            assertThat(context.requiredNonPayerKeys()).isEmpty();
        }

        @Test
        void otherAccountSharingForeignPayerKeyStillHasLegacyProjection() throws PreCheckException {
            final var foreignKey = Key.newBuilder().contractID(FOREIGN).build();
            final var context = subjectWithPayerKey(foreignKey);
            final var otherId = AccountID.newBuilder().accountNum(1234).build();
            final var requirement = KeyRequirement.forAccount(otherId, foreignKey);

            context.requireKeyRequirement(requirement);

            assertThat(context.requiredKeyRequirements()).containsExactly(requirement);
            assertThat(context.requiredNonPayerKeys()).containsExactly(foreignKey);
        }

        @Test
        void selfContractIdPayerKeyIsElided() throws PreCheckException {
            final var selfKey = Key.newBuilder().contractID(SELF).build();
            final var context = subjectWithPayerKey(selfKey);
            context.requireKey(selfKey);
            assertThat(context.requiredNonPayerKeys()).isEmpty();
        }

        @Test
        void foreignDelegatableContractIdPayerKeyIsRequired() throws PreCheckException {
            final var foreignKey =
                    Key.newBuilder().delegatableContractId(FOREIGN).build();
            final var context = subjectWithPayerKey(foreignKey);
            context.requireKey(foreignKey);
            assertThat(context.requiredNonPayerKeys()).containsExactly(foreignKey);
        }

        @Test
        void selfDelegatableContractIdPayerKeyIsElided() throws PreCheckException {
            final var selfKey = Key.newBuilder().delegatableContractId(SELF).build();
            final var context = subjectWithPayerKey(selfKey);
            context.requireKey(selfKey);
            assertThat(context.requiredNonPayerKeys()).isEmpty();
        }

        @Test
        void cryptoPayerKeyIsStillElided() throws PreCheckException {
            final var context = subjectWithPayerKey(payerKey);
            context.requireKey(payerKey);
            assertThat(context.requiredNonPayerKeys()).isEmpty();
        }
    }

    private PreHandleContextImpl subjectWithPayerKey(final Key key) throws PreCheckException {
        given(account.keyOrThrow()).willReturn(key);
        return new PreHandleContextImpl(
                storeFactory, createAccountTransaction(), configuration, dispatcher, transactionChecker, creatorInfo);
    }

    @Nested
    @DisplayName("requireKeyOrThrow with a finisher")
    class RequireKeyOrThrowWithFinisherTests {

        private static final AccountID TARGET =
                AccountID.newBuilder().accountNum(456L).build();
        private static final Key VALID_STATE_KEY = Key.newBuilder()
                .ed25519(Bytes.wrap("12345678901234567890123456789012"))
                .build();

        @BeforeEach
        void setup() {
            final var accountsConfig = mock(AccountsConfig.class);
            given(accountStore.getAccountById(TARGET)).willReturn(account);
            given(account.deleted()).willReturn(false);
            given(account.accountIdOrThrow()).willReturn(TARGET);
            given(account.keyOrThrow()).willReturn(VALID_STATE_KEY);
            given(configuration.getConfigData(AccountsConfig.class)).willReturn(accountsConfig);
            given(accountsConfig.stakingRewardAccount()).willReturn(789L);
            given(accountsConfig.nodeRewardAccount()).willReturn(987L);
        }

        @Test
        @DisplayName("throws the caller's status when the finisher yields a structurally invalid key")
        void finisherProducingInvalidKeyFailsClosed() {
            final UnaryOperator<Key> finisher =
                    ignore -> Key.newBuilder().keyList(KeyList.DEFAULT).build();

            assertThatThrownBy(() -> subject.requireKeyOrThrow(TARGET, finisher, UNRESOLVABLE_REQUIRED_SIGNERS))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(UNRESOLVABLE_REQUIRED_SIGNERS));
            assertThat(subject.requiredNonPayerKeys()).isEmpty();
        }

        @Test
        @DisplayName("adds the transformed key when the finisher yields a valid key")
        void finisherProducingValidKeyIsRequired() throws PreCheckException {
            final var transformedKey = Key.newBuilder()
                    .thresholdKey(ThresholdKey.newBuilder()
                            .threshold(1)
                            .keys(KeyList.newBuilder().keys(VALID_STATE_KEY)))
                    .build();
            final UnaryOperator<Key> finisher = ignore -> transformedKey;

            subject.requireKeyOrThrow(TARGET, finisher, UNRESOLVABLE_REQUIRED_SIGNERS);

            assertThat(subject.requiredNonPayerKeys()).containsExactly(transformedKey);
        }
    }
}
