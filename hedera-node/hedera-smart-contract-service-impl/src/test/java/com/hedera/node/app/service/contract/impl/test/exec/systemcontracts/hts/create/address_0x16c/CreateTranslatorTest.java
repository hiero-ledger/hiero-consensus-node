// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.address_0x16c;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_16C_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_FUNGIBLE_TOKEN_WITH_METADATA;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.CallAttemptHelpers.prepareHtsAttemptWithSelectorWithContractID;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CreateTranslatorTest {

    @Mock
    private ContractMetrics contractMetrics;

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
    private VerificationStrategy verificationStrategy;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private final CreateDecoder decoder = new CreateDecoder();
    private CreateTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new CreateTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesCreateFungibleTokenWithMetadata() {
        attempt = prepareHtsAttemptWithSelectorWithContractID(
                HTS_16C_CONTRACT_ID,
                CREATE_FUNGIBLE_TOKEN_WITH_METADATA,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateFungibleTokenWithMetadataAndCustomFees() {
        attempt = prepareHtsAttemptWithSelectorWithContractID(
                HTS_16C_CONTRACT_ID,
                CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateNonFungibleTokenWithMetadata() {
        attempt = prepareHtsAttemptWithSelectorWithContractID(
                HTS_16C_CONTRACT_ID,
                CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateNonFungibleTokenWithMetadataAndCustomFees() {
        attempt = prepareHtsAttemptWithSelectorWithContractID(
                HTS_16C_CONTRACT_ID,
                CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void falseOnInvalidSelector16c() {
        attempt = prepareHtsAttemptWithSelectorWithContractID(
                HTS_16C_CONTRACT_ID,
                BURN_TOKEN_V2,
                subject,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                systemContractMethodRegistry);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }
}
