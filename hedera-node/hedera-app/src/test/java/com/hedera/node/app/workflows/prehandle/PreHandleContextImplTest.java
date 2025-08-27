// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.prehandle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
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

    @Nested
    @DisplayName("Requesting keys of child transactions")
    final class KeyRequestTest {

        @BeforeEach
        void setup() {
            given(accountStore.getAccountById(ERIN.accountID())).willReturn(ERIN.account());
        }

        @SuppressWarnings("ConstantConditions")
        @Test
        void testAllKeysForTransactionWithInvalidParameters() throws PreCheckException {
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
