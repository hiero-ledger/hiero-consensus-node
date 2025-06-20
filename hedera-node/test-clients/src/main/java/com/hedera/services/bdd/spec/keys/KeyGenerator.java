// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.keys;

import com.hederahashgraph.api.proto.java.Key;
import java.security.PrivateKey;
import java.util.Map;

@FunctionalInterface
public interface KeyGenerator {
    enum Nature {
        RANDOMIZED,
        WITH_OVERLAPPING_PREFIXES
    }

    Key genEd25519AndUpdateMap(Map<String, PrivateKey> mutablePkMap);

    default Key genEcdsaSecp256k1AndUpdate(final Map<String, PrivateKey> mutablePkMap) {
        throw new UnsupportedOperationException();
    }
}
