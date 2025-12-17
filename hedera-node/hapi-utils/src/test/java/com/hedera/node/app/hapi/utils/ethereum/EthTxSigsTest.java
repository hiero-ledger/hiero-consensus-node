// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.ethereum;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.node.app.hapi.utils.ethereum.CodeDelegationTest.fillBytes;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.esaulpaugh.headlong.rlp.RLPEncoder;
import com.esaulpaugh.headlong.util.Integers;
import com.hedera.node.app.hapi.utils.EthSigsUtils;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Optional;
import java.util.SplittableRandom;
import org.hiero.base.utility.CommonUtils;
import org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

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
                null,
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
                null,
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

    @Test
    void extractAuthoritySignatureReturnsNullOnFailure() {
        final var chainId = new byte[] {0x01};
        final var address = fillBytes(20, 0x10);
        final var r = new byte[] {0x00}; // invalid (fails lower bound)
        final var s = new byte[] {0x01};

        final var cd = new CodeDelegation(chainId, address, 1L, 27, r, s);

        final Optional<EthTxSigs> result = EthTxSigs.extractAuthoritySignature(cd);
        assertTrue(result.isEmpty());
    }

    @Test
    void extractAuthoritySignatureWhenCalculateMessageThrows() {
        final var cd = mock(CodeDelegation.class, RETURNS_DEEP_STUBS);
        when(cd.calculateSignableMessage()).thenThrow(new RuntimeException("boom"));
        when(cd.yParity()).thenReturn(27);
        when(cd.r()).thenReturn(new byte[] {0x01});
        when(cd.s()).thenReturn(new byte[] {0x02});

        final Optional<EthTxSigs> result = EthTxSigs.extractAuthoritySignature(cd);
        assertTrue(result.isEmpty());
    }

    @Test
    void resolveEIP7702_encodesWithAccessList() {
        final var ethTx = mock(EthTxData.class);

        final var chainId = new byte[] {0x01, 0x2A};
        final long nonce = 9;
        final byte[] maxPriorityGas = fillBytes(4, 0x11);
        final byte[] maxGas = fillBytes(6, 0x33);
        final long gasLimit = 123456;
        final var to = fillBytes(20, 0x22);
        final long value = 987654321L;
        final var callData = fillBytes(3, 0x55);

        final Object[] accessList =
                new Object[] {new Object[] {fillBytes(20, 0x10), new Object[] {fillBytes(32, 0x01)}}};

        when(ethTx.chainId()).thenReturn(chainId);
        when(ethTx.nonce()).thenReturn(nonce);
        when(ethTx.maxPriorityGas()).thenReturn(maxPriorityGas);
        when(ethTx.maxGas()).thenReturn(maxGas);
        when(ethTx.gasLimit()).thenReturn(gasLimit);
        when(ethTx.to()).thenReturn(to);
        when(ethTx.value()).thenReturn(BigInteger.valueOf(value));
        when(ethTx.callData()).thenReturn(callData);
        when(ethTx.accessListAsRlp()).thenReturn(accessList);
        when(ethTx.authorizationListAsRlp()).thenReturn(authListAsObject);

        final byte[] actual = EthTxSigs.resolveEIP7702(ethTx);

        // Expected: authorizationList should be decoded and re-encoded as Object[] structure
        final byte[] expected = RLPEncoder.sequence(Integers.toBytes(4), new Object[] {
            chainId,
            Integers.toBytes(nonce),
            maxPriorityGas,
            maxGas,
            Integers.toBytes(gasLimit),
            to,
            Integers.toBytesUnsigned(BigInteger.valueOf(value)),
            callData,
            accessList,
            authListAsObject
        });

        assertArrayEquals(expected, actual);
    }

    @Test
    void resolveEIP7702_encodesEmptyAccessListWhenNull() {
        final var ethTx = mock(EthTxData.class);

        final var chainId = new byte[] {0x01};
        final long nonce = 1;
        final byte[] maxPriorityGas = fillBytes(2, 0x00);
        final byte[] maxGas = fillBytes(8, 0x02);
        final long gasLimit = 21000;
        final var to = fillBytes(20, 0x01);
        final long value = 1L;
        final var callData = new byte[0];

        when(ethTx.chainId()).thenReturn(chainId);
        when(ethTx.nonce()).thenReturn(nonce);
        when(ethTx.maxPriorityGas()).thenReturn(maxPriorityGas);
        when(ethTx.maxGas()).thenReturn(maxGas);
        when(ethTx.gasLimit()).thenReturn(gasLimit);
        when(ethTx.to()).thenReturn(to);
        when(ethTx.value()).thenReturn(BigInteger.valueOf(value));
        when(ethTx.callData()).thenReturn(callData);
        when(ethTx.accessListAsRlp()).thenReturn(null); // triggers empty list
        when(ethTx.authorizationListAsRlp()).thenReturn(authListAsObject);

        final byte[] actual = EthTxSigs.resolveEIP7702(ethTx);

        final byte[] expected = RLPEncoder.sequence(Integers.toBytes(4), new Object[] {
            chainId,
            Integers.toBytes(nonce),
            maxPriorityGas,
            maxGas,
            Integers.toBytes(gasLimit),
            to,
            Integers.toBytesUnsigned(BigInteger.valueOf(value)),
            callData,
            new Object[0], // empty access list
            authListAsObject
        });

        assertArrayEquals(expected, actual);
    }

    @Test
    void extractAuthoritySignatureHappyPath() {
        final byte yParity = (byte) 1;
        final byte[] r = new byte[32];
        final byte[] s = new byte[32];
        final byte[] message = new byte[] {0x01, 0x02, 0x03};

        final byte[] expectedCompressedKey = new byte[] {0x55, 0x66, 0x77};
        final byte[] expectedAddress = new byte[] {
            0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11,
            0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19,
            0x1A, 0x1B, 0x1C, 0x1D
        };

        final CodeDelegation codeDelegation = Mockito.mock(CodeDelegation.class);
        Mockito.when(codeDelegation.yParity()).thenReturn((int) yParity);
        Mockito.when(codeDelegation.r()).thenReturn(r);
        Mockito.when(codeDelegation.s()).thenReturn(s);
        Mockito.when(codeDelegation.calculateSignableMessage()).thenReturn(message);

        final LibSecp256k1.secp256k1_pubkey fakePubKey = Mockito.mock(LibSecp256k1.secp256k1_pubkey.class);

        try (MockedStatic<EthTxSigs> sigsMock = Mockito.mockStatic(EthTxSigs.class);
                MockedStatic<EthSigsUtils> addrMock = Mockito.mockStatic(EthSigsUtils.class)) {

            // Stub every helper used by extractAuthoritySignature to avoid native paths.
            sigsMock.when(() -> EthTxSigs.extractSig(
                            Mockito.anyByte(),
                            Mockito.any(byte[].class),
                            Mockito.any(byte[].class),
                            Mockito.any(byte[].class)))
                    .thenReturn(fakePubKey);

            sigsMock.when(() ->
                            EthTxSigs.serializeIntoCompressedKeyBytes(Mockito.any(LibSecp256k1.secp256k1_pubkey.class)))
                    .thenReturn(expectedCompressedKey);

            addrMock.when(() -> EthSigsUtils.recoverAddressFromPubKey(Mockito.any(LibSecp256k1.secp256k1_pubkey.class)))
                    .thenReturn(expectedAddress);

            sigsMock.when(() -> EthTxSigs.extractAuthoritySignature(codeDelegation))
                    .thenCallRealMethod();

            Optional<EthTxSigs> result = EthTxSigs.extractAuthoritySignature(codeDelegation);

            assertTrue(result.isPresent());
        }
    }

    private static final Object[] authListAsObject = new Object[] {
        new Object[] {
            fillBytes(2, 0x01), fillBytes(20, 0x22), new byte[0], new byte[0], fillBytes(32, 0x33), fillBytes(32, 0x44)
        }
    };
}
