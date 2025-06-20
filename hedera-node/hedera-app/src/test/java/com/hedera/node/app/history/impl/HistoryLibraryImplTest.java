// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.cryptography.rpm.SigningAndVerifyingSchnorrKeys;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class HistoryLibraryImplTest {
    private static final HistoryLibraryImpl subject = new HistoryLibraryImpl();

    @Test
    void hasSnarkVerificationKey() {
        assertNotNull(subject.snarkVerificationKey());
    }

    @Test
    void generatesValidSchnorrKeys() {
        final var keys = subject.newSchnorrKeyPair();
        final var message = Bytes.wrap("Hello, world!".getBytes());
        final var signature = subject.signSchnorr(message, Bytes.wrap(keys.signingKey()));
        assertTrue(subject.verifySchnorr(signature, message, Bytes.wrap(keys.verifyingKey())));
    }

    @Test
    void hashesAddressBook() {
        final List<KeyPairAndWeight> addresses = buildSomeAddresses(3);
        final var publicKeyArray =
                addresses.stream().map(k -> k.keys.verifyingKey()).toArray(byte[][]::new);
        final var weights = addresses.stream()
                .map(KeyPairAndWeight::weight)
                .mapToLong(Long::longValue)
                .toArray();
        assertNotNull(subject.hashAddressBook(weights, publicKeyArray));
    }

    @Test
    @Disabled
    // This test works. But it is disabled because it takes 3 minutes to finish this test.
    // It is useful to debug locally
    void verifiesProofOfTrust() {
        final List<KeyPairAndWeight> sourceAddresses = buildSomeAddresses(3);
        final var sourceKeys =
                sourceAddresses.stream().map(k -> k.keys.verifyingKey()).toArray(byte[][]::new);
        final var sourceWeights = sourceAddresses.stream()
                .map(KeyPairAndWeight::weight)
                .mapToLong(Long::longValue)
                .toArray();

        final List<KeyPairAndWeight> targetAddresses = buildSomeAddresses(2);
        final var targetKeys =
                targetAddresses.stream().map(k -> k.keys.verifyingKey()).toArray(byte[][]::new);
        final var targetWeights = targetAddresses.stream()
                .map(KeyPairAndWeight::weight)
                .mapToLong(Long::longValue)
                .toArray();

        final Bytes genesisAddressBookHash = subject.hashAddressBook(sourceWeights, sourceKeys);
        final Bytes nextAddressBookHash = subject.hashAddressBook(targetWeights, targetKeys);
        final Bytes metadata = com.hedera.pbj.runtime.io.buffer.Bytes.wrap("test metadata");
        final var hashedMetadata = subject.hashHintsVerificationKey(metadata);
        final var message = concatMessages(nextAddressBookHash.toByteArray(), hashedMetadata.toByteArray());

        final Map<Long, Bytes> signatures = new LinkedHashMap<>();

        for (int i = 0; i < sourceAddresses.size(); i++) {
            final var entry = sourceAddresses.get(i);
            if (i == 0) {
                signatures.put(entry.weight(), null);
            } else {
                signatures.put(
                        entry.weight(), subject.signSchnorr(Bytes.wrap(message), Bytes.wrap(entry.keys.signingKey())));
            }
        }

        final var snarkProof = subject.proveChainOfTrust(
                genesisAddressBookHash,
                null,
                sourceWeights,
                sourceKeys,
                targetWeights,
                targetKeys,
                signatures.values().stream()
                        .map(b -> b == null ? null : b.toByteArray())
                        .toArray(byte[][]::new),
                hashedMetadata);
        assertNotNull(snarkProof);
    }

    public static byte[] concatMessages(final byte[] nextAddressBookHash, final byte[] hintsVerificationKeyHash) {
        final byte[] arr = new byte[nextAddressBookHash.length + hintsVerificationKeyHash.length];
        System.arraycopy(nextAddressBookHash, 0, arr, 0, nextAddressBookHash.length);
        System.arraycopy(hintsVerificationKeyHash, 0, arr, nextAddressBookHash.length, hintsVerificationKeyHash.length);
        return arr;
    }

    private record KeyPairAndWeight(SigningAndVerifyingSchnorrKeys keys, long weight) {}

    private KeyPairAndWeight fromRandom(long weight) {
        final SigningAndVerifyingSchnorrKeys keys = subject.newSchnorrKeyPair();
        return new KeyPairAndWeight(keys, weight);
    }

    private List<KeyPairAndWeight> buildSomeAddresses(final int num) {
        return List.of(fromRandom(111), fromRandom(222), fromRandom(333), fromRandom(444))
                .subList(0, num);
    }
}
