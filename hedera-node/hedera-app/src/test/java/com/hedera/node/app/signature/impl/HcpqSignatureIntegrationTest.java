// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hedera.cryptography.hcpq.Hcpq;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.node.app.signature.DefaultKeyVerifier;
import com.hedera.node.app.signature.ExpandedSignaturePair;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import org.hiero.base.crypto.Cryptography;
import org.junit.jupiter.api.Test;

class HcpqSignatureIntegrationTest {
    private static final Bytes LEDGER_ID = Bytes.wrap(new byte[] {0x00});
    private static final Bytes BODY = Bytes.wrap(new byte[] {0x0a, 0x01, 0x01});

    @Test
    void expandsAndVerifiesAnHcpqSignature() throws Exception {
        final var random = new SecureRandom();
        final var keyPair = Hcpq.generateKeyPair(random);
        final var publicKey = Hcpq.rawPublicKey(keyPair);
        final var keyId = Hcpq.keyId(publicKey);
        final var signature =
                Hcpq.signTransaction(LEDGER_ID.toByteArray(), BODY.toByteArray(), keyPair.getPrivate(), random);
        final var key = Key.newBuilder().mlDsa44(Bytes.wrap(publicKey)).build();
        final var signaturePair = SignaturePair.newBuilder()
                .pubKeyPrefix(Bytes.wrap(keyId))
                .mlDsa44(Bytes.wrap(signature))
                .build();

        final var expanded = new HashSet<ExpandedSignaturePair>();
        new SignatureExpanderImpl().expand(key, List.of(signaturePair), expanded);

        assertThat(expanded).hasSize(1);
        final var results = new SignatureVerifierImpl(mock(Cryptography.class)).verify(BODY, expanded, LEDGER_ID);
        assertThat(results.get(key).get().passed()).isTrue();
        assertThat(new DefaultKeyVerifier(mock(HederaConfig.class), results)
                        .verificationFor(key)
                        .passed())
                .isTrue();
        assertThat(new SignatureVerifierImpl(mock(Cryptography.class))
                        .verify(BODY, expanded)
                        .get(key)
                        .get()
                        .failed())
                .isTrue();
    }

    @Test
    void rejectsWrongLedgerAndShortKeyIdentifier() throws Exception {
        final var random = new SecureRandom();
        final var keyPair = Hcpq.generateKeyPair(random);
        final var publicKey = Hcpq.rawPublicKey(keyPair);
        final var keyId = Hcpq.keyId(publicKey);
        final var signature =
                Hcpq.signTransaction(LEDGER_ID.toByteArray(), BODY.toByteArray(), keyPair.getPrivate(), random);
        final var key = Key.newBuilder().mlDsa44(Bytes.wrap(publicKey)).build();
        final var validPair = SignaturePair.newBuilder()
                .pubKeyPrefix(Bytes.wrap(keyId))
                .mlDsa44(Bytes.wrap(signature))
                .build();
        final var expanded = new HashSet<ExpandedSignaturePair>();
        new SignatureExpanderImpl().expand(key, List.of(validPair), expanded);

        final var results = new SignatureVerifierImpl(mock(Cryptography.class))
                .verify(BODY, expanded, Bytes.wrap(new byte[] {0x01}));
        assertThat(results.get(key).get().failed()).isTrue();

        final var shortIdPair = validPair
                .copyBuilder()
                .pubKeyPrefix(Bytes.wrap(java.util.Arrays.copyOf(keyId, keyId.length - 1)))
                .build();
        expanded.clear();
        new SignatureExpanderImpl().expand(key, List.of(shortIdPair), expanded);
        assertThat(expanded).isEmpty();

        final var malformedKey =
                Key.newBuilder().mlDsa44(Bytes.wrap(new byte[1311])).build();
        new SignatureExpanderImpl().expand(malformedKey, List.of(validPair), expanded);
        assertThat(expanded).isEmpty();
    }
}
