// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr.ethereum;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.cryptography.hints.EthereumBlsBridge;
import java.math.BigInteger;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks {@link BlsSigner} against blst, through the {@link EthereumBlsBridge} the node verifies with: an
 * independent implementation of the ciphersuite, so a wrong hash to G2 or domain separation tag fails here rather than
 * only in a slow embedded HAPI run.
 */
class BlsSignerTest {

    private static final BlsKeys SIGNER = new BlsKeys(BigInteger.valueOf(0x5EED_0001L));
    private static final BlsKeys OTHER_SIGNER = new BlsKeys(BigInteger.valueOf(0x5EED_0002L));
    private static final BlsKeys NON_SIGNER = new BlsKeys(BigInteger.valueOf(0x5EED_0003L));

    /** The public key of the committee of the three keys above. */
    private static final byte[] COMMITTEE_AGGREGATE =
            BlsSigner.publicKey(SIGNER.secretKey().add(OTHER_SIGNER.secretKey()).add(NON_SIGNER.secretKey()));

    private static final byte[] SIGNING_ROOT = HexFormat.of().parseHex("11".repeat(32));
    private static final byte[] OTHER_SIGNING_ROOT = HexFormat.of().parseHex("22".repeat(32));

    private static final EthereumBlsBridge BLST = EthereumBlsBridge.getInstance();

    @Test
    @DisplayName("given the public keys of three secret keys and their sum, then blst accepts them as valid keys")
    void givenPublicKeys_thenBlstValidatesThem() {
        assertThat(BLST.validateG1Eip2537PublicKeys(new byte[][] {
                    SIGNER.publicKey(), OTHER_SIGNER.publicKey(), NON_SIGNER.publicKey(), COMMITTEE_AGGREGATE
                }))
                .isTrue();
    }

    @Test
    @DisplayName("given two of three members sign, then blst verifies the signature under the aggregate minus the"
            + " non-signer")
    void givenTwoOfThreeSign_thenBlstVerifiesSignature() {
        final byte[] signature = BlsSigner.sign(SIGNER.secretKey().add(OTHER_SIGNER.secretKey()), SIGNING_ROOT);

        assertThat(BLST.verifySyncAggregate(
                        COMMITTEE_AGGREGATE, new byte[][] {NON_SIGNER.publicKey()}, SIGNING_ROOT, signature))
                .isTrue();
    }

    @Test
    @DisplayName("given a signature over another message, then blst rejects it")
    void givenSignatureOverAnotherMessage_thenBlstRejectsIt() {
        final byte[] signature = BlsSigner.sign(SIGNER.secretKey().add(OTHER_SIGNER.secretKey()), OTHER_SIGNING_ROOT);

        assertThat(BLST.verifySyncAggregate(
                        COMMITTEE_AGGREGATE, new byte[][] {NON_SIGNER.publicKey()}, SIGNING_ROOT, signature))
                .isFalse();
    }

    /** A test secret key and its public key. */
    private record BlsKeys(BigInteger secretKey, byte[] publicKey) {
        BlsKeys(final BigInteger secretKey) {
            this(secretKey, BlsSigner.publicKey(secretKey));
        }
    }
}
