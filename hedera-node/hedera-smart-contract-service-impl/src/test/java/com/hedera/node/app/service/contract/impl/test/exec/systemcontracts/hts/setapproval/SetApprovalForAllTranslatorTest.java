// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.setapproval;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.setapproval.SetApprovalForAllTranslator.ERC721_SET_APPROVAL_FOR_ALL;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.setapproval.SetApprovalForAllTranslator.SET_APPROVAL_FOR_ALL;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.TokenID;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.setapproval.SetApprovalForAllDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.setapproval.SetApprovalForAllTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

public class SetApprovalForAllTranslatorTest extends CallAttemptTestBase {

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private ContractMetrics contractMetrics;

    private final SetApprovalForAllDecoder decoder = new SetApprovalForAllDecoder();

    private SetApprovalForAllTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new SetApprovalForAllTranslator(decoder, systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesClassicalSelectorTest() {
        attempt = createHtsCallAttempt(SET_APPROVAL_FOR_ALL, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesERCSelectorTest() {
        given(nativeOperations.getToken(any(TokenID.class))).willReturn(FUNGIBLE_TOKEN);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        attempt = createHtsCallAttemptForRedirect(ERC721_SET_APPROVAL_FOR_ALL, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void falseOnInvalidSelector() {
        attempt = createHtsCallAttempt(BURN_TOKEN_V2, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }
}
