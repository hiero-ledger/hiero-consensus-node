// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import java.security.PublicKey;

public enum KeyType {
    EC(1, "SunEC"),
    RSA(2, "BC");

    private final int algorithmIdentifier;
    private final String provider;

    KeyType(int algorithmIdentifier, String provider) {
        this.algorithmIdentifier = algorithmIdentifier;
        this.provider = provider;
    }

    String getAlgorithmName() {
        return name();
    }

    public int getAlgorithmIdentifier() {
        return algorithmIdentifier;
    }

    public String getProvider() {
        return provider;
    }

    static KeyType getKeyType(int algorithmIdentifier) {
        switch (algorithmIdentifier) {
            case 1:
                return EC;
            case 2:
                return RSA;
        }
        return null;
    }

    static KeyType getKeyType(PublicKey key) {
        switch (key.getAlgorithm()) {
            case "EC":
                return KeyType.EC;
            case "RSA":
                return KeyType.RSA;
        }
        throw new IllegalArgumentException(key.getAlgorithm() + " is not a known key type!");
    }
}
