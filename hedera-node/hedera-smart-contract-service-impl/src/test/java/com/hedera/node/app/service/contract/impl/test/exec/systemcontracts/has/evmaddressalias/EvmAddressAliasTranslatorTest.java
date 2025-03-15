// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.has.evmaddressalias;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.getevmaddressalias.EvmAddressAliasTranslator.EVM_ADDRESS_ALIAS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarapprove.HbarApproveTranslator.HBAR_APPROVE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.getevmaddressalias.EvmAddressAliasCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.getevmaddressalias.EvmAddressAliasTranslator;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class EvmAddressAliasTranslatorTest extends CallAttemptTestBase {
    @Mock
    private HasCallAttempt attempt;

    @Mock
    private ContractMetrics contractMetrics;

    private EvmAddressAliasTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new EvmAddressAliasTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesEvmAddressAlias() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        // when
        attempt = createHasCallAttempt(
                TestHelpers.bytesForRedirectAccount(EVM_ADDRESS_ALIAS.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                subject);
        // then
        assertThat(subject.identifyMethod(attempt)).isPresent();
        assertEquals("0xdea3d081" /*copied from HIP-632*/, "0x" + EVM_ADDRESS_ALIAS.selectorHex());
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
    void callFromEvmAddressAliasTest() {
        final Bytes inputBytes =
                Bytes.wrapByteBuffer(EVM_ADDRESS_ALIAS.encodeCall(Tuple.singleton(APPROVED_HEADLONG_ADDRESS)));
        givenCommonForCall(inputBytes);
        // when
        final var call = subject.callFrom(attempt);
        // then
        assertThat(call).isInstanceOf(EvmAddressAliasCall.class);
    }

    private void givenCommonForCall(Bytes inputBytes) {
        given(attempt.input()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
    }
}
