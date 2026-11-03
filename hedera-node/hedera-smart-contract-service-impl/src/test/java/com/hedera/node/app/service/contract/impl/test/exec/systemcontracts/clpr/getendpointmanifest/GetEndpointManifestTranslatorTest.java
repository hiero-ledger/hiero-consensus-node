// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.clpr.getendpointmanifest;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getendpointmanifest.GetEndpointManifestTranslator.GET_ENDPOINT_MANIFEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.clpr.ClprServiceConstants;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getendpointmanifest.GetEndpointManifestCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getendpointmanifest.GetEndpointManifestTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAttemptOptions;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class GetEndpointManifestTranslatorTest extends CallAttemptTestBase {

    @Mock
    private ContractMetrics contractMetrics;

    private GetEndpointManifestTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new GetEndpointManifestTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    @DisplayName("should match getEndpointManifest selector")
    void matchesGetEndpointManifestSelector() {
        final var attempt = createClprCallAttempt(GET_ENDPOINT_MANIFEST);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    @DisplayName("should not match unrelated selector")
    void doesNotMatchUnrelatedSelector() {
        final var attempt = createClprCallAttempt(BurnTranslator.BURN_TOKEN_V2);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    @DisplayName("should create GetEndpointManifestCall from attempt")
    void createsCallFromAttempt() {
        final var attempt = org.mockito.Mockito.mock(ClprCallAttempt.class);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(GetEndpointManifestCall.class);
    }

    private ClprCallAttempt createClprCallAttempt(final SystemContractMethod method) {
        return new ClprCallAttempt(
                Bytes.wrap(method.selector()),
                new CallAttemptOptions<>(
                        ClprServiceConstants.CLPR_CONTRACT_ID,
                        TestHelpers.OWNER_BESU_ADDRESS,
                        Address.fromHexString(ClprServiceConstants.CLPR_EVM_ADDRESS),
                        TestHelpers.OWNER_BESU_ADDRESS,
                        false,
                        mockEnhancement(),
                        TestHelpers.DEFAULT_CONFIG,
                        addressIdConverter,
                        verificationStrategies,
                        gasCalculator,
                        List.of(subject),
                        systemContractMethodRegistry,
                        false));
    }
}
