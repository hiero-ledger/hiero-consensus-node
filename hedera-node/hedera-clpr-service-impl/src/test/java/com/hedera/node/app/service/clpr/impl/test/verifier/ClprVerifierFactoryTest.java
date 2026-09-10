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
}
