// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.pauses;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.pauses.PausesTranslator.PAUSE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.pauses.PausesTranslator.UNPAUSE;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.pauses.PausesDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.pauses.PausesTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

public class PausesTranslatorTest extends CallAttemptTestBase {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private ContractMetrics contractMetrics;

    private final PausesDecoder decoder = new PausesDecoder();

    private PausesTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new PausesTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesPauseTest() {
        attempt = createHtsCallAttempt(PAUSE, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesUnpauseTest() {
        attempt = createHtsCallAttempt(UNPAUSE, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesFailsOnIncorrectSelector() {
        attempt = createHtsCallAttempt(BURN_TOKEN_V2, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }
}
