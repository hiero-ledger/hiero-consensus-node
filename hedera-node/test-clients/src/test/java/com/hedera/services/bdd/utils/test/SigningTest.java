// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.utils.test;

// SPDX-License-Identifier: Apache-2.0
import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType.LEGACY_ETHEREUM;
import static com.hedera.services.bdd.utils.test.TestingConstants.CHAINID_TESTNET;
import static com.hedera.services.bdd.utils.test.TestingConstants.TINYBARS_2_IN_WEIBARS;
import static com.hedera.services.bdd.utils.test.TestingConstants.TINYBARS_57_IN_WEIBARS;
import static com.hedera.services.bdd.utils.test.TestingConstants.TRUFFLE0_ADDRESS;
import static com.hedera.services.bdd.utils.test.TestingConstants.TRUFFLE0_PRIVATE_ECDSA_KEY;
import static com.hedera.services.bdd.utils.test.TestingConstants.TRUFFLE0_PUBLIC_ECDSA_KEY;
import static com.hedera.services.bdd.utils.test.TestingConstants.TRUFFLE1_ADDRESS;
import static com.hedera.services.bdd.utils.test.TestingConstants.TRUFFLE1_PRIVATE_ECDSA_KEY;
import static com.hedera.services.bdd.utils.test.TestingConstants.ZERO_BYTES;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.hapi.utils.ethereum.EthTxSigs;
import com.hedera.services.bdd.utils.Signing;
import java.math.BigInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SigningTest {

    @Test
    void signsLegacyUnprotectedNull() {
        final var tx = new EthTxData(
                null,
                LEGACY_ETHEREUM,
                null,
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
                new byte[0],
                new byte[0]);

        final EthTxData signedTx = Signing.signMessage(tx, TRUFFLE0_PRIVATE_ECDSA_KEY);

        assertArrayEquals(null, signedTx.chainId());
        assertArrayEquals(new byte[] {27}, signedTx.v());
        assertNotNull(tx.r());
        assertNotNull(tx.s());
    }

    @Test
    void signsLegacyUnprotectedZeroChainId() {
        final var tx = new EthTxData(
                null,
                LEGACY_ETHEREUM,
                ZERO_BYTES,
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
                new byte[0],
                new byte[0]);

        final EthTxData signedTx = Signing.signMessage(tx, TRUFFLE0_PRIVATE_ECDSA_KEY);

        Assertions.assertArrayEquals(ZERO_BYTES, signedTx.chainId());
        assertArrayEquals(new byte[] {27}, signedTx.v());
        assertNotNull(tx.r());
        assertNotNull(tx.s());
    }

    @Test
    void signsEIP2930() {
        final var tx = new EthTxData(
                null,
                EthTxData.EthTransactionType.EIP2930,
                ZERO_BYTES,
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
                new byte[0],
                new byte[0]);

        final EthTxData signedTx = Signing.signMessage(tx, TRUFFLE0_PRIVATE_ECDSA_KEY);

        assertNotNull(signedTx.r());
        assertNotNull(signedTx.s());
    }

    @Test
    void signsEIP1559() {
        final var tx = new EthTxData(
                null,
                EthTxData.EthTransactionType.EIP1559,
                ZERO_BYTES,
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
                new byte[0],
                new byte[0]);

        final EthTxData signedTx = Signing.signMessage(tx, TRUFFLE0_PRIVATE_ECDSA_KEY);

        assertNotNull(signedTx.r());
        assertNotNull(signedTx.s());
    }

    @Test
    void signsLegacyProtected() {
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
                new byte[0],
                new byte[0]);

        final EthTxData signedTx = Signing.signMessage(tx, TRUFFLE0_PRIVATE_ECDSA_KEY);

        Assertions.assertArrayEquals(CHAINID_TESTNET, signedTx.chainId());
        assertArrayEquals(new byte[] {2, 116}, signedTx.v());
    }

    @Test
    void extractAddress() {
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
                new byte[0],
                new byte[0]);

        final EthTxData signedTx = Signing.signMessage(tx, TRUFFLE0_PRIVATE_ECDSA_KEY);

        final EthTxSigs sigs = EthTxSigs.extractSignatures(signedTx);

        Assertions.assertArrayEquals(TRUFFLE0_ADDRESS, sigs.address());
        Assertions.assertArrayEquals(TRUFFLE0_PUBLIC_ECDSA_KEY, sigs.publicKey());
    }

    @Test
    void equalsToStringHashCode() {
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
                new byte[0],
                new byte[0]);

        final EthTxData signedTx = Signing.signMessage(tx, TRUFFLE0_PRIVATE_ECDSA_KEY);
        final EthTxData signedTxAgain = Signing.signMessage(tx, TRUFFLE0_PRIVATE_ECDSA_KEY);
        final EthTxData signedTx1 = Signing.signMessage(tx, TRUFFLE1_PRIVATE_ECDSA_KEY);

        final EthTxSigs sigs = EthTxSigs.extractSignatures(signedTx);
        final EthTxSigs sigsAgain = EthTxSigs.extractSignatures(signedTxAgain);
        final EthTxSigs sigs1 = EthTxSigs.extractSignatures(signedTx1);

        assertEquals(sigs.toString(), sigsAgain.toString());
        assertNotEquals(sigs.toString(), sigs1.toString());

        assertDoesNotThrow(sigs::hashCode);
        assertDoesNotThrow(sigsAgain::hashCode);
        assertDoesNotThrow(sigs1::hashCode);

        assertEquals(sigs, sigsAgain);
        assertNotEquals(sigs, sigs1);
    }
}
