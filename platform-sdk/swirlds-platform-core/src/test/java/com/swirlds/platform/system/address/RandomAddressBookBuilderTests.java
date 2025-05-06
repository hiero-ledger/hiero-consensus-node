// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.address;

import static org.assertj.core.api.Fail.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.test.fixtures.addressbook.RandomAddressBookBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.PublicKey;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.Address;
import org.hiero.consensus.model.roster.AddressBook;
import org.junit.jupiter.api.Test;

class RandomAddressBookBuilderTests {
    private static final Cryptography CRYPTOGRAPHY = CryptographyProvider.getInstance();

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

        final RandomAddressBookBuilder builderA =
                RandomAddressBookBuilder.create(randotron).withSize(size).withRealKeysEnabled(true);
        final AddressBook addressBookA = builderA.build();

        final RandomAddressBookBuilder builderB = RandomAddressBookBuilder.create(randotron.copyAndReset())
                .withSize(size)
                .withRealKeysEnabled(true);
        final AddressBook addressBookB = builderB.build();

        // The address book should be the same (keys should be deterministic)
        CRYPTOGRAPHY.digestSync(addressBookA);
        CRYPTOGRAPHY.digestSync(addressBookB);
        assertEquals(addressBookA.getHash(), addressBookB.getHash());

        // Verify that each address has unique keys
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                if (i == j) {
                    continue;
                }

                final NodeId idI = addressBookA.getNodeId(i);
                final Address addressI = addressBookA.getAddress(idI);
                final PublicKey signaturePublicKeyI = addressI.getSigPublicKey();
                final PublicKey agreementPublicKeyI = addressI.getAgreePublicKey();

                final NodeId idJ = addressBookA.getNodeId(j);
                final Address addressJ = addressBookA.getAddress(idJ);
                final PublicKey signaturePublicKeyJ = addressJ.getSigPublicKey();
                final PublicKey agreementPublicKeyJ = addressJ.getAgreePublicKey();

                assertKeysAreUnique(signaturePublicKeyI, signaturePublicKeyJ);
                assertKeysAreUnique(agreementPublicKeyI, agreementPublicKeyJ);
            }
        }

        // Verify that the private key can produce valid signatures that can be verified by the public key
        for (int i = 0; i < size; i++) {
            final NodeId id = addressBookA.getNodeId(i);
            final Address address = addressBookA.getAddress(id);
            final PublicKey signaturePublicKey = address.getSigPublicKey();
            final KeysAndCerts privateKeys = builderA.getPrivateKeys(id);

            final byte[] dataArray = randotron.nextByteArray(64);
            final Bytes dataBytes = Bytes.wrap(dataArray);
            final Signature signature = new PlatformSigner(privateKeys).sign(dataArray);

            assertTrue(CryptoStatic.verifySignature(dataBytes, signature.getBytes(), signaturePublicKey));

            // Sanity check: validating using the wrong public key should fail
            final NodeId wrongId = addressBookA.getNodeId((i + 1) % size);
            final Address wrongAddress = addressBookA.getAddress(wrongId);
            final PublicKey wrongPublicKey = wrongAddress.getSigPublicKey();
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
