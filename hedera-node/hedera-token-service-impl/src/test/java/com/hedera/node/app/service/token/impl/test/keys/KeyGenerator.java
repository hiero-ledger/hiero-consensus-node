// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.keys;

import com.hederahashgraph.api.proto.java.Key;
import java.security.PrivateKey;
import java.util.Map;

public interface KeyGenerator {
    Key genEd25519AndUpdateMap(Map<String, PrivateKey> publicToPrivateKey);

    Key genEcdsaSecp256k1AndUpdateMap(Map<String, PrivateKey> publicToPrivateKey);
}
