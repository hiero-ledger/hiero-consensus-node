// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.swirlds.common.crypto.internal.CryptoUtils;
import com.swirlds.common.test.fixtures.io.InputOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SerializablePublicKeyTests {

    static Stream<Arguments> keyTypeProvider() {
        return Stream.of(arguments("RSA", 3072, false), arguments("EC", 384, false));
    }

    @ParameterizedTest
    @MethodSource("keyTypeProvider")
    void serializeDeserialize(String keyType, int keySize, boolean writeClassId)
            throws NoSuchAlgorithmException, NoSuchProviderException, IOException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance(keyType);
        keyGen.initialize(keySize, CryptoUtils.getDetRandom());
        KeyPair keyPair = keyGen.generateKeyPair();

        SerializablePublicKey original = new SerializablePublicKey(keyPair.getPublic());
        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeSerializable(original, writeClassId);
        io.startReading();
        SerializablePublicKey copy = io.getInput().readSerializable(writeClassId, SerializablePublicKey::new);

        assertEquals(original, copy);
        io.close();
    }
}
