// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.scope;

import static com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy.Decision.DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION;
import static com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy.Decision.INVALID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ANOTHER_ED25519_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.AN_ED25519_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_CONTRACT_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_SECP256K1_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_SECP256K1_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.node.app.service.contract.impl.exec.scope.SpecificCryptoVerificationStrategy;
import org.junit.jupiter.api.Test;

class SpecificCryptoVerificationStrategyTest {
    @Test
    void delegatesVerificationForSpecificEd25519KeyOnly() {
        final var subject = new SpecificCryptoVerificationStrategy(AN_ED25519_KEY);

        assertThat(subject.decideForPrimitive(AN_ED25519_KEY)).isSameAs(DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION);
        assertThat(subject.decideForPrimitive(ANOTHER_ED25519_KEY)).isSameAs(INVALID);
    }

    @Test
    void delegatesVerificationForSpecificECDSAKeyOnly() {
        final var subject = new SpecificCryptoVerificationStrategy(A_SECP256K1_KEY);

        assertThat(subject.decideForPrimitive(A_SECP256K1_KEY)).isSameAs(DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION);
        assertThat(subject.decideForPrimitive(B_SECP256K1_KEY)).isSameAs(INVALID);
    }

    @Test
    void failsToConstructWithoutCryptoKey() {
        assertThrows(IllegalArgumentException.class, () -> new SpecificCryptoVerificationStrategy(A_CONTRACT_KEY));
    }
}
