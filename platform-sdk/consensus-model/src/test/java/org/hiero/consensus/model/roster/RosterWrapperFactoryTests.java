// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.PublicKey;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.test.fixtures.roster.RosterWithKeys;
import org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.junit.jupiter.api.Test;

class RosterWrapperFactoryTests {

    /**
     * Assert that the given keys are unique.
     *
     * @param keyA the first key
     * @param keyB the second key
     */
    private void assertKeysAreUnique(@NonNull final PublicKey keyA, @NonNull final PublicKey keyB) {
        final byte[] keyABytes = keyA.getEncoded();
        final byte[] keyBBytes = keyB.getEncoded();

        for (int i = 0; i < keyABytes.length; i++) {
            if (keyABytes[i] != keyBBytes[i]) {
                return;
            }
        }
        fail("Keys are not unique");
    }

    /**
     * Normally this would be broken up into several tests, but because it's not cheap to generate keys, better
     * to do it all in one test with the same set of keys.
     */
    @Test
    void validDeterministicKeysTest() {
        final Randotron randotron = Randotron.create();

        // Only generate small address book (it's expensive to generate signatures)
        final int size = 3;

        final RosterWithKeys rosterWithKeysA =
                RosterWrapperFactory.randomRosterWithKeys(randotron, size, WeightGenerators.GAUSSIAN);
        final RosterWrapper rosterA = rosterWithKeysA.roster();

        final RosterWrapper rosterB = RosterWrapperFactory.randomRosterWithKeys(
                        randotron.copyAndReset(), size, WeightGenerators.GAUSSIAN)
                .roster();

        // The address book should be the same (keys should be deterministic)
        assertThat(rosterA).isEqualTo(rosterB);

        // Verify that each address has unique keys
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                final RosterEntryWrapper entryI = rosterA.rosterEntry(i);
                final PublicKey signaturePublicKeyI =
                        entryI.gossipCaCertificate().getPublicKey();

                final RosterEntryWrapper entryJ = rosterA.rosterEntry(j);
                final PublicKey signaturePublicKeyJ =
                        entryJ.gossipCaCertificate().getPublicKey();

                assertKeysAreUnique(signaturePublicKeyI, signaturePublicKeyJ);
            }
        }

        // Verify that the private key can produce valid signatures that can be verified by the public key
        for (int i = 0; i < size; i++) {
            final RosterEntryWrapper entry = rosterA.rosterEntry(i);
            final PublicKey signaturePublicKey = entry.gossipCaCertificate().getPublicKey();
            final KeysAndCerts privateKeys = rosterWithKeysA.privateKey(entry.nodeId());

            final byte[] dataArray = randotron.nextByteArray(64);
            final Bytes dataBytes = Bytes.wrap(dataArray);
            final Signature signature = new PlatformSigner(privateKeys).sign(dataArray);

            assertTrue(CryptoUtils.verifySignature(dataBytes, signature.getBytes(), signaturePublicKey));

            // Sanity check: validating using the wrong public key should fail
            final RosterEntryWrapper wrongEntry = rosterA.rosterEntry((i + 1) % size);
            final PublicKey wrongPublicKey = wrongEntry.gossipCaCertificate().getPublicKey();
            assertFalse(CryptoUtils.verifySignature(dataBytes, signature.getBytes(), wrongPublicKey));

            // Sanity check: validating against the wrong data should fail
            final Bytes wrongData = randotron.nextHashBytes();
            assertFalse(CryptoUtils.verifySignature(wrongData, signature.getBytes(), signaturePublicKey));

            // Sanity check: validating with a modified signature should fail
            final byte[] modifiedSignature = signature.getBytes().toByteArray();
            modifiedSignature[0] = (byte) ~modifiedSignature[0];
            assertFalse(CryptoUtils.verifySignature(dataBytes, Bytes.wrap(modifiedSignature), signaturePublicKey));
        }
    }
}
