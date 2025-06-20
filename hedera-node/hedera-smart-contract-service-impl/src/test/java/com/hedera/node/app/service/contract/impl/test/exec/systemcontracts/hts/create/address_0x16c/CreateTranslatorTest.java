// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create.address_0x16c;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_16C_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_FUNGIBLE_TOKEN_WITH_METADATA;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class CreateTranslatorTest extends CallAttemptTestBase {

    @Mock
    private ContractMetrics contractMetrics;

    @Mock
    private HtsCallAttempt attempt;

    private final CreateDecoder decoder = new CreateDecoder();
    private CreateTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new CreateTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesCreateFungibleTokenWithMetadata() {
        attempt = createHtsCallAttempt(HTS_16C_CONTRACT_ID, CREATE_FUNGIBLE_TOKEN_WITH_METADATA, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateFungibleTokenWithMetadataAndCustomFees() {
        attempt =
                createHtsCallAttempt(HTS_16C_CONTRACT_ID, CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateNonFungibleTokenWithMetadata() {
        attempt = createHtsCallAttempt(HTS_16C_CONTRACT_ID, CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesCreateNonFungibleTokenWithMetadataAndCustomFees() {
        attempt = createHtsCallAttempt(
                HTS_16C_CONTRACT_ID, CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void falseOnInvalidSelector16c() {
        attempt = createHtsCallAttempt(HTS_16C_CONTRACT_ID, BURN_TOKEN_V2, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }
}
