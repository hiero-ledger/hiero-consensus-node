// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.prehandle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNRESOLVABLE_REQUIRED_SIGNERS;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static com.hedera.node.app.workflows.prehandle.PreHandleContextListUpdatesTest.A_COMPLEX_KEY;
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
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        given(storeFactory.getStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountById(PAYER)).willReturn(account);
        given(account.keyOrThrow()).willReturn(payerKey);

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
}
