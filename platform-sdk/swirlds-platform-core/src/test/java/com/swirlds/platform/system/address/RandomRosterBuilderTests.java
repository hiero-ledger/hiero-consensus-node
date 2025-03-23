// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.address;

import static org.assertj.core.api.Fail.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.crypto.PlatformSigner;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.PublicKey;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Test;

class RandomRosterBuilderTests {

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

        final RandomRosterBuilder builderA =
                RandomRosterBuilder.create(randotron).withSize(size).withRealKeysEnabled(true);
        final Roster rosterA = builderA.build();

        final RandomRosterBuilder builderB = RandomRosterBuilder.create(randotron.copyAndReset())
                .withSize(size)
                .withRealKeysEnabled(true);
        final Roster rosterB = builderB.build();

        // The address book should be the same (keys should be deterministic)
        assertEquals(RosterUtils.hash(rosterA), RosterUtils.hash(rosterB));

        // Verify that each address has unique keys
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                if (i == j) {
                    continue;
                }

                final RosterEntry addressI = rosterA.rosterEntries().get(i);
                final PublicKey signaturePublicKeyI =
                        RosterUtils.fetchGossipCaCertificate(addressI).getPublicKey();

                final RosterEntry addressJ = rosterA.rosterEntries().get(j);
                final PublicKey signaturePublicKeyJ =
                        RosterUtils.fetchGossipCaCertificate(addressJ).getPublicKey();

                assertKeysAreUnique(signaturePublicKeyI, signaturePublicKeyJ);
            }
        }

        // Verify that the private key can produce valid signatures that can be verified by the public key
        for (int i = 0; i < size; i++) {
            final RosterEntry address = rosterA.rosterEntries().get(i);
            final NodeId id = NodeId.of(address.nodeId());
            final PublicKey signaturePublicKey =
                    RosterUtils.fetchGossipCaCertificate(address).getPublicKey();
            final KeysAndCerts privateKeys = builderA.getPrivateKeys(id);

            final byte[] dataArray = randotron.nextByteArray(64);
            final Bytes dataBytes = Bytes.wrap(dataArray);
            final com.swirlds.common.crypto.Signature signature = new PlatformSigner(privateKeys).sign(dataArray);

            assertTrue(CryptoStatic.verifySignature(dataBytes, signature.getBytes(), signaturePublicKey));

            // Sanity check: validating using the wrong public key should fail
            final RosterEntry wrongAddress = rosterA.rosterEntries().get((i + 1) % size);
            final NodeId wrongId = NodeId.of(wrongAddress.nodeId());
            final PublicKey wrongPublicKey =
                    RosterUtils.fetchGossipCaCertificate(wrongAddress).getPublicKey();
            assertFalse(CryptoStatic.verifySignature(dataBytes, signature.getBytes(), wrongPublicKey));

            // Sanity check: validating against the wrong data should fail
            final Bytes wrongData = randotron.nextHashBytes();
            assertFalse(CryptoStatic.verifySignature(wrongData, signature.getBytes(), signaturePublicKey));

            // Sanity check: validating with a modified signature should fail
            final byte[] modifiedSignature = signature.getBytes().toByteArray();
            modifiedSignature[0] = (byte) ~modifiedSignature[0];
            assertFalse(CryptoStatic.verifySignature(dataBytes, Bytes.wrap(modifiedSignature), signaturePublicKey));
        }
    }
}
