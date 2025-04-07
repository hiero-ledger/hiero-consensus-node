// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import static org.hiero.consensus.model.utility.CommonUtils.unhex;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class EthSigsUtilsTest {
    private static final byte[] TRUFFLE0_PRIVATE_ECDSA_KEY =
            unhex("c87509a1c067bbde78beb793e6fa76530b6382a4c0241e5e4a9ec0a0f44dc0d3");
    private static final byte[] TRUFFLE0_PUBLIC_ECDSA_KEY =
            unhex("03af80b90d25145da28c583359beb47b21796b2fe1a23c1511e443e7a64dfdb27d");
    private static final byte[] TRUFFLE0_ADDRESS = unhex("627306090abaB3A6e1400e9345bC60c78a8BEf57");

    @Test
    void extractsAddress() {
        // good recovery
        assertArrayEquals(TRUFFLE0_ADDRESS, EthSigsUtils.recoverAddressFromPubKey(TRUFFLE0_PUBLIC_ECDSA_KEY));

        // failed recovery
        assertArrayEquals(new byte[0], EthSigsUtils.recoverAddressFromPubKey(TRUFFLE0_PRIVATE_ECDSA_KEY));
    }
}
