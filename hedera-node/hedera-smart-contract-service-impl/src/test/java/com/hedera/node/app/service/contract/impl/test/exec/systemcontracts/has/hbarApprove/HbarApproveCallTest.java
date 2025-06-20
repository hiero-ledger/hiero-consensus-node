// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.hbarApprove;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ALLOWANCE_OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ordinalRevertOutputFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarapprove.HbarApproveCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarapprove.HbarApproveTranslator;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class HbarApproveCallTest extends CallTestBase {
    private HbarApproveCall subject;

    @Mock
    private HasCallAttempt attempt;

    @Mock
    private TransactionBody transactionBody;

    @Mock
    private ContractCallStreamBuilder recordBuilder;

    @Test
    void revertsWithNoOwner() {
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(systemContractOperations.dispatch(any(), any(), any(), any())).willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(ResponseCodeEnum.INVALID_ALLOWANCE_OWNER_ID);
        subject = new HbarApproveCall(attempt, transactionBody);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(State.REVERT, result.getState());
        assertEquals(ordinalRevertOutputFor(INVALID_ALLOWANCE_OWNER_ID), result.getOutput());
    }

    @Test
    void getHbarApprove() {
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(systemContractOperations.dispatch(any(), any(), any(), any())).willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(ResponseCodeEnum.SUCCESS);

        subject = new HbarApproveCall(attempt, transactionBody);

        final var result = subject.execute(frame).fullResult().result();
        assertEquals(State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(HbarApproveTranslator.HBAR_APPROVE
                        .getOutputs()
                        .encode(Tuple.singleton((long) SUCCESS.getNumber()))
                        .array()),
                result.getOutput());
    }

    @Test
    void getHbarApproveRevert() {
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(systemContractOperations.dispatch(any(), any(), any(), any())).willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(ResponseCodeEnum.REVERTED_SUCCESS);

        subject = new HbarApproveCall(attempt, transactionBody);

        final var result = subject.execute(frame).fullResult().result();
        assertEquals(State.REVERT, result.getState());
        assertEquals(
                Bytes.wrap(HbarApproveTranslator.HBAR_APPROVE
                        .getOutputs()
                        .encode(Tuple.singleton((long) REVERTED_SUCCESS.getNumber()))
                        .array()),
                result.getOutput());
    }
}
