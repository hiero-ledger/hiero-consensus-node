// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.estimatedFee;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.WorkflowCheck.INGEST;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.WorkflowCheck.NOT_INGEST;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.authorization.SystemPrivilege;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.InsufficientBalanceException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.validation.ExpiryValidation;
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
class SolvencyPreCheckTest extends AppTestBase {

    private static final Fees FEE = new Fees(1000L, 0, 0);

    private static final Instant START = Instant.parse("2020-02-02T20:20:02.02Z");

    @Mock
    private ExchangeRateManager exchangeRateManager;

    @Mock
    private FeeManager feeManager;

    @Mock
    private ExpiryValidation expiryValidation;

    @Mock(strictness = LENIENT)
    private Authorizer authorizer;

    private SolvencyPreCheck subject;

    @BeforeEach
    void setup() {
        when(authorizer.hasPrivilegedAuthorization(any(), any(), any())).thenReturn(SystemPrivilege.UNNECESSARY);

        subject = new SolvencyPreCheck(exchangeRateManager, feeManager, expiryValidation, authorizer);
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testConstructorWithIllegalParameters() {
        assertThatThrownBy(() -> new SolvencyPreCheck(null, feeManager, expiryValidation, authorizer))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SolvencyPreCheck(exchangeRateManager, null, expiryValidation, authorizer))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SolvencyPreCheck(exchangeRateManager, feeManager, null, authorizer))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SolvencyPreCheck(exchangeRateManager, feeManager, expiryValidation, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Nested
    @DisplayName("Tests related to getPayerAccount()")
    final class GetPayerAccountTests {

        private ReadableStoreFactory storeFactory;

        @BeforeEach
        void setup() {
            setupStandardStates();
            storeFactory = new ReadableStoreFactory(state);
        }

        @SuppressWarnings("ConstantConditions")
        @Test
        void testGetPayerWithIllegalParameters() {
            // given
            final var payerID = ALICE.accountID();

            // then
            assertThatThrownBy(() -> subject.getPayerAccount(null, payerID)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> subject.getPayerAccount(storeFactory, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void testGetPayerAccountSuccess() {
            assertThatCode(() -> subject.getPayerAccount(storeFactory, ALICE.accountID()))
                    .doesNotThrowAnyException();
        }

        @Test
        void testGetUnknownPayerAccountFails() {
            assertThatThrownBy(() -> subject.getPayerAccount(storeFactory, BOB.accountID()))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(ResponseCodeEnum.PAYER_ACCOUNT_NOT_FOUND));
        }

        @Test
        void testGetDeletedPayerAccountFails() {
            // given
            accountsState.put(
                    ALICE.accountID(),
                    ALICE.account().copyBuilder().deleted(true).build());

            // then
            assertThatThrownBy(() -> subject.getPayerAccount(storeFactory, ALICE.accountID()))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(ResponseCodeEnum.PAYER_ACCOUNT_DELETED));
        }

        @Test
        void testGetSmartContractPayerAccountFails() {
            // given
            accountsState.put(
                    ALICE.accountID(),
                    ALICE.account().copyBuilder().smartContract(true).build());

            // then
            assertThatThrownBy(() -> subject.getPayerAccount(storeFactory, ALICE.accountID()))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(ResponseCodeEnum.PAYER_ACCOUNT_NOT_FOUND));
        }

        @AfterEach
        void tearDown() {
            state.release();
        }
    }

    @Nested
    @DisplayName("Tests related to checkSolvency()")
    final class CheckSolvencyTests {

        @SuppressWarnings("ConstantConditions")
        @Test
        void testCheckSolvencyWithIllegalParameters() {
            // given
            final var txInfo = createTransactionInfo(FEE.totalFee(), START, CONSENSUS_CREATE_TOPIC, null);
            final var payer =
                    ALICE.account().copyBuilder().tinybarBalance(FEE.totalFee()).build();

            // then
            assertThatThrownBy(() -> subject.checkSolvency(null, payer, FEE, NOT_INGEST))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> subject.checkSolvency(txInfo, null, FEE, NOT_INGEST))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void testSimpleHappyPath() {
            // given
            final var txInfo = createTransactionInfo(FEE.totalFee(), START, CONSENSUS_CREATE_TOPIC, null);
            final var payer =
                    ALICE.account().copyBuilder().tinybarBalance(FEE.totalFee()).build();

            // then
            assertThatCode(() -> subject.checkSolvency(txInfo, payer, FEE, NOT_INGEST))
                    .doesNotThrowAnyException();
        }

        @Test
        void testInsufficientTransactionFeeFails() {
            // given
            final var txInfo = createTransactionInfo(FEE.totalFee() - 1, START, CONSENSUS_CREATE_TOPIC, null);
            final var payer =
                    ALICE.account().copyBuilder().tinybarBalance(FEE.totalFee()).build();

            // then
            assertThatThrownBy(() -> subject.checkSolvency(txInfo, payer, FEE, NOT_INGEST))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .has(responseCode(ResponseCodeEnum.INSUFFICIENT_TX_FEE))
                    .has(estimatedFee(FEE.totalFee()));
        }

        @Test
        void testPrivilegedTransactionSucceeds() {
            // given
            final var txInfo = createTransactionInfo(FEE.totalFee() - 1, START, CONSENSUS_CREATE_TOPIC, null);
            final var payer = ALICE.account()
                    .copyBuilder()
                    .tinybarBalance(FEE.totalFee() / 2)
                    .build();
            when(authorizer.hasWaivedFees(ALICE.accountID(), CONSENSUS_CREATE_TOPIC, txInfo.txBody()))
                    .thenReturn(true);

            // then
            assertThatCode(() -> subject.checkSolvency(txInfo, payer, FEE, NOT_INGEST))
                    .doesNotThrowAnyException();
        }

        @Test
        void testInsufficientPayerBalanceFails() {
            // given
            final var txInfo = createTransactionInfo(FEE.totalFee(), START, CONSENSUS_CREATE_TOPIC, null);
            final var payer = ALICE.account()
                    .copyBuilder()
                    .tinybarBalance(FEE.totalFee() - 1)
                    .build();

            // then
            assertThatThrownBy(() -> subject.checkSolvency(txInfo, payer, FEE, NOT_INGEST))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .has(responseCode(ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE))
                    .has(estimatedFee(FEE.totalFee()));
        }

        @Test
        void testInsufficientBalanceOfExpiredAccountFails() throws PreCheckException {
            // given
            final var txInfo = createTransactionInfo(
                    0,
                    START,
                    CRYPTO_CREATE,
                    TransactionBody.newBuilder()
                            .cryptoCreateAccount(
                                    CryptoCreateTransactionBody.newBuilder().initialBalance(123L)));
            final var payer = ALICE.account().copyBuilder().tinybarBalance(0).build();
            doThrow(new PreCheckException(ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL))
                    .when(expiryValidation)
                    .checkAccountExpiry(payer);
            // then
            assertThatThrownBy(() -> subject.checkSolvency(txInfo, payer, new Fees(0, 0, 0), INGEST))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL));
        }
    }

    @Nested
    @DisplayName("Tests related to checkSolvency()")
    final class CheckSolvencyTestsForCryptoCreate {
        @Test
        void testCryptoCreateSucceeds() {
            // given
            final var builder = TransactionBody.newBuilder()
                    .cryptoCreateAccount(
                            CryptoCreateTransactionBody.newBuilder().initialBalance(1L));
            final var txInfo = createTransactionInfo(FEE.totalFee(), START, CRYPTO_CREATE, builder);
            final var payer = ALICE.account()
                    .copyBuilder()
                    .tinybarBalance(FEE.totalFee() + 1L)
                    .build();

            // then
            assertThatCode(() -> subject.checkSolvency(txInfo, payer, FEE, NOT_INGEST))
                    .doesNotThrowAnyException();
        }

        @Test
        void testCryptoCreateWithInsufficientBalanceFails() {
            // given
            final var builder = TransactionBody.newBuilder()
                    .cryptoCreateAccount(
                            CryptoCreateTransactionBody.newBuilder().initialBalance(1L));
            final var txInfo = createTransactionInfo(FEE.totalFee(), START, CRYPTO_CREATE, builder);
            final var payer =
                    ALICE.account().copyBuilder().tinybarBalance(FEE.totalFee()).build();

            // then
            assertThatThrownBy(() -> subject.checkSolvency(txInfo, payer, FEE, INGEST))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .has(responseCode(ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE))
                    .has(estimatedFee(FEE.totalFee()));
        }
    }

    @Nested
    @DisplayName("Tests related to checkSolvency()")
    final class CheckSolvencyTestsWithCryptoTransfer {

        @Test
        void testCryptoTransferSucceeds() {
            // given
            final var payer = ALICE.account()
                    .copyBuilder()
                    .tinybarBalance(FEE.totalFee() + 1)
                    .build();
            final var transferList = TransferList.newBuilder().accountAmounts(send(ALICE.accountID(), 1L));
            final var builder = TransactionBody.newBuilder()
                    .cryptoTransfer(CryptoTransferTransactionBody.newBuilder().transfers(transferList));
            final var txInfo = createTransactionInfo(FEE.totalFee(), START, CRYPTO_TRANSFER, builder);

            // then
            assertThatCode(() -> subject.checkSolvency(txInfo, payer, FEE, NOT_INGEST))
                    .doesNotThrowAnyException();
        }

        @Test
        void testCryptoTransferWithInsufficientBalanceFails() {
            // given
            final var payer =
                    ALICE.account().copyBuilder().tinybarBalance(FEE.totalFee()).build();
            final var transferList = TransferList.newBuilder().accountAmounts(send(ALICE.accountID(), 1L));
            final var builder = TransactionBody.newBuilder()
                    .cryptoTransfer(CryptoTransferTransactionBody.newBuilder().transfers(transferList));
            final var txInfo = createTransactionInfo(FEE.totalFee(), START, CRYPTO_TRANSFER, builder);

            // then
            assertThatThrownBy(() -> subject.checkSolvency(txInfo, payer, FEE, INGEST))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .has(responseCode(ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE))
                    .has(estimatedFee(FEE.totalFee()));
        }

        @Test
        void testCryptoTransferWithoutPayerEntrySucceeds() {
            // given
            final var payer =
                    ALICE.account().copyBuilder().tinybarBalance(FEE.totalFee()).build();
            final var transferList = TransferList.newBuilder().accountAmounts(send(BOB.accountID(), 1000L));
            final var builder = TransactionBody.newBuilder()
                    .cryptoTransfer(CryptoTransferTransactionBody.newBuilder().transfers(transferList));
            final var txInfo = createTransactionInfo(FEE.totalFee(), START, CRYPTO_TRANSFER, builder);

            // then
            assertThatCode(() -> subject.checkSolvency(txInfo, payer, FEE, NOT_INGEST))
                    .doesNotThrowAnyException();
        }

        @Test
        void testCryptoTransferWithMultipleEntriesSucceeds() {
            // given
            final var payer = ALICE.account()
                    .copyBuilder()
                    .tinybarBalance(FEE.totalFee() + 1L)
                    .build();
            final var transferList =
                    TransferList.newBuilder().accountAmounts(send(ALICE.accountID(), 1L), send(BOB.accountID(), 1000L));
            final var builder = TransactionBody.newBuilder()
                    .cryptoTransfer(CryptoTransferTransactionBody.newBuilder().transfers(transferList));
            final var txInfo = createTransactionInfo(FEE.totalFee(), START, CRYPTO_TRANSFER, builder);

            // then
            assertThatCode(() -> subject.checkSolvency(txInfo, payer, FEE, NOT_INGEST))
                    .doesNotThrowAnyException();
        }

        @Test
        void testCryptoTransferWithMultipleEntriesFails() {
            // given
            final var payer =
                    ALICE.account().copyBuilder().tinybarBalance(FEE.totalFee()).build();
            final var transferList =
                    TransferList.newBuilder().accountAmounts(send(ALICE.accountID(), 1L), send(BOB.accountID(), 1000L));
            final var builder = TransactionBody.newBuilder()
                    .cryptoTransfer(CryptoTransferTransactionBody.newBuilder().transfers(transferList));
            final var txInfo = createTransactionInfo(FEE.totalFee(), START, CRYPTO_TRANSFER, builder);

            // then
            assertThatThrownBy(() -> subject.checkSolvency(txInfo, payer, FEE, INGEST))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .has(responseCode(ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE))
                    .has(estimatedFee(FEE.totalFee()));
        }
    }

    @Nested
    @DisplayName("Tests related to checkSolvency()")
    final class CheckSolvencyTestsWithContractCreate {
        // TODO: Add tests for ContractCreate once the requirements are clear
    }

    @Nested
    @DisplayName("Tests related to checkSolvency()")
    final class CheckSolvencyTestsWithContractCall {
        // TODO: Add tests for ContractCall once the requirements are clear
    }

    @Nested
    @DisplayName("Tests related to checkSolvency()")
    final class CheckSolvencyTestsWithEthereumTransaction {
        @Test
        void testEthereumTransactionSucceeds() {
            // given
            final var builder = TransactionBody.newBuilder()
                    .ethereumTransaction(EthereumTransactionBody.newBuilder().maxGasAllowance(1L));
            final var txInfo = createTransactionInfo(FEE.totalFee(), START, ETHEREUM_TRANSACTION, builder);
            final var payer = ALICE.account()
                    .copyBuilder()
                    .tinybarBalance(FEE.totalFee() + 1L)
                    .build();

            // then
            assertThatCode(() -> subject.checkSolvency(txInfo, payer, FEE, NOT_INGEST))
                    .doesNotThrowAnyException();
        }

        @Test
        void testEthereumTransactionWithInsufficientBalanceFails() {
            // given
            final var builder = TransactionBody.newBuilder()
                    .ethereumTransaction(EthereumTransactionBody.newBuilder().maxGasAllowance(1L));
            final var txInfo = createTransactionInfo(FEE.totalFee(), START, ETHEREUM_TRANSACTION, builder);
            final var payer =
                    ALICE.account().copyBuilder().tinybarBalance(FEE.totalFee()).build();

            // then
            assertThatThrownBy(() -> subject.checkSolvency(txInfo, payer, FEE, INGEST))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .has(responseCode(ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE))
                    .has(estimatedFee(FEE.totalFee()));
        }
    }

    private TransactionInfo createTransactionInfo(
            final long transactionFee,
            final Instant start,
            final HederaFunctionality functionality,
            final TransactionBody.Builder preparedBuilder) {
        final var builder = preparedBuilder != null ? preparedBuilder : TransactionBody.newBuilder();
        final var transactionID = TransactionID.newBuilder()
                .accountID(ALICE.accountID())
                .transactionValidStart(HapiUtils.asTimestamp(start))
                .build();
        final var txBody = builder.transactionFee(transactionFee)
                .transactionID(transactionID)
                .build();
        final var bodyBytes = TransactionBody.PROTOBUF.toBytes(txBody);
        final var signedTransaction = SignedTransaction.newBuilder()
                .bodyBytes(bodyBytes)
                .sigMap(SignatureMap.DEFAULT)
                .build();
        final var signedTransactionBytes = SignedTransaction.PROTOBUF.toBytes(signedTransaction);
        final var transaction = Transaction.newBuilder()
                .signedTransactionBytes(signedTransactionBytes)
                .build();
        return new TransactionInfo(
                transaction, txBody, SignatureMap.DEFAULT, signedTransactionBytes, functionality, null);
    }

    private static AccountAmount send(AccountID accountID, long amount) {
        return AccountAmount.newBuilder().accountID(accountID).amount(-amount).build();
    }
}
