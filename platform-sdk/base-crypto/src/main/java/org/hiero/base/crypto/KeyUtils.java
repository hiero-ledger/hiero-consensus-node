// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;

public class KeyUtils {
    public static KeyPair generateKeyPair(KeyType keyType, int keySize, SecureRandom secureRandom) {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(keyType.getAlgorithmName(), keyType.getProvider());
            keyGen.initialize(keySize, secureRandom);
            return keyGen.generateKeyPair();
        } catch (Exception e) {
            // should never happen
            throw new RuntimeException(e);
        }
    }
}
