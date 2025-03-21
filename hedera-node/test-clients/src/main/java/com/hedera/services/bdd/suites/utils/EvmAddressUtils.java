// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils;

import static com.hedera.services.bdd.suites.utils.MiscEETUtils.genRandomBytes;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.node.app.hapi.utils.keys.Secp256k1Utils;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import java.math.BigInteger;

public class EvmAddressUtils {
    private EvmAddressUtils() {}

    public static byte[] getEvmAddressFromString(HapiSpecRegistry registry, String keyName) {
        final var key = registry.getKey(keyName);
        return Secp256k1Utils.getEvmAddressFromString(key);
    }

    public static Address randomHeadlongAddress() {
        return Address.wrap(Address.toChecksumAddress(new BigInteger(1, genRandomBytes(20))));
    }
}
