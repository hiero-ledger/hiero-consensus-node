// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.update.address_0x16c;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_16C_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c.UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHtsAttemptWithSelectorWithContractID;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.freeze.FreezeUnfreezeTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c.UpdateDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c.UpdateTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UpdateTranslatorTest extends CallTestBase {

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private VerificationStrategies verificationStrategies;

    @Mock
    private HederaWorldUpdater.Enhancement enhancement;

    @Mock
    private ContractMetrics contractMetrics;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private UpdateTranslator subject;

    private final UpdateDecoder decoder = new UpdateDecoder();

    @BeforeEach
    void setUp() {
        subject = new UpdateTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesUpdateMetadataTest() {
        attempt = prepareHtsAttemptWithSelectorWithContractID(
                HTS_16C_CONTRACT_ID,
                TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesFailsOnIncorrectSelector() {
        attempt = prepareHtsAttemptWithSelectorWithContractID(
                HTS_16C_CONTRACT_ID,
                FreezeUnfreezeTranslator.FREEZE,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }
}
