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
}
