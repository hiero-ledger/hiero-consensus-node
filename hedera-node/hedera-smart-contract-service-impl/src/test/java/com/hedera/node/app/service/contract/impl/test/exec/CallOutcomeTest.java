// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SUCCESS_RESULT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SUCCESS_RESULT_WITH_SIGNER_NONCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.exec.delegation.CodeDelegationResult;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.records.ContractCreateStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.RootProxyWorldUpdater;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CallOutcomeTest {
    @Mock
    private RootProxyWorldUpdater updater;

    @Mock(answer = Answers.RETURNS_SELF)
    private ContractCallStreamBuilder contractCallRecordBuilder;

    @Mock(answer = Answers.RETURNS_SELF)
    private ContractCreateStreamBuilder contractCreateRecordBuilder;

    @Mock
    private EntityIdFactory entityIdFactory;

    @Mock
    private HandleContext context;

    @Mock
    private Configuration configuration;

    @Mock
    private BlockStreamConfig blockStreamConfig;

    @Mock
    private EthTxData ethTxData;

    private void givenStreamMode(final StreamMode mode) {
        given(context.configuration()).willReturn(configuration);
        given(configuration.getConfigData(BlockStreamConfig.class)).willReturn(blockStreamConfig);
        given(blockStreamConfig.streamMode()).willReturn(mode);
    }

    @Test
    void setsAbortCallResult() {
        givenStreamMode(StreamMode.BOTH);
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
                null,
                null);
        abortedCall.addCallDetailsTo(contractCallRecordBuilder, context, entityIdFactory);
        verify(contractCallRecordBuilder).contractCallResult(any());
    }

    @Test
    void skipsLegacyContractCallResultWhenStreamModeIsBlocks() {
        givenStreamMode(StreamMode.BLOCKS);
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
                null,
                CodeDelegationResult.EMPTY);
        abortedCall.addCallDetailsTo(contractCallRecordBuilder, context, entityIdFactory);
        verify(contractCallRecordBuilder, never()).contractCallResult(any(ContractFunctionResult.class));
    }

    @Test
    void setsLegacyContractCreateResultWhenStreamModeIsNotBlocks() {
        givenStreamMode(StreamMode.BOTH);
        final var createOutcome = new CallOutcome(
                ContractFunctionResult.DEFAULT,
                SUCCESS,
                CALLED_CONTRACT_ID,
                null,
                null,
                null,
                null,
                EvmTransactionResult.DEFAULT,
                null,
                null,
                null,
                CodeDelegationResult.EMPTY);
        createOutcome.addCreateDetailsTo(contractCreateRecordBuilder, context, entityIdFactory);
        verify(contractCreateRecordBuilder).contractCreateResult(any());
    }

    @Test
    void skipsLegacyContractCreateResultWhenStreamModeIsBlocks() {
        givenStreamMode(StreamMode.BLOCKS);
        final var createOutcome = new CallOutcome(
                ContractFunctionResult.DEFAULT,
                SUCCESS,
                CALLED_CONTRACT_ID,
                null,
                null,
                null,
                null,
                EvmTransactionResult.DEFAULT,
                null,
                null,
                null,
                CodeDelegationResult.EMPTY);
        createOutcome.addCreateDetailsTo(contractCreateRecordBuilder, context, entityIdFactory);
        verify(contractCreateRecordBuilder, never()).contractCreateResult(any());
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
                SUCCESS_RESULT.asEvmTxResultOf(null, updater, null, null),
                SUCCESS_RESULT.signerNonce(),
                Bytes.EMPTY,
                null,
                null);
        assertEquals(CALLED_CONTRACT_ID, outcome.recipientIdIfCreated());
    }

    @Test
    void usesSignerNonceWhenEthTxDataIsThere() {
        given(updater.getCreatedContractIds()).willReturn(List.of(CALLED_CONTRACT_ID));
        given(updater.entityIdFactory()).willReturn(entityIdFactory);
        final var outcome = new CallOutcome(
                SUCCESS_RESULT_WITH_SIGNER_NONCE.asProtoResultOf(null, updater, null),
                SUCCESS,
                null,
                null,
                null,
                null,
                null,
                SUCCESS_RESULT_WITH_SIGNER_NONCE.asEvmTxResultOf(ethTxData, updater, Bytes.EMPTY, null),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.signerNonce(),
                Bytes.EMPTY,
                null,
                null);
        assertEquals(
                SUCCESS_RESULT_WITH_SIGNER_NONCE.signerNonce(),
                outcome.txResult().signerNonce());
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
                SUCCESS_RESULT.asEvmTxResultOf(null, updater, null, null),
                SUCCESS_RESULT.signerNonce(),
                null,
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
                SUCCESS_RESULT.asEvmTxResultOf(null, updater, null, null),
                SUCCESS_RESULT.signerNonce(),
                null,
                null,
                null);
        assertEquals(CALLED_CONTRACT_ID, outcome.recipientId());
    }
}
