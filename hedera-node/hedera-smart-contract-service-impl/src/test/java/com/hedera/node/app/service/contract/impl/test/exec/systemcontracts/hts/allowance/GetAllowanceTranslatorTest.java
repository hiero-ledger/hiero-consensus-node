// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.allowance;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.allowance.GetAllowanceTranslator.ERC_GET_ALLOWANCE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.allowance.GetAllowanceTranslator.GET_ALLOWANCE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V3;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_HEADLONG_ADDRESS;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.allowance.GetAllowanceCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.allowance.GetAllowanceTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * Unit tests for {@link GetAllowanceTranslator}.
 */
public class GetAllowanceTranslatorTest extends CallAttemptTestBase {

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private ContractMetrics contractMetrics;

    private GetAllowanceTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new GetAllowanceTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesGetAllowance() {
        attempt = createHtsCallAttempt(GET_ALLOWANCE, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesERCGetAllowance() {
        attempt = createHtsCallAttempt(ERC_GET_ALLOWANCE, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void failsOnInvalidSelector() {
        attempt = createHtsCallAttempt(TOKEN_UPDATE_INFO_FUNCTION_V3, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromErcGetApprovedTest() {
        final Bytes inputBytes = Bytes.wrapByteBuffer(GetAllowanceTranslator.ERC_GET_ALLOWANCE.encodeCall(
                Tuple.of(OWNER_HEADLONG_ADDRESS, APPROVED_HEADLONG_ADDRESS)));
        given(attempt.isSelector(ERC_GET_ALLOWANCE)).willReturn(true);
        given(attempt.inputBytes()).willReturn(inputBytes.toArray());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(GetAllowanceCall.class);
    }

    @Test
    void callFromGetAllowanceTest() {
        final Bytes inputBytes = Bytes.wrapByteBuffer(GET_ALLOWANCE.encodeCall(
                Tuple.of(FUNGIBLE_TOKEN_HEADLONG_ADDRESS, OWNER_HEADLONG_ADDRESS, APPROVED_HEADLONG_ADDRESS)));
        given(attempt.isSelector(ERC_GET_ALLOWANCE)).willReturn(false);
        given(attempt.inputBytes()).willReturn(inputBytes.toArray());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(GetAllowanceCall.class);
    }
}
