// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.wipe;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.wipe.WipeTranslator.WIPE_FUNGIBLE_V1;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.wipe.WipeTranslator.WIPE_FUNGIBLE_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.wipe.WipeTranslator.WIPE_NFT;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.wipe.WipeDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.wipe.WipeTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

public class WipeTranslatorTest extends CallAttemptTestBase {

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private ContractMetrics contractMetrics;

    private final WipeDecoder decoder = new WipeDecoder();

    private WipeTranslator subject;

    @BeforeEach
    void setup() {
        subject = new WipeTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesWipeFungibleV1Test() {
        attempt = createHtsCallAttempt(WIPE_FUNGIBLE_V1, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesWipeFungibleV2Test() {
        attempt = createHtsCallAttempt(WIPE_FUNGIBLE_V2, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesWipeNftTest() {
        attempt = createHtsCallAttempt(WIPE_NFT, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchFailsOnIncorrectSelectorTest() {
        attempt = createHtsCallAttempt(BURN_TOKEN_V2, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }
}
