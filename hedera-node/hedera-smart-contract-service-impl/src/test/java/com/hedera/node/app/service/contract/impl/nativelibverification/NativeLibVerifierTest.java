// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.nativelibverification;

import static org.mockito.BDDMockito.given;

import com.hedera.node.config.data.ContractsConfig;
import org.assertj.core.api.Assertions;
import org.hyperledger.besu.crypto.Blake2bfMessageDigest.Blake2bfDigest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testing the Besu Native Libraries verification strategy.
 */
@ExtendWith(MockitoExtension.class)
class NativeLibVerifierTest {

    @Mock
    private ContractsConfig contractsConfig;

    private NativeLibVerifier verifier;

    @BeforeEach
    void beforeEach() {
        verifier = new NativeLibVerifier(() -> contractsConfig);
    }

    @Test
    void verifyNativeLibsInWarningMode() {
        given(contractsConfig.nativeLibVerificationHaltEnabled()).willReturn(false);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> verifier.verifyNativeLibs());
    }

    @Test
    void verifyNativeLibsInHaltMode() {
        given(contractsConfig.nativeLibVerificationHaltEnabled()).willReturn(true);
        Blake2bfDigest.disableNative();
        Assertions.assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> verifier.verifyNativeLibs())
                .withMessageContaining("Native libraries");
    }
}
