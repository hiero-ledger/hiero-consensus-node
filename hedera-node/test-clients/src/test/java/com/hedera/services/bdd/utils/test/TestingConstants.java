// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.utils.test;

import static org.hiero.base.utility.CommonUtils.unhex;

import java.math.BigInteger;

/**
 * Useful constants for unit tests
 *
 * Copied from `com.hedera.node.app.hapi.utils.ethereum.TestingConstants`
 */
public class TestingConstants {
    static final byte[] ZERO_BYTES = new byte[0];

    static final byte[] TRUFFLE0_PRIVATE_ECDSA_KEY =
            unhex("c87509a1c067bbde78beb793e6fa76530b6382a4c0241e5e4a9ec0a0f44dc0d3");
    static final byte[] TRUFFLE0_PUBLIC_ECDSA_KEY =
            unhex("03af80b90d25145da28c583359beb47b21796b2fe1a23c1511e443e7a64dfdb27d");
    static final byte[] TRUFFLE0_ADDRESS = unhex("627306090abaB3A6e1400e9345bC60c78a8BEf57");

    static final byte[] TRUFFLE1_PRIVATE_ECDSA_KEY =
            unhex("ae6ae8e5ccbfb04590405997ee2d52d2b330726137b875053c36d94e974d162f");
    static final byte[] TRUFFLE1_ADDRESS = unhex("f17f52151EbEF6C7334FAD080c5704D77216b732");

    static final byte[] CHAINID_TESTNET = unhex("0128");

    static final BigInteger WEIBARS_IN_TINYBAR = BigInteger.valueOf(10_000_000_000L);

    static final byte[] TINYBARS_57_IN_WEIBARS =
            BigInteger.valueOf(57).multiply(WEIBARS_IN_TINYBAR).toByteArray();
    static final byte[] TINYBARS_2_IN_WEIBARS =
            BigInteger.valueOf(2).multiply(WEIBARS_IN_TINYBAR).toByteArray();

    private TestingConstants() {
        throw new UnsupportedOperationException("Utility Class");
    }
}
