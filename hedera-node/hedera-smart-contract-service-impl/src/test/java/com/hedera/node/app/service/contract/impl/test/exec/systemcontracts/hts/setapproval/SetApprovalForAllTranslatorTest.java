// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.setapproval;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.setapproval.SetApprovalForAllTranslator.ERC721_SET_APPROVAL_FOR_ALL;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.setapproval.SetApprovalForAllTranslator.SET_APPROVAL_FOR_ALL;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.setapproval.SetApprovalForAllDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.setapproval.SetApprovalForAllTranslator;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.apache.tuweni.bytes.Bytes;
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
        attempt = createHtsCallAttempt(Bytes.wrap(SET_APPROVAL_FOR_ALL.selector()), subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesERCSelectorTest() {
        given(nativeOperations.getToken(anyLong())).willReturn(FUNGIBLE_TOKEN);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        attempt = createHtsCallAttempt(
                TestHelpers.bytesForRedirect(ERC721_SET_APPROVAL_FOR_ALL.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void falseOnInvalidSelector() {
        attempt = createHtsCallAttempt(Bytes.wrap(BURN_TOKEN_V2.selector()), subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }
}
