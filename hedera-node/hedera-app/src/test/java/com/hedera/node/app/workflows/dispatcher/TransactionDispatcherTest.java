// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.token.CryptoDeleteAllowanceTransactionBody;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.impl.handlers.CryptoDeleteAllowanceHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoTransferHandler;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.hiero.hapi.fees.FeeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransactionDispatcherTest {

    @Mock
    private TransactionHandlers handlers;

    @Mock
    private CryptoTransferHandler cryptoTransferHandler;

    @Mock
    private CryptoDeleteAllowanceHandler cryptoDeleteAllowanceHandler;

    private TransactionDispatcher subject;

    @BeforeEach
    void setUp() {
        subject = new TransactionDispatcher(handlers);
    }

    @Test
    void dispatchComputeFeesUsesSimpleFeesWhenEnabled() {
        // given - simple fees enabled for CRYPTO_TRANSFER
        final var config = HederaTestConfigBuilder.create()
                .withValue("fees.simpleFeesEnabled", true)
                .getOrCreateConfig();

        final var cryptoTransferBody =
                CryptoTransferTransactionBody.newBuilder().build();
        final var txBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder())
                .cryptoTransfer(cryptoTransferBody)
                .build();

        final var feeContext = mock(FeeContext.class);
        final var rate = ExchangeRate.newBuilder().hbarEquiv(1).centEquiv(12).build();

        given(feeContext.body()).willReturn(txBody);
        given(feeContext.configuration()).willReturn(config);
        given(feeContext.activeRate()).willReturn(rate);
        given(handlers.cryptoTransferHandler()).willReturn(cryptoTransferHandler);

        final var feeResult = new FeeResult();
        feeResult.node = 100L;
        feeResult.network = 200L;
        feeResult.service = 300L;
        given(cryptoTransferHandler.calculateFeeResult(feeContext)).willReturn(feeResult);

        // when
        final var result = subject.dispatchComputeFees(feeContext);

        // then
        assertThat(result).isNotNull();
        verify(cryptoTransferHandler).calculateFeeResult(feeContext);
    }

    @Test
    void dispatchComputeFeesUsesTraditionalFeesWhenDisabled() {
        // given - simple fees disabled
        final var config = HederaTestConfigBuilder.create()
                .withValue("fees.simpleFeesEnabled", false)
                .getOrCreateConfig();

        final var cryptoTransferBody =
                CryptoTransferTransactionBody.newBuilder().build();
        final var txBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder())
                .cryptoTransfer(cryptoTransferBody)
                .build();

        final var feeContext = mock(FeeContext.class);
        final var fees = Fees.FREE;

        given(feeContext.body()).willReturn(txBody);
        given(feeContext.configuration()).willReturn(config);
        given(handlers.cryptoTransferHandler()).willReturn(cryptoTransferHandler);
        given(cryptoTransferHandler.calculateFees(feeContext)).willReturn(fees);

        // when
        final var result = subject.dispatchComputeFees(feeContext);

        // then
        assertThat(result).isEqualTo(fees);
        verify(cryptoTransferHandler).calculateFees(feeContext);
    }

    @Test
    void shouldUseSimpleFeesForCryptoDeleteAllowance() {
        // given - simple fees enabled for CRYPTO_DELETE_ALLOWANCE
        final var config = HederaTestConfigBuilder.create()
                .withValue("fees.simpleFeesEnabled", true)
                .getOrCreateConfig();

        final var cryptoDeleteAllowanceBody =
                CryptoDeleteAllowanceTransactionBody.newBuilder().build();
        final var txBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder())
                .cryptoDeleteAllowance(cryptoDeleteAllowanceBody)
                .build();

        final var feeContext = mock(FeeContext.class);
        final var rate = ExchangeRate.newBuilder().hbarEquiv(1).centEquiv(12).build();

        given(feeContext.body()).willReturn(txBody);
        given(feeContext.configuration()).willReturn(config);
        given(feeContext.activeRate()).willReturn(rate);
        given(handlers.cryptoDeleteAllowanceHandler()).willReturn(cryptoDeleteAllowanceHandler);

        final var feeResult = new FeeResult();
        feeResult.node = 50L;
        feeResult.network = 100L;
        feeResult.service = 150L;
        given(cryptoDeleteAllowanceHandler.calculateFeeResult(feeContext)).willReturn(feeResult);

        // when
        final var result = subject.dispatchComputeFees(feeContext);

        // then
        assertThat(result).isNotNull();
        verify(cryptoDeleteAllowanceHandler).calculateFeeResult(feeContext);
    }

    @Test
    void dispatchComputeFeesWithSimpleFeesConversion() {
        // given - simple fees enabled, verify conversion happens correctly
        final var config = HederaTestConfigBuilder.create()
                .withValue("fees.simpleFeesEnabled", true)
                .getOrCreateConfig();

        final var cryptoTransferBody =
                CryptoTransferTransactionBody.newBuilder().build();
        final var txBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder())
                .cryptoTransfer(cryptoTransferBody)
                .build();

        final var feeContext = mock(FeeContext.class);
        final var rate = ExchangeRate.newBuilder()
                .hbarEquiv(1)
                .centEquiv(1) // 1:1 rate for easy verification
                .build();

        given(feeContext.body()).willReturn(txBody);
        given(feeContext.configuration()).willReturn(config);
        given(feeContext.activeRate()).willReturn(rate);
        given(handlers.cryptoTransferHandler()).willReturn(cryptoTransferHandler);

        final var feeResult = new FeeResult();
        feeResult.node = 10L;
        feeResult.network = 20L;
        feeResult.service = 30L;
        given(cryptoTransferHandler.calculateFeeResult(feeContext)).willReturn(feeResult);

        // when
        final var result = subject.dispatchComputeFees(feeContext);

        // then - with 1:1 rate, values should match
        assertThat(result).isNotNull();
        assertThat(result.nodeFee()).isEqualTo(10L);
        assertThat(result.networkFee()).isEqualTo(20L);
        assertThat(result.serviceFee()).isEqualTo(30L);
    }
}
