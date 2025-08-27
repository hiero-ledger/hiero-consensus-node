// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.isauthorizedraw;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceTranslator.HBAR_ALLOWANCE_PROXY;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorizedraw.IsAuthorizedRawTranslator.IS_AUTHORIZED_RAW;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.messageHash;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.signature;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorizedraw.IsAuthorizedRawCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorizedraw.IsAuthorizedRawTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.v051.Version051FeatureFlags;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;

public class IsAuthorizedRawTranslatorTest extends CallAttemptTestBase {

    @Mock(strictness = Strictness.LENIENT) // might not use `configuration()`
    private HasCallAttempt attempt;

    @Mock
    private CustomGasCalculator customGasCalculator;

    @Mock
    private ContractMetrics contractMetrics;

    private IsAuthorizedRawTranslator subject;

    @BeforeEach
    void setUp() {
        final var featureFlags = new Version051FeatureFlags();
        subject = new IsAuthorizedRawTranslator(
                featureFlags, customGasCalculator, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesIsAuthorizedRawWhenEnabled() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when
        attempt = createHasCallAttempt(
                TestHelpers.bytesForRedirectAccount(IS_AUTHORIZED_RAW.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                getTestConfiguration(true),
                subject);
        // then
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void doesNotMatchIsAuthorizedRawWhenDisabled() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when
        attempt = createHasCallAttempt(
                TestHelpers.bytesForRedirectAccount(IS_AUTHORIZED_RAW.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                getTestConfiguration(false),
                subject);
        // then
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void failsOnInvalidSelector() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when
        attempt = createHasCallAttempt(
                TestHelpers.bytesForRedirectAccount(HBAR_ALLOWANCE_PROXY.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                getTestConfiguration(true),
                subject);
        // then
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromIsAuthorizedRawTest() {
        given(attempt.configuration()).willReturn(getTestConfiguration(true));
        final Bytes inputBytes = Bytes.wrapByteBuffer(
                IS_AUTHORIZED_RAW.encodeCall(Tuple.of(APPROVED_HEADLONG_ADDRESS, messageHash, signature)));
        givenCommonForCall(inputBytes);
        // when
        final var call = subject.callFrom(attempt);
        // then
        assertThat(call).isInstanceOf(IsAuthorizedRawCall.class);
    }

    private void givenCommonForCall(Bytes inputBytes) {
        given(attempt.inputBytes()).willReturn(inputBytes.toArray());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.isSelector((SystemContractMethod) any())).willReturn(true);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.signatureVerifier()).willReturn(signatureVerifier);
    }

    @NonNull
    Configuration getTestConfiguration(final boolean enableIsAuthorizedRaw) {
        return HederaTestConfigBuilder.create()
                .withValue("contracts.systemContract.accountService.isAuthorizedRawEnabled", enableIsAuthorizedRaw)
                .getOrCreateConfig();
    }
}
