// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class ProofExceptionTest {
    @Test
    void formatsVerifierNameAndMessage() {
        assertThat(new ProofException("Verifier", "bad proof")).hasMessage("Verifier: bad proof");
        assertThat(ProofException.sei("bad proof")).hasMessage("SeiCometBftProofVerifier: bad proof");
        assertThat(ProofException.besuQbft("bad proof")).hasMessage("BesuQbftVerifier: bad proof");
    }

    @Test
    void preservesCauseForAllFactories() {
        final var cause = new IllegalArgumentException("parse failed");

        assertThat(new ProofException("Verifier", "bad proof", cause))
                .hasMessage("Verifier: bad proof")
                .hasCause(cause);
        assertThat(ProofException.sei("bad proof", cause))
                .hasMessage("SeiCometBftProofVerifier: bad proof")
                .hasCause(cause);
        assertThat(ProofException.besuQbft("bad proof", cause))
                .hasMessage("BesuQbftVerifier: bad proof")
                .hasCause(cause);
    }

    @Test
    void rejectsNullInputs() {
        assertThatNullPointerException().isThrownBy(() -> new ProofException(null, "bad proof"));
        assertThatNullPointerException().isThrownBy(() -> new ProofException("Verifier", null));
        assertThatNullPointerException()
                .isThrownBy(() -> new ProofException("Verifier", "bad proof", null))
                .withMessage("cause");
    }
}
