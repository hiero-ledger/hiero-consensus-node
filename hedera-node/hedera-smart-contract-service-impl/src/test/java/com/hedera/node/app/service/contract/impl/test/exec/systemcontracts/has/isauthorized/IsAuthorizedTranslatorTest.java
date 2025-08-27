// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.isauthorized;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceTranslator.HBAR_ALLOWANCE_PROXY;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorized.IsAuthorizedTranslator.IS_AUTHORIZED;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.message;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.signature;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorized.IsAuthorizedCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorized.IsAuthorizedTranslator;
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

public class IsAuthorizedTranslatorTest extends CallAttemptTestBase {

    @Mock(strictness = Strictness.LENIENT) // might not use `configuration()`
    private HasCallAttempt attempt;

    @Mock
    private CustomGasCalculator customGasCalculator;

    @Mock
    private ContractMetrics contractMetrics;

    private IsAuthorizedTranslator subject;

    @BeforeEach
    void setUp() {
        final var featureFlags = new Version051FeatureFlags();
        subject = new IsAuthorizedTranslator(
                featureFlags, customGasCalculator, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesIsAuthorizedWhenEnabled() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when
        attempt = createHasCallAttempt(
                TestHelpers.bytesForRedirectAccount(IS_AUTHORIZED.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                getTestConfiguration(true),
                subject);
        // then
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void doesNotMatchIsAuthorizedWhenDisabled() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when
        attempt = createHasCallAttempt(
                TestHelpers.bytesForRedirectAccount(IS_AUTHORIZED.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS),
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
    void callFromIsAuthorizedTest() {
        final var input =
                Bytes.wrapByteBuffer(IS_AUTHORIZED.encodeCall(Tuple.of(APPROVED_HEADLONG_ADDRESS, message, signature)));
        // when
        attempt = createHasCallAttempt(input, getTestConfiguration(true), subject);
        // then
        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(IsAuthorizedCall.class);
    }

    @NonNull
    Configuration getTestConfiguration(final boolean enableIsAuthorized) {
        return HederaTestConfigBuilder.create()
                .withValue("contracts.systemContract.accountService.isAuthorizedEnabled", enableIsAuthorized)
                .getOrCreateConfig();
    }
}
