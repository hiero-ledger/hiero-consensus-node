// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.allowance;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.allowance.GetAllowanceTranslator.ERC_GET_ALLOWANCE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.allowance.GetAllowanceTranslator.GET_ALLOWANCE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V3;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHtsAttemptWithSelector;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.allowance.GetAllowanceCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.allowance.GetAllowanceTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link GetAllowanceTranslator}.
 */
@ExtendWith(MockitoExtension.class)
public class GetAllowanceTranslatorTest {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private SystemContractGasCalculator gasCalculator;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private HederaWorldUpdater.Enhancement enhancement;

    @Mock
    private VerificationStrategies verificationStrategies;

    @Mock
    private ContractMetrics contractMetrics;

    private GetAllowanceTranslator subject;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    @BeforeEach
    void setUp() {
        subject = new GetAllowanceTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesGetAllowance() {
        attempt = prepareHtsAttemptWithSelector(
                GET_ALLOWANCE,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesERCGetAllowance() {
        attempt = prepareHtsAttemptWithSelector(
                ERC_GET_ALLOWANCE,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
        ;
    }

    @Test
    void failsOnInvalidSelector() {
        attempt = prepareHtsAttemptWithSelector(
                TOKEN_UPDATE_INFO_FUNCTION_V3,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromErcGetApprovedTest() {
        final Bytes inputBytes = Bytes.wrapByteBuffer(GetAllowanceTranslator.ERC_GET_ALLOWANCE.encodeCall(
                Tuple.of(OWNER_HEADLONG_ADDRESS, APPROVED_HEADLONG_ADDRESS)));
        given(attempt.isSelector(ERC_GET_ALLOWANCE)).willReturn(true);
        given(attempt.inputBytes()).willReturn(inputBytes.toArray());
        given(attempt.enhancement()).willReturn(enhancement);
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
        given(attempt.enhancement()).willReturn(enhancement);
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(GetAllowanceCall.class);
    }
}
