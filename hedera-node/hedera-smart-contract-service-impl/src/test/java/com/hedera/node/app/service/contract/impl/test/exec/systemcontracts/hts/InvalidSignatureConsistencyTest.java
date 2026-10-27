// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** GHSA-jfxq-8944-q786: a dispatched INVALID_SIGNATURE must surface as 7, not be remapped to 326. */
class InvalidSignatureConsistencyTest extends CallTestBase {
    @Test
    void convenienceConstructorDoesNotRemapInvalidSignature() {
        final var attempt = Mockito.mock(HtsCallAttempt.class);
        final var addressIdConverter = Mockito.mock(AddressIdConverter.class);
        final var verificationStrategy = Mockito.mock(VerificationStrategy.class);
        final var dispatchGasCalculator = Mockito.mock(DispatchGasCalculator.class);
        final var recordBuilder = Mockito.mock(ContractCallStreamBuilder.class);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(addressIdConverter.convertSender(any())).willReturn(AccountID.DEFAULT);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        final var subject = new DispatchForResponseCodeHtsCall(attempt, TransactionBody.DEFAULT, dispatchGasCalculator);
        given(systemContractOperations.dispatch(
                        TransactionBody.DEFAULT,
                        verificationStrategy,
                        AccountID.DEFAULT,
                        ContractCallStreamBuilder.class))
                .willReturn(recordBuilder);
        given(dispatchGasCalculator.gasRequirement(
                        TransactionBody.DEFAULT, gasCalculator, mockEnhancement(), AccountID.DEFAULT))
                .willReturn(123L);
        given(recordBuilder.status()).willReturn(INVALID_SIGNATURE);

        subject.execute(frame);

        // recordBuilder is a mock, so its status() getter keeps returning the stub no matter what the
        // failure customizer writes; the output bytes alone cannot detect the remap. Assert on the write.
        verify(recordBuilder).status(INVALID_SIGNATURE);
        verify(recordBuilder, never()).status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE);
    }
}
