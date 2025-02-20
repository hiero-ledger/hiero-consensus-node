// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils;

import static com.hedera.services.bdd.suites.utils.MiscEETUtils.genRandomBytes;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import java.math.BigInteger;

public class ECDSAKeysUtils {
    private ECDSAKeysUtils() {}

    public static byte[] getEvmAddressFromString(HapiSpecRegistry registry, String keyName) {
        return registry.getKey(keyName).getECDSASecp256K1().toByteArray();
    }

    public static Address randomHeadlongAddress() {
        return Address.wrap(Address.toChecksumAddress(new BigInteger(1, genRandomBytes(20))));
    }
}
