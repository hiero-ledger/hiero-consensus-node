// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.isauthorizedraw;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceTranslator.HBAR_ALLOWANCE_PROXY;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorizedraw.IsAuthorizedRawTranslator.IS_AUTHORIZED_RAW;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.messageHash;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.signature;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHasAttemptWithSelectorAndCustomConfig;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorizedraw.IsAuthorizedRawCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorizedraw.IsAuthorizedRawTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
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
public class IsAuthorizedRawTranslatorTest {

    @Mock(strictness = Strictness.LENIENT) // might not use `configuration()`
    private HasCallAttempt attempt;

    @Mock
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

    private IsAuthorizedRawTranslator subject;

    @BeforeEach
    void setUp() {
        final var featureFlags = new Version051FeatureFlags();
        subject = new IsAuthorizedRawTranslator(
                featureFlags, customGasCalculator, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesIsAuthorizedRawWhenEnabled() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        attempt = prepareHasAttemptWithSelectorAndCustomConfig(
                IS_AUTHORIZED_RAW,
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
    void doesNotMatchIsAuthorizedRawWhenDisabled() {
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        attempt = prepareHasAttemptWithSelectorAndCustomConfig(
                IS_AUTHORIZED_RAW,
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
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
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
    void callFromIsAuthorizedRawTest() {
        given(attempt.configuration()).willReturn(getTestConfiguration(true));
        final Bytes inputBytes = Bytes.wrapByteBuffer(
                IS_AUTHORIZED_RAW.encodeCall(Tuple.of(APPROVED_HEADLONG_ADDRESS, messageHash, signature)));
        givenCommonForCall(inputBytes);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(IsAuthorizedRawCall.class);
    }

    private void givenCommonForCall(Bytes inputBytes) {
        given(attempt.inputBytes()).willReturn(inputBytes.toArray());
        given(attempt.enhancement()).willReturn(enhancement);
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
