// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.grantrevokekyc;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantrevokekyc.GrantRevokeKycTranslator.GRANT_KYC;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantrevokekyc.GrantRevokeKycTranslator.REVOKE_KYC;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantrevokekyc.GrantRevokeKycDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantrevokekyc.GrantRevokeKycTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class GrantRevokeKycTranslatorTest extends CallAttemptTestBase {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private ContractMetrics contractMetrics;

    private final GrantRevokeKycDecoder decoder = new GrantRevokeKycDecoder();
    private GrantRevokeKycTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new GrantRevokeKycTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesGrantKycTest() {
        attempt = createHtsCallAttempt(GRANT_KYC, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesRevokeKycTest() {
        attempt = createHtsCallAttempt(REVOKE_KYC, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesFailsWithIncorrectSelector() {
        attempt = createHtsCallAttempt(BURN_TOKEN_V2, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }
}
