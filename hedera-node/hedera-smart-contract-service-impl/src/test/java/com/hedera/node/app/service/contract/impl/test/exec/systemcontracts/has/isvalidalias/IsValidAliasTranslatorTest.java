// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.isvalidalias;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarapprove.HbarApproveTranslator.HBAR_APPROVE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isvalidalias.IsValidAliasTranslator.IS_VALID_ALIAS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ACCOUNT_AS_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isvalidalias.IsValidAliasCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isvalidalias.IsValidAliasTranslator;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

public class IsValidAliasTranslatorTest extends CallAttemptTestBase {

    @Mock
    private HasCallAttempt attempt;

    @Mock
    private ContractMetrics contractMetrics;

    private IsValidAliasTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new IsValidAliasTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesIsValidAliasSelector() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when
        attempt = createHasCallAttempt(
                TestHelpers.bytesForRedirectAccount(IS_VALID_ALIAS.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS), subject);
        // then
        assertThat(subject.identifyMethod(attempt)).isPresent();
        assertEquals("0x308ef301" /*copied from HIP-632*/, "0x" + IS_VALID_ALIAS.selectorHex());
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
    void callFromIsValidAliasTest() {
        final Bytes inputBytes =
                Bytes.wrapByteBuffer(IS_VALID_ALIAS.encodeCall(Tuple.singleton(OWNER_ACCOUNT_AS_ADDRESS)));
        givenCommonForCall(inputBytes);
        // when
        final var call = subject.callFrom(attempt);
        // then
        assertThat(call).isInstanceOf(IsValidAliasCall.class);
    }

    private void givenCommonForCall(Bytes inputBytes) {
        given(attempt.input()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
    }
}
