// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.isauthorized;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceTranslator.HBAR_ALLOWANCE_PROXY;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorized.IsAuthorizedTranslator.IS_AUTHORIZED;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.message;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.signature;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHasAttemptWithSelectorAndCustomConfig;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHasAttemptWithSelectorAndInputAndCustomConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorized.IsAuthorizedCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorized.IsAuthorizedTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.exec.v051.Version051FeatureFlags;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class IsAuthorizedTranslatorTest {

    @Mock(strictness = Strictness.LENIENT) // might not use `configuration()`
    private HasCallAttempt attempt;

    @Mock(strictness = Strictness.LENIENT) // might not use `nativeOperations()`
    private HederaWorldUpdater.Enhancement enhancement;

    @Mock
    private SystemContractGasCalculator gasCalculator;

    @Mock
    private CustomGasCalculator customGasCalculator;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private VerificationStrategies verificationStrategies;

    @Mock
    private SignatureVerifier signatureVerifier;

    @Mock
    private HederaNativeOperations nativeOperations;

    @Mock
    private ContractMetrics contractMetrics;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private IsAuthorizedTranslator subject;

    @BeforeEach
    void setUp() {
        final var featureFlags = new Version051FeatureFlags();
        subject = new IsAuthorizedTranslator(
                featureFlags, customGasCalculator, systemContractMethodRegistry, contractMetrics);

        given(enhancement.nativeOperations()).willReturn(nativeOperations);
    }

    @Test
    void matchesIsAuthorizedWhenEnabled() {
        attempt = prepareHasAttemptWithSelectorAndCustomConfig(
                IS_AUTHORIZED,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                getTestConfiguration(true));
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void doesNotMatchIsAuthorizedWhenDisabled() {
        attempt = prepareHasAttemptWithSelectorAndCustomConfig(
                IS_AUTHORIZED,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                getTestConfiguration(false));
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void failsOnInvalidSelector() {
        attempt = prepareHasAttemptWithSelectorAndCustomConfig(
                HBAR_ALLOWANCE_PROXY,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                getTestConfiguration(true));
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromIsAuthorizedTest() {

        final var input =
                Bytes.wrapByteBuffer(IS_AUTHORIZED.encodeCall(Tuple.of(APPROVED_HEADLONG_ADDRESS, message, signature)));
        attempt = prepareHasAttemptWithSelectorAndInputAndCustomConfig(
                IS_AUTHORIZED,
                input,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                getTestConfiguration(true));

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
