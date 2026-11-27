// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.verifier;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_VERIFIER_CONTRACT;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierFactory;
import com.hedera.node.app.service.clpr.impl.verifier.EvmClprVerifier;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClprVerifierFactoryTest {

    private final ClprVerifierFactory subject = new ClprVerifierFactory();

    @Test
    @DisplayName("getVerifier returns an EvmClprVerifier bound to a contractNum verifier contract")
    void resolvesContractNumVerifier() {
        final var contractId = ContractID.newBuilder()
                .shardNum(0)
                .realmNum(0)
                .contractNum(5001)
                .build();
        assertThat(subject.getVerifier(contractId)).isInstanceOf(EvmClprVerifier.class);
    }

    @Test
    @DisplayName("getVerifier returns an EvmClprVerifier bound to an evmAddress verifier contract")
    void resolvesEvmAddressVerifier() {
        final var evmAddress =
                Bytes.wrap(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x13, (byte) 0x89});
        final var contractId = ContractID.newBuilder().evmAddress(evmAddress).build();
        assertThat(subject.getVerifier(contractId)).isInstanceOf(EvmClprVerifier.class);
    }

    @Test
    @DisplayName("getVerifier rejects a verifier contract with no identifier")
    void rejectsContractIdWithoutIdentifier() {
        final var contractId = ContractID.newBuilder().build();
        assertThatThrownBy(() -> subject.getVerifier(contractId))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_VERIFIER_CONTRACT));
    }

    @Test
    @DisplayName("getVerifier resolves a precompile built-in (0x16f Besu) to an EvmClprVerifier")
    void precompileBuiltInResolvesToEvmVerifier() {
        final var cid = ContractID.newBuilder().contractNum(0x16fL).build();
        assertThat(subject.getVerifier(cid)).isInstanceOf(EvmClprVerifier.class);
    }

    @Test
    @DisplayName("getVerifier falls through to EvmClprVerifier for a custom (non-built-in) contract num")
    void customContractFallsThroughToEvm() {
        final var cid = ContractID.newBuilder().contractNum(0x999L).build();
        assertThat(subject.getVerifier(cid)).isInstanceOf(EvmClprVerifier.class);
    }

    @Test
    @DisplayName("getVerifier throws CLPR_INVALID_VERIFIER_CONTRACT for an empty ContractID")
    void emptyIdThrowsInvalidVerifierContract() {
        final var cid = ContractID.newBuilder().build();
        assertThatThrownBy(() -> subject.getVerifier(cid))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_VERIFIER_CONTRACT));
    }

    @Test
    @DisplayName("Canton (0x173) built-in resolves to an EvmClprVerifier via contract num, like every other built-in")
    void cantonResolvesToEvmVerifierByNum() {
        final var cid = ContractID.newBuilder().contractNum(0x173L).build();
        assertThat(subject.getVerifier(cid)).isInstanceOf(EvmClprVerifier.class);
    }

    @Test
    @DisplayName("Canton (0x173) built-in resolves to an EvmClprVerifier via evm address, like every other built-in")
    void cantonResolvesToEvmVerifierByEvmAddress() {
        final var addr =
                Bytes.wrap(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01, (byte) 0x73});
        final var cid = ContractID.newBuilder().evmAddress(addr).build();
        assertThat(subject.getVerifier(cid)).isInstanceOf(EvmClprVerifier.class);
    }

    @Test
    @DisplayName("every built-in verifier address resolves to an EvmClprVerifier: there is no native verifier")
    void everyBuiltInResolvesToEvmVerifier() {
        for (final long num : new long[] {0x16eL, 0x16fL, 0x171L, 0x173L}) {
            final var cid = ContractID.newBuilder().contractNum(num).build();
            assertThat(subject.getVerifier(cid)).as("contract %x", num).isInstanceOf(EvmClprVerifier.class);
        }
    }
}
