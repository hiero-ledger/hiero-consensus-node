// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.hederaaccountnumalias;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarapprove.HbarApproveTranslator.HBAR_APPROVE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hederaaccountnumalias.HederaAccountNumAliasTranslator.HEDERA_ACCOUNT_NUM_ALIAS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ACCOUNT_AS_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hederaaccountnumalias.HederaAccountNumAliasCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hederaaccountnumalias.HederaAccountNumAliasTranslator;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

public class HederaAccountNumAliasTranslatorTest extends CallAttemptTestBase {

    @Mock
    private HasCallAttempt attempt;

    @Mock
    private ContractMetrics contractMetrics;

    private HederaAccountNumAliasTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new HederaAccountNumAliasTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesHederaAccountNumAlias() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when
        attempt = createHasCallAttempt(
                TestHelpers.bytesForRedirectAccount(HEDERA_ACCOUNT_NUM_ALIAS.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                subject);
        // then
        assertThat(subject.identifyMethod(attempt)).isPresent();
        assertEquals("0xbbf12d2e" /*copied from HIP-632*/, "0x" + HEDERA_ACCOUNT_NUM_ALIAS.selectorHex());
    }

    @Test
    void failsOnInvalidSelector() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when
        attempt = createHasCallAttempt(
                TestHelpers.bytesForRedirectAccount(HBAR_APPROVE.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS), subject);
        // then
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromHederaAccountNumAliasTest() {
        final Bytes inputBytes =
                Bytes.wrapByteBuffer(HEDERA_ACCOUNT_NUM_ALIAS.encodeCall(Tuple.singleton(OWNER_ACCOUNT_AS_ADDRESS)));
        givenCommonForCall(inputBytes);
        // when
        final var call = subject.callFrom(attempt);
        // then
        assertThat(call).isInstanceOf(HederaAccountNumAliasCall.class);
    }

    private void givenCommonForCall(Bytes inputBytes) {
        given(attempt.input()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
    }
}
