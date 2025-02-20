// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.hbarAllowance;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceTranslator.HBAR_ALLOWANCE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceTranslator.HBAR_ALLOWANCE_PROXY;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarapprove.HbarApproveTranslator.HBAR_APPROVE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.UNAUTHORIZED_SPENDER_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHasAttemptWithSelector;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class HbarAllowanceTranslatorTest {
    @Mock
    private HasCallAttempt attempt;

    @Mock
    private SystemContractGasCalculator gasCalculator;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private HederaWorldUpdater.Enhancement enhancement;

    @Mock
    private VerificationStrategies verificationStrategies;

    @Mock
    private SignatureVerifier signatureVerifier;

    @Mock
    private HederaNativeOperations nativeOperations;

    @Mock
    private ContractMetrics contractMetrics;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private HbarAllowanceTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new HbarAllowanceTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesHbarAllowance() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        attempt = prepareHasAttemptWithSelector(
                HBAR_ALLOWANCE,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();

        attempt = prepareHasAttemptWithSelector(
                HBAR_ALLOWANCE_PROXY,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void failsOnInvalidSelector() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        attempt = prepareHasAttemptWithSelector(
                HBAR_APPROVE,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromHbarAllowanceTest() {
        final Bytes inputBytes = Bytes.wrapByteBuffer(
                HBAR_ALLOWANCE.encodeCall(Tuple.of(APPROVED_HEADLONG_ADDRESS, UNAUTHORIZED_SPENDER_HEADLONG_ADDRESS)));
        givenCommonForCall(inputBytes);
        given(attempt.isSelector(HBAR_ALLOWANCE)).willReturn(true);
        given(addressIdConverter.convert(UNAUTHORIZED_SPENDER_HEADLONG_ADDRESS)).willReturn(A_NEW_ACCOUNT_ID);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(HbarAllowanceCall.class);
    }

    @Test
    void callFromHbarAllowanceProxyTest() {
        final Bytes inputBytes = Bytes.wrapByteBuffer(
                HbarAllowanceTranslator.HBAR_ALLOWANCE_PROXY.encodeCall(Tuple.singleton(APPROVED_HEADLONG_ADDRESS)));
        givenCommonForCall(inputBytes);
        given(attempt.isSelector(HBAR_ALLOWANCE_PROXY)).willReturn(true);
        given(attempt.isSelector(HBAR_ALLOWANCE)).willReturn(false);
        given(attempt.redirectAccountId()).willReturn(A_NEW_ACCOUNT_ID);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(HbarAllowanceCall.class);
    }

    private void givenCommonForCall(Bytes inputBytes) {
        given(attempt.inputBytes()).willReturn(inputBytes.toArray());
        given(attempt.enhancement()).willReturn(enhancement);
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(addressIdConverter.convert(APPROVED_HEADLONG_ADDRESS)).willReturn(B_NEW_ACCOUNT_ID);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
    }
}
