// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.update.address_0x167;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.freeze.FreezeUnfreezeTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateKeysTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class UpdateKeysTranslatorTest extends CallAttemptTestBase {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private ContractMetrics contractMetrics;

    private UpdateKeysTranslator subject;

    private final UpdateDecoder decoder = new UpdateDecoder();

    @BeforeEach
    void setUp() {
        subject = new UpdateKeysTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesUpdateKeysTest() {
        attempt = createHtsCallAttempt(UpdateKeysTranslator.TOKEN_UPDATE_KEYS_FUNCTION, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesIncorrectSelectorFailsTest() {
        attempt = createHtsCallAttempt(FreezeUnfreezeTranslator.FREEZE, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }
}
