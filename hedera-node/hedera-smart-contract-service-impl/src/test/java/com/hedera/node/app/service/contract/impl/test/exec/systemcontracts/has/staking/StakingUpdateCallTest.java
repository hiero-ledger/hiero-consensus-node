// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.staking;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_STAKING_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.StakingTranslator.STAKE_TO_NODE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.ACCOUNT_SERVICE_STAKING_UPDATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.verify;
import static org.mockito.Mockito.never;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.StakingUpdateCall;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StakingUpdateCallTest extends CallTestBase {

    private static final TransactionBody BODY = TransactionBody.newBuilder()
            .cryptoUpdateAccount(
                    CryptoUpdateTransactionBody.newBuilder().stakedNodeId(3L).build())
            .build();

    @Mock
    private HasCallAttempt attempt;

    @Mock
    private ContractCallStreamBuilder recordBuilder;

    @Mock
    private VerificationStrategy verificationStrategy;

    @BeforeEach
    void setUp() {
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.enhancement()).willReturn(mockEnhancement());
    }

    @Test
    void marksTheDispatchAsAnAccountServiceStakingUpdate() {
        givenDispatchReturning(SUCCESS);

        new StakingUpdateCall(attempt, BODY).execute(frame);

        // Without this marker CryptoUpdateHandler rejects a contract account, which is the whole point
        final var captor = ArgumentCaptor.forClass(DispatchMetadata.class);
        verify(systemContractOperations).dispatch(any(), any(), any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue().getMetadata(ACCOUNT_SERVICE_STAKING_UPDATE, Boolean.class))
                .contains(Boolean.TRUE);
    }

    @Test
    void pricesTheDispatchAsACryptoUpdate() {
        givenDispatchReturning(SUCCESS);
        given(gasCalculator.gasRequirement(BODY, DispatchType.CRYPTO_UPDATE, SENDER_ID))
                .willReturn(1234L);

        final var result = new StakingUpdateCall(attempt, BODY).execute(frame);

        assertThat(result.fullResult().gasRequirement()).isEqualTo(1234L);
    }

    @Test
    void returnsTheResponseCodeOnSuccess() {
        givenDispatchReturning(SUCCESS);

        final var result =
                new StakingUpdateCall(attempt, BODY).execute(frame).fullResult().result();

        assertThat(result.state()).isEqualTo(State.COMPLETED_SUCCESS);
        assertThat(result.output()).isEqualTo(encodedRc(SUCCESS));
    }

    @Test
    void returnsAFailureRatherThanReverting() {
        givenDispatchReturning(INVALID_STAKING_ID);

        final var result =
                new StakingUpdateCall(attempt, BODY).execute(frame).fullResult().result();

        // HAS convention and the HIP both say a business failure is returned, not reverted
        assertThat(result.state()).isEqualTo(State.COMPLETED_SUCCESS);
        assertThat(result.output()).isEqualTo(encodedRc(INVALID_STAKING_ID));
    }

    @Test
    void reportsASignatureFailureTheWayEveryOtherSystemContractCallDoes() {
        givenDispatchReturning(INVALID_SIGNATURE);

        final var result =
                new StakingUpdateCall(attempt, BODY).execute(frame).fullResult().result();

        // ReturnTypes#standardized remaps INVALID_SIGNATURE (7) for every system contract call, so an
        // unauthorized cross-account call surfaces as 326 in the EVM, not 7 -- which is what HIP-1522's
        // Authorization section and response-code table specify.
        assertThat(result.state()).isEqualTo(State.COMPLETED_SUCCESS);
        assertThat(result.output()).isEqualTo(encodedRc(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE));
    }

    @Test
    void preDispatchFailureReturnsItsCodeAndDispatchesNothing() {
        given(gasCalculator.canonicalGasRequirement(DispatchType.CRYPTO_UPDATE)).willReturn(100L);

        final var result = new StakingUpdateCall(attempt, INVALID_STAKING_ID)
                .execute(frame)
                .fullResult();

        assertThat(result.result().state()).isEqualTo(State.COMPLETED_SUCCESS);
        assertThat(result.result().output()).isEqualTo(encodedRc(INVALID_STAKING_ID));
        assertThat(result.gasRequirement()).isEqualTo(100L);
        verify(systemContractOperations, never()).dispatch(any(), any(), any(), any(), any(), any(), any());
    }

    private void givenDispatchReturning(final ResponseCodeEnum status) {
        given(attempt.senderId()).willReturn(SENDER_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(systemContractOperations.dispatch(any(), any(), any(), any(), any(), any(), any()))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(status);
    }

    private static Bytes encodedRc(final ResponseCodeEnum status) {
        return Bytes.wrap(STAKE_TO_NODE
                .getOutputs()
                .encode(Tuple.singleton((long) status.protoOrdinal()))
                .array());
    }
}
