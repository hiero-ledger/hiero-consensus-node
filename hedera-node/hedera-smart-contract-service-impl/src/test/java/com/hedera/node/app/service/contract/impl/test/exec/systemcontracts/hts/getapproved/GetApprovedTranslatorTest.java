// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.getapproved;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.getapproved.GetApprovedTranslator.ERC_GET_APPROVED;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.getapproved.GetApprovedTranslator.HAPI_GET_APPROVED;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.*;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.fromHeadlongAddress;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.getapproved.GetApprovedCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.getapproved.GetApprovedTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * Unit tests for {@link GetApprovedTranslator}.
 */
public class GetApprovedTranslatorTest extends CallAttemptTestBase {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private Token token;

    @Mock
    private ContractMetrics contractMetrics;

    private GetApprovedTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new GetApprovedTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesErcGetApprovedTest() {
        given(nativeOperations.getToken(anyLong())).willReturn(FUNGIBLE_TOKEN);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        attempt = createHtsCallAttemptForRedirect(ERC_GET_APPROVED, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesHapiGetApprovedTest() {
        attempt = createHtsCallAttempt(HAPI_GET_APPROVED, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesFailsOnIncorrectSelectorTest() {
        attempt = createHtsCallAttempt(BURN_TOKEN_V2, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromErcGetApprovedTest() {
        Tuple tuple = Tuple.singleton(BigInteger.valueOf(123L));
        Bytes inputBytes = Bytes.wrapByteBuffer(GetApprovedTranslator.ERC_GET_APPROVED.encodeCall(tuple));
        given(attempt.isSelector(ERC_GET_APPROVED)).willReturn(true);
        given(attempt.input()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(GetApprovedCall.class);
    }

    @Test
    void callFromHapiGetApprovedTest() {
        Tuple tuple = Tuple.of(NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, BigInteger.valueOf(123L));
        Bytes inputBytes = Bytes.wrapByteBuffer(HAPI_GET_APPROVED.encodeCall(tuple));
        given(attempt.isSelector(ERC_GET_APPROVED)).willReturn(false);
        given(attempt.input()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.linkedToken(fromHeadlongAddress(NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS)))
                .willReturn(token);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(GetApprovedCall.class);
    }
}
