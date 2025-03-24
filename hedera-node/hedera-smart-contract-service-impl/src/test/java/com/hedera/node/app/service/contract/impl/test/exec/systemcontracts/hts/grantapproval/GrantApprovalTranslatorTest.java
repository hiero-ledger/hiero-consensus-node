// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.grantapproval;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval.GrantApprovalTranslator.ERC_GRANT_APPROVAL;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval.GrantApprovalTranslator.ERC_GRANT_APPROVAL_NFT;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval.GrantApprovalTranslator.GRANT_APPROVAL;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval.GrantApprovalTranslator.GRANT_APPROVAL_NFT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.UNAUTHORIZED_SPENDER_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.UNAUTHORIZED_SPENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval.ClassicGrantApprovalCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval.ERCGrantApprovalCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval.GrantApprovalDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval.GrantApprovalTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class GrantApprovalTranslatorTest extends CallAttemptTestBase {

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private ContractMetrics contractMetrics;

    private final GrantApprovalDecoder decoder = new GrantApprovalDecoder();
    private GrantApprovalTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new GrantApprovalTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void grantApprovalMatches() {
        attempt = createHtsCallAttempt(GRANT_APPROVAL, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void ERCGrantApprovalMatches() {
        given(nativeOperations.getToken(anyLong())).willReturn(FUNGIBLE_TOKEN);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        attempt = createHtsCallAttemptForRedirect(ERC_GRANT_APPROVAL, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void ERCGrantApprovalNFTMatches() {
        given(nativeOperations.getToken(anyLong())).willReturn(FUNGIBLE_TOKEN);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        attempt = createHtsCallAttemptForRedirect(ERC_GRANT_APPROVAL_NFT, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void grantApprovalNFTMatches() {
        attempt = createHtsCallAttempt(GRANT_APPROVAL_NFT, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void falseOnInvalidSelector() {
        attempt = createHtsCallAttempt(BURN_TOKEN_V2, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromHapiFungible() {
        final Tuple tuple = Tuple.of(
                FUNGIBLE_TOKEN_HEADLONG_ADDRESS, UNAUTHORIZED_SPENDER_HEADLONG_ADDRESS, BigInteger.valueOf(123L));
        final byte[] inputBytes = Bytes.wrapByteBuffer(GrantApprovalTranslator.GRANT_APPROVAL.encodeCall(tuple))
                .toArray();
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(addressIdConverter.convert(any())).willReturn(UNAUTHORIZED_SPENDER_ID);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.isSelector(GRANT_APPROVAL, GRANT_APPROVAL_NFT)).willReturn(true);
        given(attempt.isSelector(GRANT_APPROVAL)).willReturn(true);
        given(attempt.isSelector(ERC_GRANT_APPROVAL)).willReturn(false);
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertInstanceOf(ClassicGrantApprovalCall.class, call);
    }

    @Test
    void callFromHapiNonFungible() {
        final Tuple tuple = Tuple.of(
                NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, UNAUTHORIZED_SPENDER_HEADLONG_ADDRESS, BigInteger.valueOf(123L));
        final byte[] inputBytes = Bytes.wrapByteBuffer(GrantApprovalTranslator.GRANT_APPROVAL_NFT.encodeCall(tuple))
                .toArray();
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(addressIdConverter.convert(any())).willReturn(UNAUTHORIZED_SPENDER_ID);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.isSelector(GRANT_APPROVAL, GRANT_APPROVAL_NFT)).willReturn(true);
        given(attempt.isSelector(GRANT_APPROVAL)).willReturn(false);
        given(attempt.isSelector(ERC_GRANT_APPROVAL)).willReturn(false);
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertInstanceOf(ClassicGrantApprovalCall.class, call);
    }

    @Test
    void callFromERCFungible() {
        final Tuple tuple = Tuple.of(UNAUTHORIZED_SPENDER_HEADLONG_ADDRESS, BigInteger.valueOf(123L));
        final byte[] inputBytes =
                Bytes.wrapByteBuffer(ERC_GRANT_APPROVAL.encodeCall(tuple)).toArray();
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(addressIdConverter.convert(any())).willReturn(UNAUTHORIZED_SPENDER_ID);
        given(attempt.redirectTokenId()).willReturn(FUNGIBLE_TOKEN_ID);
        given(attempt.redirectTokenType()).willReturn(TokenType.FUNGIBLE_COMMON);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.isSelector(ERC_GRANT_APPROVAL)).willReturn(true);
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertInstanceOf(ERCGrantApprovalCall.class, call);
    }

    @Test
    void callFromERCNonFungible() {
        final Tuple tuple = Tuple.of(UNAUTHORIZED_SPENDER_HEADLONG_ADDRESS, BigInteger.valueOf(123L));
        final byte[] inputBytes = Bytes.wrapByteBuffer(GrantApprovalTranslator.ERC_GRANT_APPROVAL_NFT.encodeCall(tuple))
                .toArray();
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(addressIdConverter.convert(any())).willReturn(UNAUTHORIZED_SPENDER_ID);
        given(attempt.redirectTokenId()).willReturn(NON_FUNGIBLE_TOKEN_ID);
        given(attempt.redirectTokenType()).willReturn(TokenType.NON_FUNGIBLE_UNIQUE);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.isSelector(ERC_GRANT_APPROVAL)).willReturn(true);
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertInstanceOf(ERCGrantApprovalCall.class, call);
    }
}
