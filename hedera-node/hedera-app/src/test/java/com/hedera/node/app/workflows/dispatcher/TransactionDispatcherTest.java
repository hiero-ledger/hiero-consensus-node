// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.token.CryptoDeleteTransactionBody;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.service.token.impl.handlers.CryptoCreateHandler;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fees.SimpleFeeCalculator;
import com.hedera.node.config.data.FeesConfig;
import com.swirlds.config.api.Configuration;
import java.util.stream.Stream;
import org.hiero.hapi.fees.FeeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TransactionDispatcher} fee calculation with simple fees.
 */
@ExtendWith(MockitoExtension.class)
class TransactionDispatcherTest {

    @Mock
    private TransactionHandlers handlers;

    @Mock
    private FeeManager feeManager;

    @Mock
    private FeeContext feeContext;

    @Mock
    private Configuration configuration;

    @Mock
    private FeesConfig feesConfig;

    @Mock
    private SimpleFeeCalculator simpleFeeCalculator;

    @Mock
    private CryptoCreateHandler cryptoCreateHandler;

    private TransactionDispatcher subject;
    private ExchangeRate testExchangeRate;

    @BeforeEach
    void setUp() {
        subject = new TransactionDispatcher(handlers, feeManager);
        testExchangeRate = ExchangeRate.newBuilder().hbarEquiv(1).centEquiv(12).build();
    }

    @Nested
    @DisplayName("Simple Fees Tests")
    class SimpleFeesTests {

        /**
         * Provides test data for transaction types that use simple fees.
         * <p><b>To enable a new transaction type:</b> Add a new Arguments entry here with:
         * <ol>
         *   <li>Descriptive name (for test display)</li>
         *   <li>TransactionBody with the transaction type set</li>
         * </ol>
         */
        static Stream<Arguments> simpleFeesEnabledTransactions() {
            return Stream.of(
                    Arguments.of(
                            "CRYPTO_CREATE_ACCOUNT",
                            TransactionBody.newBuilder()
                                    .transactionID(TransactionID.newBuilder()
                                            .accountID(AccountID.newBuilder()
                                                    .accountNum(1001)
                                                    .build())
                                            .transactionValidStart(Timestamp.newBuilder()
                                                    .seconds(1234567L)
                                                    .build())
                                            .build())
                                    .cryptoCreateAccount(CryptoCreateTransactionBody.newBuilder()
                                            .build())
                                    .build()),
                    Arguments.of(
                            "CRYPTO_DELETE",
                            TransactionBody.newBuilder()
                                    .transactionID(TransactionID.newBuilder()
                                            .accountID(AccountID.newBuilder()
                                                    .accountNum(1001)
                                                    .build())
                                            .transactionValidStart(Timestamp.newBuilder()
                                                    .seconds(1234567L)
                                                    .build())
                                            .build())
                                    .cryptoDelete(CryptoDeleteTransactionBody.newBuilder()
                                            .deleteAccountID(AccountID.newBuilder()
                                                    .accountNum(1002)
                                                    .build())
                                            .build())
                                    .build()),
                    Arguments.of(
                            "CRYPTO_UPDATE_ACCOUNT",
                            TransactionBody.newBuilder()
                                    .transactionID(TransactionID.newBuilder()
                                            .accountID(AccountID.newBuilder()
                                                    .accountNum(1001)
                                                    .build())
                                            .transactionValidStart(Timestamp.newBuilder()
                                                    .seconds(1234567L)
                                                    .build())
                                            .build())
                                    .cryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder()
                                            .accountIDToUpdate(AccountID.newBuilder()
                                                    .accountNum(1003)
                                                    .build())
                                            .build())
                                    .build()),
                    Arguments.of(
                            "CRYPTO_TRANSFER",
                            TransactionBody.newBuilder()
                                    .transactionID(TransactionID.newBuilder()
                                            .accountID(AccountID.newBuilder()
                                                    .accountNum(1001)
                                                    .build())
                                            .transactionValidStart(Timestamp.newBuilder()
                                                    .seconds(1234567L)
                                                    .build())
                                            .build())
                                    .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                                            .build())
                                    .build()));
        }

        @ParameterizedTest(name = "{0} uses simple fees when enabled")
        @MethodSource("simpleFeesEnabledTransactions")
        @DisplayName("Transaction types use simple fees when enabled")
        void testTransactionUsesSimpleFees(String txTypeName, TransactionBody txBody) {
            // Given: Simple fees are enabled
            given(feeContext.configuration()).willReturn(configuration);
            given(configuration.getConfigData(FeesConfig.class)).willReturn(feesConfig);
            given(feesConfig.simpleFeesEnabled()).willReturn(true);

            // And: Transaction body is provided
            given(feeContext.body()).willReturn(txBody);
            given(feeContext.activeRate()).willReturn(testExchangeRate);

            // And: Simple fee calculator returns a fee result
            final var feeResult = new FeeResult(498500000L, 100000L, 2);
            given(feeManager.getSimpleFeeCalculator()).willReturn(simpleFeeCalculator);
            given(simpleFeeCalculator.calculateTxFee(eq(txBody), any())).willReturn(feeResult);

            // When
            final var result = subject.dispatchComputeFees(feeContext);

            // Then: Should use simple fee calculator
            verify(simpleFeeCalculator).calculateTxFee(eq(txBody), any());

            // Verify fees are converted from tinycents to tinybars (divide by 12)
            assertThat(result).isNotNull();
            assertThat(result.nodeFee()).isEqualTo(8333L); // 100000/12
            assertThat(result.networkFee()).isEqualTo(16666L); // 200000/12
            assertThat(result.serviceFee()).isEqualTo(41541666L); // 498500000/12
        }

        @Test
        @DisplayName("Simple fees not used when feature is disabled")
        void testSimpleFeesNotUsedWhenFeatureDisabled() {
            // Given: Simple fees are DISABLED
            given(feeContext.configuration()).willReturn(configuration);
            given(configuration.getConfigData(FeesConfig.class)).willReturn(feesConfig);
            given(feesConfig.simpleFeesEnabled()).willReturn(false);

            // And: Transaction is CRYPTO_CREATE_ACCOUNT (normally would use simple fees)
            final var txBody = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder()
                            .accountID(AccountID.newBuilder().accountNum(1001).build())
                            .transactionValidStart(
                                    Timestamp.newBuilder().seconds(1234567L).build())
                            .build())
                    .cryptoCreateAccount(
                            CryptoCreateTransactionBody.newBuilder().build())
                    .build();
            given(feeContext.body()).willReturn(txBody);

            // And: Handler returns fees
            given(handlers.cryptoCreateHandler()).willReturn(cryptoCreateHandler);
            final var handlerFees = new Fees(1000L, 2000L, 3000L);
            given(cryptoCreateHandler.calculateFees(feeContext)).willReturn(handlerFees);

            // When
            final var result = subject.dispatchComputeFees(feeContext);

            // Then: Should NOT use simple fee calculator, use handler instead
            verify(cryptoCreateHandler).calculateFees(feeContext);
            verify(feeManager, never()).getSimpleFeeCalculator();

            assertThat(result).isEqualTo(handlerFees);
        }
    }
}
