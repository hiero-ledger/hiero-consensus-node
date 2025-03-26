// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.grantapproval;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.UNAUTHORIZED_SPENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asBytesResult;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval.ClassicGrantApprovalCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval.GrantApprovalTranslator;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * Unit tests for {@link ClassicGrantApprovalCall}.
 */
public class ClassicGrantApprovalCallTest extends CallTestBase {

    private ClassicGrantApprovalCall subject;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private ContractCallStreamBuilder recordBuilder;

    @Mock
    private SystemContractGasCalculator systemContractGasCalculator;

    @Mock
    private Nft nft;

    @Mock
    private Token token;

    @Mock
    private Account account;

    @Mock
    private ProxyWorldUpdater updater;

    @Test
    void fungibleApprove() {
        subject = new ClassicGrantApprovalCall(
                systemContractGasCalculator,
                mockEnhancement(),
                verificationStrategy,
                OWNER_ID,
                FUNGIBLE_TOKEN_ID,
                UNAUTHORIZED_SPENDER_ID,
                100L,
                TokenType.FUNGIBLE_COMMON);
        given(systemContractOperations.dispatch(any(), any(), any(), any())).willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(ResponseCodeEnum.SUCCESS);
        given(frame.getWorldUpdater()).willReturn(updater);
        given(updater.entityIdFactory()).willReturn(entityIdFactory);
        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                asBytesResult(GrantApprovalTranslator.GRANT_APPROVAL
                        .getOutputs()
                        .encode(Tuple.of(ResponseCodeEnum.SUCCESS.protoOrdinal(), true))),
                result.getOutput());
    }

    @Test
    void nftApprove() {
        subject = new ClassicGrantApprovalCall(
                systemContractGasCalculator,
                mockEnhancement(),
                verificationStrategy,
                OWNER_ID,
                NON_FUNGIBLE_TOKEN_ID,
                UNAUTHORIZED_SPENDER_ID,
                100L,
                TokenType.NON_FUNGIBLE_UNIQUE);
        given(systemContractOperations.dispatch(any(), any(), any(), any())).willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(ResponseCodeEnum.SUCCESS);
        given(nativeOperations.getNft(NON_FUNGIBLE_TOKEN_ID, 100L)).willReturn(nft);
        given(nativeOperations.getToken(NON_FUNGIBLE_TOKEN_ID)).willReturn(token);
        given(frame.getWorldUpdater()).willReturn(updater);
        given(updater.entityIdFactory()).willReturn(entityIdFactory);
        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.getState());
        assertEquals(
                asBytesResult(
                        GrantApprovalTranslator.GRANT_APPROVAL_NFT.getOutputs().encode(Tuple.singleton((long)
                                ResponseCodeEnum.SUCCESS.protoOrdinal()))),
                result.getOutput());
    }
}
