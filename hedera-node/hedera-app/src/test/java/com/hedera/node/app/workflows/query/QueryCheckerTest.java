// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.query;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.GET_ACCOUNT_DETAILS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_ID_DOES_NOT_EXIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_RECEIVING_NODE_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.estimatedFee;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.WorkflowCheck.NOT_INGEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.handlers.CryptoTransferHandler;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.hedera.node.app.spi.workflows.InsufficientBalanceException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.validation.ExpiryValidation;
import com.hedera.node.app.workflows.SolvencyPreCheck;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueryCheckerTest extends AppTestBase {

    private static final Account ALICE_ACCOUNT = ALICE.account();

    @Mock
    private Authorizer authorizer;

    @Mock
    private CryptoTransferHandler cryptoTransferHandler;

    @Mock
    private SolvencyPreCheck solvencyPreCheck;

    @Mock
    private ExpiryValidation expiryValidation;

    @Mock
    private FeeManager feeManager;

    @Mock
    private TransactionDispatcher dispatcher;

    @Mock
    private TransactionChecker transactionChecker;

    @Mock
    private Configuration configuration;

    private QueryChecker checker;

    @BeforeEach
    void setup() {
        checker = new QueryChecker(
                authorizer,
                cryptoTransferHandler,
                solvencyPreCheck,
                expiryValidation,
                feeManager,
                dispatcher,
                transactionChecker);
    }

    @AfterEach
    void tearDown() {
        if (state != null) {
            state.release();
        }
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testConstructorWithIllegalArguments() {
        assertThatThrownBy(() -> new QueryChecker(
                        null,
                        cryptoTransferHandler,
                        solvencyPreCheck,
                        expiryValidation,
                        feeManager,
                        dispatcher,
                        transactionChecker))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryChecker(
                        authorizer,
                        null,
                        solvencyPreCheck,
                        expiryValidation,
                        feeManager,
                        dispatcher,
                        transactionChecker))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryChecker(
                        authorizer,
                        cryptoTransferHandler,
                        null,
                        expiryValidation,
                        feeManager,
                        dispatcher,
                        transactionChecker))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryChecker(
                        authorizer,
                        cryptoTransferHandler,
                        solvencyPreCheck,
                        null,
                        feeManager,
                        dispatcher,
                        transactionChecker))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueryChecker(
                        authorizer,
                        cryptoTransferHandler,
                        solvencyPreCheck,
                        expiryValidation,
                        null,
                        dispatcher,
                        transactionChecker))
                .isInstanceOf(NullPointerException.class);
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testValidateCryptoTransferWithIllegalArguments() {
        assertThatThrownBy(() -> checker.validateCryptoTransfer(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testValidateCryptoTransferSucceeds() {
        // given
        final var txBody = TransactionBody.newBuilder()
                .transactionID(
                        TransactionID.newBuilder().accountID(AccountID.DEFAULT).build())
                .build();
        final var signatureMap = SignatureMap.newBuilder().build();
        final var transactionInfo = new TransactionInfo(
                SignedTransaction.DEFAULT, txBody, signatureMap, Bytes.EMPTY, CRYPTO_TRANSFER, null);

        // when
        assertThatCode(() -> checker.validateCryptoTransfer(transactionInfo)).doesNotThrowAnyException();
    }

    @Test
    void testValidateCryptoTransferWithWrongTransactionType() {
        // given
        final var txBody = TransactionBody.newBuilder()
                .transactionID(
                        TransactionID.newBuilder().accountID(AccountID.DEFAULT).build())
                .build();
        final var signatureMap = SignatureMap.newBuilder().build();
        final var transactionInfo = new TransactionInfo(
                SignedTransaction.DEFAULT, txBody, signatureMap, Bytes.EMPTY, CONSENSUS_CREATE_TOPIC, null);

        // then
        assertThatThrownBy(() -> checker.validateCryptoTransfer(transactionInfo))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INSUFFICIENT_TX_FEE));
    }

    @Test
    void testValidateCryptoTransferWithFailingValidation() throws PreCheckException {
        // given
        final var txBody = TransactionBody.newBuilder()
                .transactionID(
                        TransactionID.newBuilder().accountID(AccountID.DEFAULT).build())
                .build();
        final var signatureMap = SignatureMap.newBuilder().build();
        final var transactionInfo = new TransactionInfo(
                SignedTransaction.DEFAULT, txBody, signatureMap, Bytes.EMPTY, CRYPTO_TRANSFER, null);
        doThrow(new PreCheckException(INVALID_ACCOUNT_AMOUNTS))
                .when(cryptoTransferHandler)
                .pureChecks(any());

        // then
        assertThatThrownBy(() -> checker.validateCryptoTransfer(transactionInfo))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_ACCOUNT_AMOUNTS));
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testCheckPermissionWithIllegalArguments() {
        // given
        final var payer = AccountID.newBuilder().build();

        // then
        assertThatThrownBy(() -> checker.checkPermissions(null, GET_ACCOUNT_DETAILS))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> checker.checkPermissions(payer, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testCheckPermissionSucceeds() {
        // given
        final var payer = AccountID.newBuilder().build();
        when(authorizer.isAuthorized(payer, GET_ACCOUNT_DETAILS)).thenReturn(true);

        // then
        assertDoesNotThrow(() -> checker.checkPermissions(payer, GET_ACCOUNT_DETAILS));
    }

    @Test
    void testCheckPermissionFails() {
        // given
        final var payer = AccountID.newBuilder().build();
        when(authorizer.isAuthorized(payer, GET_ACCOUNT_DETAILS)).thenReturn(false);

        // then
        assertThatThrownBy(() -> checker.checkPermissions(payer, GET_ACCOUNT_DETAILS))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(NOT_SUPPORTED));
    }

    @Nested
    @DisplayName("Tests for checking account balances")
    class ValidateAccountBalanceTests {

        private ReadableAccountStore store;

        @Mock
        private ReadableEntityCounters entityCounters;

        @BeforeEach
        void setup() {
            setupStandardStates();

            final var storeFactory = new ReadableStoreFactory(state);
            store = storeFactory.getStore(ReadableAccountStore.class);
        }

        @SuppressWarnings("ConstantConditions")
        @Test
        void testIllegalArguments() {
            // given
            final var txInfo =
                    createPaymentInfo(ALICE.accountID(), send(ALICE.accountID(), 8), receive(nodeSelfAccountId, 8));

            // then
            assertThatThrownBy(() -> checker.validateAccountBalances(null, txInfo, ALICE_ACCOUNT, 8L, 0))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> checker.validateAccountBalances(store, null, ALICE_ACCOUNT, 8L, 0))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> checker.validateAccountBalances(store, txInfo, null, 8L, 0))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void testHappyPath() {
            // given
            final var amount = 8L;
            final var txInfo = createPaymentInfo(
                    ALICE.accountID(), send(ALICE.accountID(), amount), receive(nodeSelfAccountId, amount));

            // then
            assertThatCode(() -> checker.validateAccountBalances(store, txInfo, ALICE_ACCOUNT, amount, 0))
                    .doesNotThrowAnyException();
        }

        @Test
        void testSolvencyCheckFails() throws PreCheckException {
            // given
            final var amount = 8L;
            final var txInfo = createPaymentInfo(
                    ALICE.accountID(), send(ALICE.accountID(), amount), receive(nodeSelfAccountId, amount));
            doThrow(new InsufficientBalanceException(INSUFFICIENT_ACCOUNT_BALANCE, amount))
                    .when(solvencyPreCheck)
                    .checkSolvency(txInfo, ALICE_ACCOUNT, new Fees(amount, 0, 0), NOT_INGEST);

            // then
            assertThatThrownBy(() -> checker.validateAccountBalances(store, txInfo, ALICE_ACCOUNT, 0, amount))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .has(responseCode(INSUFFICIENT_ACCOUNT_BALANCE))
                    .has(estimatedFee(amount));
        }

        @Test
        void testEmptyTransferListFails() {
            // given
            final var txInfo = createPaymentInfo(ALICE.accountID());

            // then
            assertThatThrownBy(() -> checker.validateAccountBalances(store, txInfo, ALICE_ACCOUNT, 8L, 0))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_ACCOUNT_AMOUNTS));
        }

        @Test
        void testOtherPayerSucceeds() {
            // given
            final long amount = 5000L;
            final var txInfo = createPaymentInfo(
                    ALICE.accountID(), send(ERIN.accountID(), amount), receive(nodeSelfAccountId, amount));
            accountsState.put(
                    ERIN.accountID(),
                    ERIN.account().copyBuilder().tinybarBalance(amount).build());

            // then
            assertThatCode(() -> checker.validateAccountBalances(store, txInfo, ALICE_ACCOUNT, amount, 0))
                    .doesNotThrowAnyException();
        }

        @Test
        void testOtherPayerFailsWithInsufficientBalance() {
            // given
            final long amount = 5000L;
            final var txInfo = createPaymentInfo(
                    ALICE.accountID(), send(ERIN.accountID(), amount), receive(nodeSelfAccountId, amount));
            accountsState.put(
                    ERIN.accountID(),
                    ERIN.account().copyBuilder().tinybarBalance(amount - 1).build());

            // then
            assertThatThrownBy(() -> checker.validateAccountBalances(store, txInfo, ALICE_ACCOUNT, 0, amount))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .has(responseCode(INSUFFICIENT_PAYER_BALANCE))
                    .has(estimatedFee(amount));
        }

        @Test
        void testOtherPayerFailsIfNotFound() {
            // given
            final long amount = 5000L;
            final var txInfo = createPaymentInfo(
                    ALICE.accountID(), send(BOB.accountID(), amount), receive(nodeSelfAccountId, amount));

            // then
            assertThatThrownBy(() -> checker.validateAccountBalances(store, txInfo, ALICE_ACCOUNT, amount, 0))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(ACCOUNT_ID_DOES_NOT_EXIST));
        }

        @Test
        void testMultiplePayersSucceeds() {
            // given
            final long amount = 4000L;
            final var txInfo = createPaymentInfo(
                    ALICE.accountID(),
                    send(ALICE.accountID(), amount / 2),
                    send(BOB.accountID(), amount / 4),
                    send(ERIN.accountID(), amount / 4),
                    receive(nodeSelfAccountId, amount));
            accountsState.put(
                    BOB.accountID(),
                    BOB.account().copyBuilder().tinybarBalance(amount / 4).build());
            accountsState.put(
                    ERIN.accountID(),
                    ERIN.account().copyBuilder().tinybarBalance(amount / 4).build());

            // then
            assertThatCode(() -> checker.validateAccountBalances(store, txInfo, ALICE_ACCOUNT, amount, 0))
                    .doesNotThrowAnyException();
        }

        @Test
        void testMultiplePayersFailsWithInsufficientBalance() {
            // given
            final long amount = 4000L;
            final var txInfo = createPaymentInfo(
                    ALICE.accountID(),
                    send(ALICE.accountID(), amount / 2),
                    send(BOB.accountID(), amount / 4),
                    send(ERIN.accountID(), amount / 4),
                    receive(nodeSelfAccountId, amount));
            accountsState.put(
                    BOB.accountID(),
                    BOB.account().copyBuilder().tinybarBalance(amount / 4 - 1).build());
            accountsState.put(
                    ERIN.accountID(),
                    ERIN.account().copyBuilder().tinybarBalance(amount / 4).build());

            // then
            assertThatThrownBy(() -> checker.validateAccountBalances(store, txInfo, ALICE_ACCOUNT, 0, amount))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .has(responseCode(INSUFFICIENT_PAYER_BALANCE))
                    .has(estimatedFee(amount));
        }

        @Test
        void testMultiplePayersFailsIfOneNotFound() {
            // given
            final long amount = 4000L;
            final var txInfo = createPaymentInfo(
                    ALICE.accountID(),
                    send(ALICE.accountID(), amount / 2),
                    send(BOB.accountID(), amount / 4),
                    send(ERIN.accountID(), amount / 4),
                    receive(nodeSelfAccountId, amount));
            accountsState.put(
                    ERIN.accountID(),
                    ERIN.account().copyBuilder().tinybarBalance(amount / 4).build());

            // then
            assertThatThrownBy(() -> checker.validateAccountBalances(store, txInfo, ALICE_ACCOUNT, amount, 0))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(ACCOUNT_ID_DOES_NOT_EXIST));
        }

        @Test
        void testWrongRecipientFails() {
            // given
            final var amount = 8L;
            final var txInfo = createPaymentInfo(
                    ALICE.accountID(), send(ALICE.accountID(), amount), receive(NODE_1.nodeAccountID(), amount));
            accountsState.put(NODE_1.nodeAccountID(), NODE_1.account());

            // then
            assertThatThrownBy(() -> checker.validateAccountBalances(store, txInfo, ALICE_ACCOUNT, amount, 0))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_RECEIVING_NODE_ACCOUNT));
        }

        @Test
        void testInsufficientNodePaymentFails() {
            // given
            final var amount = 4000L;
            final var txInfo = createPaymentInfo(
                    ALICE.accountID(),
                    send(ALICE.accountID(), amount),
                    receive(ERIN.accountID(), amount / 2),
                    receive(nodeSelfAccountId, amount / 2));

            // then
            assertThatThrownBy(() -> checker.validateAccountBalances(store, txInfo, ALICE_ACCOUNT, amount, 0))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .has(responseCode(INSUFFICIENT_TX_FEE))
                    .has(estimatedFee(amount));
        }

        @Test
        void testPayerWithMinValueFails() {
            // given
            final var amount = 4000L;
            final var txInfo = createPaymentInfo(
                    ALICE.accountID(),
                    AccountAmount.newBuilder()
                            .accountID(ALICE.accountID())
                            .amount(Long.MIN_VALUE)
                            .build(),
                    receive(nodeSelfAccountId, amount));

            // then
            assertThatThrownBy(() -> checker.validateAccountBalances(store, txInfo, ALICE_ACCOUNT, amount, 0))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_ACCOUNT_AMOUNTS));
        }

        @Test
        void testOtherPayerWithMinValueFails() {
            // given
            final var amount = 4000L;
            final var txInfo = createPaymentInfo(
                    ALICE.accountID(),
                    AccountAmount.newBuilder()
                            .accountID(ERIN.accountID())
                            .amount(Long.MIN_VALUE)
                            .build(),
                    receive(nodeSelfAccountId, amount));

            // then
            assertThatThrownBy(() -> checker.validateAccountBalances(store, txInfo, ALICE_ACCOUNT, amount, 0))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_ACCOUNT_AMOUNTS));
        }
    }

    @Test
    void testEstimateTxFees(@Mock final ReadableStoreFactory storeFactory) {
        // given
        final var consensusNow = Instant.ofEpochSecond(0);
        final var txInfo = createPaymentInfo(ALICE.accountID());
        final var configuration = HederaTestConfigBuilder.createConfig();
        final var fees = new Fees(1L, 20L, 300L);
        when(cryptoTransferHandler.calculateFees(any())).thenReturn(fees);

        // when
        final var result = checker.estimateTxFees(
                storeFactory, consensusNow, txInfo, ALICE.account().key(), configuration);

        // then
        assertThat(result).isEqualTo(fees.totalFee());
    }

    private TransactionInfo createPaymentInfo(final AccountID payerID, final AccountAmount... transfers) {
        final var transactionID = TransactionID.newBuilder().accountID(payerID).build();
        final var transferList =
                TransferList.newBuilder().accountAmounts(transfers).build();
        final var txBody = TransactionBody.newBuilder()
                .transactionID(transactionID)
                .nodeAccountID(nodeSelfAccountId)
                .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                        .transfers(transferList)
                        .build())
                .build();
        final var bodyBytes = TransactionBody.PROTOBUF.toBytes(txBody);
        final var signedTransaction = SignedTransaction.newBuilder()
                .bodyBytes(bodyBytes)
                .sigMap(SignatureMap.DEFAULT)
                .build();
        return new TransactionInfo(
                signedTransaction, txBody, SignatureMap.DEFAULT, signedTransaction.bodyBytes(), CRYPTO_TRANSFER, null);
    }

    private static AccountAmount send(AccountID accountID, long amount) {
        return AccountAmount.newBuilder().accountID(accountID).amount(-amount).build();
    }

    private static AccountAmount receive(AccountID accountID, long amount) {
        return AccountAmount.newBuilder().accountID(accountID).amount(amount).build();
    }
}
