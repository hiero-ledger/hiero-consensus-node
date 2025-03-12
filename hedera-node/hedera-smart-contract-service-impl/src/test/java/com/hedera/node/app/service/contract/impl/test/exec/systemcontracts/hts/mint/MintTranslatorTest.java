// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.mint;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.mint.MintTranslator.MINT;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.mint.MintTranslator.MINT_V2;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.mint.MintDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.mint.MintTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class MintTranslatorTest extends CallAttemptTestBase {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private ContractMetrics contractMetrics;

    private MintTranslator subject;

    private final MintDecoder decoder = new MintDecoder();

    @BeforeEach
    void setUp() {
        subject = new MintTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesMintV1Test() {
        attempt = createHtsCallAttempt(Bytes.wrap(MINT.selector()), subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesMintV2Test() {
        attempt = createHtsCallAttempt(Bytes.wrap(MINT_V2.selector()), subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchFailsOnIncorrectSelectorTest() {
        attempt = createHtsCallAttempt(Bytes.wrap(BURN_TOKEN_V2.selector()), subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }
}
