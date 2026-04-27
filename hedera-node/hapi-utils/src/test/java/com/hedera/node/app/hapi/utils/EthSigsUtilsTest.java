// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import static com.hedera.node.app.hapi.utils.ethereum.TestingConstants.TRUFFLE0_PRIVATE_ECDSA_KEY;
import static com.hedera.node.app.hapi.utils.ethereum.TestingConstants.TRUFFLE0_PUBLIC_ECDSA_KEY;
import static com.hedera.node.app.hapi.utils.ethereum.TestingConstants.TRUFFLE0_ADDRESS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.hedera.node.app.hapi.utils.ethereum.BouncyCastleSecp256k1Support;
import org.junit.jupiter.api.Test;

class EthSigsUtilsTest {
    @Test
    void extractsAddress() {
        // good recovery
        assertArrayEquals(TRUFFLE0_ADDRESS, EthSigsUtils.recoverAddressFromPubKey(TRUFFLE0_PUBLIC_ECDSA_KEY));

        // failed recovery
        assertArrayEquals(new byte[0], EthSigsUtils.recoverAddressFromPubKey(TRUFFLE0_PRIVATE_ECDSA_KEY));
    }

    @Test
    void matchesPureBouncyCastleForKnownAddressRecovery() {
        assertArrayEquals(
                BouncyCastleSecp256k1Support.recoverAddressFromCompressedPublicKey(TRUFFLE0_PUBLIC_ECDSA_KEY),
                EthSigsUtils.recoverAddressFromPubKey(TRUFFLE0_PUBLIC_ECDSA_KEY));
        assertArrayEquals(
                BouncyCastleSecp256k1Support.recoverAddressFromCompressedPublicKey(TRUFFLE0_PUBLIC_ECDSA_KEY),
                EthSigsUtils.recoverAddressFromPrivateKey(TRUFFLE0_PRIVATE_ECDSA_KEY));
    }
}
