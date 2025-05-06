// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.PublicKey;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import org.hiero.base.crypto.KeyType;
import org.hiero.base.crypto.Signature;
import org.hiero.base.crypto.test.fixtures.PreGeneratedPublicKeys;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class KeysAndCertsTest {
    private static final byte[] DATA_ARRAY = {1, 2, 3};
    private static final Bytes DATA_BYTES = Bytes.wrap(DATA_ARRAY);
    private static final PublicKey WRONG_KEY =
            PreGeneratedPublicKeys.getPublicKey(KeyType.RSA, 0).getPublicKey();

    private void testSignVerify(final PlatformSigner signer, final PublicKey publicKey) {
        final Signature signature = signer.sign(DATA_ARRAY);

        assertTrue(
                CryptoStatic.verifySignature(DATA_BYTES, signature.getBytes(), publicKey),
                "verify should be true when using the correct public key");
        assertFalse(
                CryptoStatic.verifySignature(DATA_BYTES, signature.getBytes(), WRONG_KEY),
                "verify should be false when using the incorrect public key");
    }

    /**
     * Tests signing and verifying with provided {@link KeysAndCerts}
     *
     * @param roster
     * 		roster of the network
     * @param keysAndCerts
     * 		keys and certificates to use for testing
     */
    @ParameterizedTest
    @MethodSource({"com.swirlds.platform.crypto.CryptoArgsProvider#basicTestArgs"})
    void basicTest(@NonNull final Roster roster, @NonNull final Map<NodeId, KeysAndCerts> keysAndCerts) {
        Objects.requireNonNull(roster, "roster must not be null");
        Objects.requireNonNull(keysAndCerts, "keysAndCerts must not be null");
        // choose a random node to test
        final Random random = new Random();
        final int node = random.nextInt(roster.rosterEntries().size());
        final NodeId nodeId = NodeId.of(roster.rosterEntries().get(node).nodeId());

        final PlatformSigner signer = new PlatformSigner(keysAndCerts.get(nodeId));
        testSignVerify(
                signer,
                RosterUtils.fetchGossipCaCertificate(roster.rosterEntries().get(node))
                        .getPublicKey());
        // test it twice to verify that the signer is reusable
        testSignVerify(
                signer,
                RosterUtils.fetchGossipCaCertificate(roster.rosterEntries().get(node))
                        .getPublicKey());
    }
}
