// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.verifier;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.clpr.impl.verifier.VerifiersRegistry;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProofVerifier;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;

class VerifiersRegistryTest {
    private static final Bytes CANTON_EVM_ADDRESS =
            Bytes.wrap(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01, (byte) 0x72});

    @Test
    void resolvesCantonByContractNum() {
        final var cid = ContractID.newBuilder().contractNum(0x172L).build();
        assertThat(VerifiersRegistry.find(cid)).containsInstanceOf(CantonProofVerifier.class);
    }

    @Test
    void resolvesCantonByEvmAddress() {
        final var cid = ContractID.newBuilder().evmAddress(CANTON_EVM_ADDRESS).build();
        assertThat(VerifiersRegistry.find(cid)).containsInstanceOf(CantonProofVerifier.class);
    }

    @Test
    void exposesCantonIdentifiersForCallersThatNeedThem() {
        assertThat(VerifiersRegistry.CANTON_CONTRACT_NUM).isEqualTo(0x172L);
        assertThat(VerifiersRegistry.CANTON_EVM_ADDRESS).isEqualTo(CANTON_EVM_ADDRESS);
    }

    @Test
    void returnsEmptyForEvmPrecompileAndCustomContracts() {
        for (final long num : new long[] {0x16eL, 0x16fL, 0x171L, 0x999L}) {
            final var cid = ContractID.newBuilder().contractNum(num).build();
            assertThat(VerifiersRegistry.find(cid)).as("contract %x", num).isEmpty();
        }
    }

    @Test
    void returnsEmptyForUnsetContractId() {
        assertThat(VerifiersRegistry.find(ContractID.DEFAULT)).isEmpty();
    }
}
