// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.PublicKey;
import java.util.Map;
import java.util.Random;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.base.crypto.KeyType;
import org.hiero.base.crypto.Signature;
import org.hiero.base.crypto.test.fixtures.PreGeneratedPublicKeys;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterEntryWrapper;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.test.fixtures.roster.RosterWithKeys;
import org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.junit.jupiter.api.Test;

class KeysAndCertsTest {
    private static final byte[] DATA_ARRAY = {1, 2, 3};
    private static final Bytes DATA_BYTES = Bytes.wrap(DATA_ARRAY);
    private static final PublicKey WRONG_KEY =
            PreGeneratedPublicKeys.getPublicKey(KeyType.RSA, 0).getPublicKey();

    private void testSignVerify(final PlatformSigner signer, final PublicKey publicKey) {
        final Signature signature = signer.sign(DATA_ARRAY);

        assertTrue(
                CryptoUtils.verifySignature(DATA_BYTES, signature.getBytes(), publicKey),
                "verify should be true when using the correct public key");
        assertFalse(
                CryptoUtils.verifySignature(DATA_BYTES, signature.getBytes(), WRONG_KEY),
                "verify should be false when using the incorrect public key");
    }

    /**
     * Tests signing and verifying with provided {@link KeysAndCerts}
     */
    @Test
    void basicTest() {
        final RosterWithKeys rosterWithKeys = RosterWrapperFactory.randomRosterWithKeys(
                Randotron.create(), 10, WeightGenerators.BALANCED_1000_PER_NODE);
        final RosterWrapper roster = rosterWithKeys.roster();
        final Map<NodeId, KeysAndCerts> keysAndCerts = rosterWithKeys.privateKeys();

        // choose a random node to test
        final Random random = new Random();
        final int node = random.nextInt(roster.size());
        final RosterEntryWrapper entry = roster.rosterEntry(node);

        final PlatformSigner signer = new PlatformSigner(keysAndCerts.get(entry.nodeId()));
        testSignVerify(signer, entry.gossipCaCertificate().getPublicKey());
        // test it twice to verify that the signer is reusable
        testSignVerify(signer, entry.gossipCaCertificate().getPublicKey());
    }
}
