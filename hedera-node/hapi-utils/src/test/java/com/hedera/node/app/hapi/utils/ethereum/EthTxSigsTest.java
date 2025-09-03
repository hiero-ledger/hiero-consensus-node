// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.ethereum;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType.LEGACY_ETHEREUM;
import static com.hedera.node.app.hapi.utils.ethereum.TestingConstants.CHAINID_TESTNET;
import static com.hedera.node.app.hapi.utils.ethereum.TestingConstants.TINYBARS_2_IN_WEIBARS;
import static com.hedera.node.app.hapi.utils.ethereum.TestingConstants.TINYBARS_57_IN_WEIBARS;
import static com.hedera.node.app.hapi.utils.ethereum.TestingConstants.TRUFFLE0_ADDRESS;
import static com.hedera.node.app.hapi.utils.ethereum.TestingConstants.TRUFFLE0_PRIVATE_ECDSA_KEY;
import static com.hedera.node.app.hapi.utils.ethereum.TestingConstants.TRUFFLE0_PUBLIC_ECDSA_KEY;
import static com.hedera.node.app.hapi.utils.ethereum.TestingConstants.TRUFFLE1_ADDRESS;
import static com.hedera.node.app.hapi.utils.ethereum.TestingConstants.ZERO_BYTES;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.SplittableRandom;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class EthTxSigsTest {
    private final SplittableRandom random = new SplittableRandom();

    @Test
    void leftPadsRIfNecessary() {
        final var r = nextBytes(30);
        final var s = nextBytes(32);
        final var sig = EthTxSigs.concatLeftPadded(r, s);
        assertArrayEquals(r, Arrays.copyOfRange(sig, 2, 32));
        assertArrayEquals(s, Arrays.copyOfRange(sig, 32, 64));
    }

    @Test
    void issue4180CaseStudyPasses() {
        final var expectedFromAddress = CommonUtils.unhex("5052672db37ad6f222b8de61665c6bb76acfefaa");
        final var ethTxData = EthTxData.populateEthTxData(
                CommonUtils.unhex(
                        "f88b718601d1a94a20008316e360940000000000000000000000000000000002e8a7b980a4fdacd5760000000000000000000000000000000000000000000000000000000000000002820273a076398dfd239dcdf69aeef7328a5e8cc69ef1b4ba5cca56eab1af06d7959923599f8194cd217b301cbdbdcd05b3572c411ec9333af39c98af8c5c9de45ddb05c5"));
        final var ethTxSigs = EthTxSigs.extractSignatures(ethTxData);
        assertArrayEquals(expectedFromAddress, ethTxSigs.address());
    }

    @Test
    void leftPadsSIfNecessary() {
        final var r = nextBytes(32);
        final var s = nextBytes(29);
        final var sig = EthTxSigs.concatLeftPadded(r, s);
        assertArrayEquals(r, Arrays.copyOfRange(sig, 0, 32));
        assertArrayEquals(s, Arrays.copyOfRange(sig, 35, 64));
    }

    @Test
    void leftPadsBothIfNecessary() {
        final var r = nextBytes(31);
        final var s = nextBytes(29);
        final var sig = EthTxSigs.concatLeftPadded(r, s);
        assertArrayEquals(r, Arrays.copyOfRange(sig, 1, 32));
        assertArrayEquals(s, Arrays.copyOfRange(sig, 35, 64));
    }

    @Test
    void leftPadsNeitherIfUnnecessary() {
        final var r = nextBytes(32);
        final var s = nextBytes(32);
        final var sig = EthTxSigs.concatLeftPadded(r, s);
        assertArrayEquals(r, Arrays.copyOfRange(sig, 0, 32));
        assertArrayEquals(s, Arrays.copyOfRange(sig, 32, 64));
    }

    public byte[] nextBytes(final int n) {
        final var ans = new byte[n];
        random.nextBytes(ans);
        return ans;
    }

    @Test
    void badSignatureVerification() {
        final byte[] allFs = new byte[32];
        Arrays.fill(allFs, (byte) -1);

        final var tx = new EthTxData(
                null,
                LEGACY_ETHEREUM,
                CHAINID_TESTNET,
                1,
                TINYBARS_57_IN_WEIBARS,
                TINYBARS_2_IN_WEIBARS,
                TINYBARS_57_IN_WEIBARS,
                1_000_000L,
                TRUFFLE1_ADDRESS,
                BigInteger.ZERO,
                ZERO_BYTES,
                ZERO_BYTES,
                null,
                3,
                new byte[0],
                allFs,
                allFs);

        assertThrows(IllegalArgumentException.class, () -> EthTxSigs.extractSignatures(tx));
    }

    @Test
    void badSignatureExtract() {
        final var tx = new EthTxData(
                null,
                LEGACY_ETHEREUM,
                CHAINID_TESTNET,
                1,
                TINYBARS_57_IN_WEIBARS,
                TINYBARS_2_IN_WEIBARS,
                TINYBARS_57_IN_WEIBARS,
                1_000_000L,
                TRUFFLE1_ADDRESS,
                BigInteger.ZERO,
                ZERO_BYTES,
                ZERO_BYTES,
                null,
                1,
                new byte[0],
                new byte[32],
                new byte[32]);

        assertThrows(IllegalArgumentException.class, () -> EthTxSigs.extractSignatures(tx));
    }

    @Test
    void extractsAddress() {
        // good recovery
        Assertions.assertArrayEquals(TRUFFLE0_ADDRESS, recoverAddressFromPubKey(TRUFFLE0_PUBLIC_ECDSA_KEY));

        // failed recovery
        assertArrayEquals(new byte[0], recoverAddressFromPubKey(TRUFFLE0_PRIVATE_ECDSA_KEY));
    }

    @Test
    void isAddressEqualForTransactionWithAccessList() {
        // based on transaction from Sepolia
        // https://sepolia.etherscan.io/tx/0xcadccd1934c0fda481414a756cd227cf87a215444d11f3f38c1186cce7a98235
        final var expectedFromAddress = CommonUtils.unhex("eA1B261FB7Ec1C4F2BEeA2476f17017537b4B507");
        final var ethTxData = EthTxData.populateEthTxData(CommonUtils.unhex(
                "02f8cb83aa36a781d6843b9aca00843b9aca0e82653394bdf6a09235fa130c5e5ddb60a3c06852e794347580a42e64cec10000000000000000000000000000000000000000000000000000000000000000f838f794bdf6a09235fa130c5e5ddb60a3c06852e7943475e1a0000000000000000000000000000000000000000000000000000000000000000001a0db915ded35296ff17f81c4e4075ba39a7cc6a0a1bf622eb969a578dad169d04aa03471b14e0f6ada15f1e5ab0eac0ed3c71dd3447ba98dc4669c82d2e406bb16be" // INPROPER
                ));
        final var ethTxSigs = EthTxSigs.extractSignatures(ethTxData);
        assertArrayEquals(expectedFromAddress, ethTxSigs.address());
    }

    @Test
    void isAddressEqualForTransactionWithoutAccessList() {
        // based on transaction from Sepolia
        // https://sepolia.etherscan.io/tx/0xd26abe8a34f53a7a2062bf7f8dd0c7218d9cf760fa6cca289eb06a5905894a98
        final var expectedFromAddress = CommonUtils.unhex("eA1B261FB7Ec1C4F2BEeA2476f17017537b4B507");
        final var ethTxData = EthTxData.populateEthTxData(CommonUtils.unhex(
                "02f89283aa36a781d5843b9aca00843b9aca0e825c3794bdf6a09235fa130c5e5ddb60a3c06852e794347580a42e64cec10000000000000000000000000000000000000000000000000000000000000000c080a09a3e200427a4d4eff9df54400d8a161b9439a0faae8d9d2a0b2275586011b3eba042defab332de10085042867558826d9bf16f8ff6e4a3c4f46640c9de16f927b0" // INPROPER
                ));
        final var ethTxSigs = EthTxSigs.extractSignatures(ethTxData);
        assertArrayEquals(expectedFromAddress, ethTxSigs.address());
    }
}
