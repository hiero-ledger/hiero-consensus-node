// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.scope;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.QuerySystemContractOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import java.time.InstantSource;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuerySystemContractOperationsTest {
    @Mock
    private QueryContext context;

    @Mock
    private ExchangeRateInfo exchangeRateInfo;

    @Mock
    private VerificationStrategy verificationStrategy;

    private final InstantSource instantSource = InstantSource.system();

    private QuerySystemContractOperations subject;

    @BeforeEach
    void setUp() {
        subject = new QuerySystemContractOperations(context, instantSource);
    }

    @Test
    void exchangeRateTest() {
        final ExchangeRate exchangeRate = new ExchangeRate(1, 2, TimestampSeconds.DEFAULT);
        given(context.exchangeRateInfo()).willReturn(exchangeRateInfo);
        given(exchangeRateInfo.activeRate(any())).willReturn(exchangeRate);
        var result = subject.currentExchangeRate();
        assertThat(result).isEqualTo(exchangeRate);
    }

    @Test
    void dispatchingNotSupported() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> subject.dispatch(
                        TransactionBody.DEFAULT, verificationStrategy, AccountID.DEFAULT, StreamBuilder.class));
        assertThrows(
                UnsupportedOperationException.class,
                () -> subject.externalizePreemptedDispatch(TransactionBody.DEFAULT, ACCOUNT_DELETED, CRYPTO_TRANSFER));
    }

    @Test
    void sigTestsNotSupported() {
        assertThrows(
                UnsupportedOperationException.class, () -> subject.primitiveSignatureTestWith(verificationStrategy));
        assertThrows(UnsupportedOperationException.class, () -> subject.signatureTestWith(verificationStrategy));
    }

    @Test
    void externalizingResultsAreNoop() {
        assertDoesNotThrow(() -> subject.externalizeResult(
                ContractFunctionResult.DEFAULT, SUCCESS, SignedTransaction.DEFAULT, EvmTransactionResult.DEFAULT));
        assertSame(
                SignedTransaction.DEFAULT,
                subject.syntheticSignedTxForNativeCall(Bytes.EMPTY, ContractID.DEFAULT, true));
    }

    @Test
    void maybeEthSenderKeyIsNullTest() {
        assertNull(subject.maybeEthSenderKey());
    }
}
