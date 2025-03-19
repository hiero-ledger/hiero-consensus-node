// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.burn;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V1;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V3;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class BurnTranslatorTest extends CallAttemptTestBase {

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private ContractMetrics contractMetrics;

    private BurnTranslator subject;

    private final BurnDecoder decoder = new BurnDecoder();

    @BeforeEach
    void setUp() {
        subject = new BurnTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesBurnTokenV1() {
        attempt = createHtsCallAttempt(BURN_TOKEN_V1, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesBurnTokenV2() {
        attempt = createHtsCallAttempt(BURN_TOKEN_V2, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchFailsOnInvalidSelector() {
        attempt = createHtsCallAttempt(TOKEN_UPDATE_INFO_FUNCTION_V3, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }
}
