// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.ContractID;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Which system contracts get the {@code [CLPR-VERIFY-NATIVE]} debug logging: the CLPR router and the verifiers. */
class AbstractNativeSystemContractDebugTargetTest {
    private static final Bytes INPUT = Bytes.wrap(new byte[] {1, 2, 3, 4});

    @ParameterizedTest
    @ValueSource(longs = {0x16eL, 0x16fL, 0x170L, 0x171L, 0x173L})
    void clprRouterAndVerifiersAreDebugTargetsByNumber(final long num) {
        assertThat(AbstractNativeSystemContract.isClprVerifierDebugTarget(contractNum(num), INPUT))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {0x16eL, 0x16fL, 0x170L, 0x171L, 0x173L})
    void clprRouterAndVerifiersAreDebugTargetsByEvmAddress(final long num) {
        assertThat(AbstractNativeSystemContract.isClprVerifierDebugTarget(evmAddress(num), INPUT))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {0x167L, 0x16cL, 0x172L, 0x174L})
    void otherSystemContractsAreNotDebugTargets(final long num) {
        assertThat(AbstractNativeSystemContract.isClprVerifierDebugTarget(contractNum(num), INPUT))
                .isFalse();
        assertThat(AbstractNativeSystemContract.isClprVerifierDebugTarget(evmAddress(num), INPUT))
                .isFalse();
    }

    private static ContractID contractNum(final long num) {
        return ContractID.newBuilder().contractNum(num).build();
    }

    private static ContractID evmAddress(final long num) {
        final byte[] address = new byte[20];
        address[18] = (byte) (num >>> 8);
        address[19] = (byte) num;
        return ContractID.newBuilder()
                .evmAddress(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(address))
                .build();
    }
}
