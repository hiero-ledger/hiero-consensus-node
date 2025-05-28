// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.hbarAllowance;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ALLOWANCE_OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OPERATOR;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.UNAUTHORIZED_SPENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.revertOutputFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.CanonicalDispatchPrices;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.TinybarValues;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import java.math.BigInteger;
import java.util.function.ToLongBiFunction;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class HbarAllowanceCallTest extends CallTestBase {

    private HbarAllowanceCall subject;

    @Mock
    private HasCallAttempt attempt;

    @Mock
    private TinybarValues tinybarValues;

    @Mock
    private CanonicalDispatchPrices dispatchPrices;

    @Mock
    private ToLongBiFunction<TransactionBody, AccountID> feeCalculator;

    @Test
    void revertsWithNoOwner() {
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        subject = new HbarAllowanceCall(attempt, APPROVED_ID, UNAUTHORIZED_SPENDER_ID);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.getState());
        assertEquals(revertOutputFor(INVALID_ALLOWANCE_OWNER_ID), result.getOutput());
    }

    @Test
    void callHbarAllowance() {
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(nativeOperations.getAccount(B_NEW_ACCOUNT_ID)).willReturn(OPERATOR);
        subject = new HbarAllowanceCall(attempt, B_NEW_ACCOUNT_ID, UNAUTHORIZED_SPENDER_ID);

        final var result = subject.execute(frame).fullResult().result();
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                Bytes.wrap(HbarAllowanceTranslator.HBAR_ALLOWANCE_PROXY
                        .getOutputs()
                        .encode(Tuple.of((long) SUCCESS.getNumber(), BigInteger.valueOf(0L)))
                        .array()),
                result.getOutput());
    }

    @Test
    void sameSuccessAndRevertGas() {
        given(attempt.systemContractGasCalculator())
                .willReturn(new SystemContractGasCalculator(tinybarValues, dispatchPrices, feeCalculator));
        given(dispatchPrices.canonicalPriceInTinycents(DispatchType.TOKEN_INFO)).willReturn(1_000_000L);
        given(tinybarValues.childTransactionTinycentGasPrice()).willReturn(852_000L);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(nativeOperations.getAccount(B_NEW_ACCOUNT_ID)).willReturn(OPERATOR);
        subject = new HbarAllowanceCall(attempt, B_NEW_ACCOUNT_ID, UNAUTHORIZED_SPENDER_ID);

        final var successResult = subject.execute(frame);
        assertEquals(ResponseCodeEnum.SUCCESS, successResult.responseCode());
        assertTrue(successResult.fullResult().gasRequirement() > 0);

        given(nativeOperations.getAccount(B_NEW_ACCOUNT_ID)).willReturn(null);
        final var revertResult = subject.execute(frame);
        assertEquals(ResponseCodeEnum.INVALID_ALLOWANCE_OWNER_ID, revertResult.responseCode());
        assertTrue(revertResult.fullResult().gasRequirement() > 0);
        assertEquals(
                successResult.fullResult().gasRequirement(),
                revertResult.fullResult().gasRequirement());
    }
}
