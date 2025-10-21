// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SUCCESS_RESULT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.RootProxyWorldUpdater;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CallOutcomeTest {
    @Mock
    private RootProxyWorldUpdater updater;

    @Mock
    private ContractCallStreamBuilder contractCallRecordBuilder;

    @Mock
    private EntityIdFactory entityIdFactory;

    @Mock
    private HandleContext context;

    @Test
    void setsAbortCallResult() {
        final var abortedCall = new CallOutcome(
                ContractFunctionResult.DEFAULT,
                INSUFFICIENT_GAS,
                CALLED_CONTRACT_ID,
                null,
                null,
                null,
                null,
                EvmTransactionResult.DEFAULT,
                null,
                null,
                null);
        abortedCall.addCallDetailsTo(contractCallRecordBuilder, context);
        verify(contractCallRecordBuilder).contractCallResult(any());
    }

    @Test
    void recognizesCreatedIdWhenEvmAddressIsSet() {
        given(updater.getCreatedContractIds()).willReturn(List.of(CALLED_CONTRACT_ID));
        given(updater.entityIdFactory()).willReturn(entityIdFactory);
        final var outcome = new CallOutcome(
                SUCCESS_RESULT.asProtoResultOf(null, updater, null),
                SUCCESS,
                null,
                null,
                null,
                null,
                null,
                SUCCESS_RESULT.asEvmTxResultOf(null, null),
                SUCCESS_RESULT.signerNonce(),
                Bytes.EMPTY,
                null);
        assertEquals(CALLED_CONTRACT_ID, outcome.recipientIdIfCreated());
    }

    @Test
    void recognizesNoCreatedIdWhenEvmAddressNotSet() {
        given(updater.entityIdFactory()).willReturn(entityIdFactory);
        final var outcome = new CallOutcome(
                SUCCESS_RESULT.asProtoResultOf(null, updater, null),
                SUCCESS,
                null,
                null,
                null,
                null,
                null,
                SUCCESS_RESULT.asEvmTxResultOf(null, null),
                SUCCESS_RESULT.signerNonce(),
                null,
                null);
        assertNull(outcome.recipientIdIfCreated());
    }

    @Test
    void calledIdIsFromResult() {
        given(updater.entityIdFactory()).willReturn(entityIdFactory);
        final var outcome = new CallOutcome(
                SUCCESS_RESULT.asProtoResultOf(null, updater, null),
                INVALID_CONTRACT_ID,
                CALLED_CONTRACT_ID,
                null,
                null,
                null,
                null,
                SUCCESS_RESULT.asEvmTxResultOf(null, null),
                SUCCESS_RESULT.signerNonce(),
                null,
                null);
        assertEquals(CALLED_CONTRACT_ID, outcome.recipientId());
    }
}
