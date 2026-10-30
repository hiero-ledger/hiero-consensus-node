// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.ethereum;

import static com.hedera.node.app.hapi.utils.ethereum.CodeDelegationTest.fillBytes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.esaulpaugh.headlong.rlp.RLPEncoder;
import com.esaulpaugh.headlong.util.Integers;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import java.math.BigInteger;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class EthTxDataType4TransactionTest {

    @Test
    void testType4TransactionEncoding() {
        final byte[] chainId = {0x01, 0x2A};
        final int nonce = 7;
        final byte[] maxPriorityGas = fillBytes(3, 0x11);
        final byte[] maxGas = fillBytes(3, 0x21);
        final int gasLimit = 123456;
        final byte[] to = fillBytes(20, 0x33);
        final long value = 987_654L;
        final byte[] callData = fillBytes(4, 0x44);

        final byte[] addr1 = fillBytes(20, 0x50);
        final byte[] addr2 = fillBytes(20, 0x60);
        final byte[] key1 = fillBytes(32, 0x70);
        final byte[] key2 = fillBytes(32, 0x80);
        final Object[] accessListList = {
            new Object[] {addr1, new Object[] {key1, key2}},
            new Object[] {addr2, new Object[] {}}
        };

        final byte[] expectedAccessListBytes = RLPEncoder.sequence(accessListList);

        final byte[] authChainId = {0x01, 0x2A};
        final byte[] authAddress = fillBytes(20, 0x99);
        final int authNonce = 3;
        final int authYParity = 1;
        final byte[] authR = fillBytes(32, 0xB0);
        final byte[] authS = fillBytes(32, 0xC0);
        final Object[] authorizationEntry = {
            authChainId, authAddress, Integers.toBytes(authNonce), Integers.toBytes(authYParity), authR, authS
        };
        final Object[] authorizationList = {authorizationEntry};
        final byte[] expectedAuthorizationListBytes = RLPEncoder.sequence(authorizationList);

        final int recId = 27;
        final byte[] r = fillBytes(32, 0x90);
        final byte[] s = fillBytes(32, 0xA0);

        final byte[] raw = RLPEncoder.sequence(Integers.toBytes(4), new Object[] {
            chainId, // 0
            Integers.toBytes(nonce), // 1
            maxPriorityGas, // 2
            maxGas, // 3
            Integers.toBytes(gasLimit), // 4
            to, // 5
            Integers.toBytesUnsigned(BigInteger.valueOf(value)), // 6
            callData, // 7
            accessListList, // 8 (list)
            authorizationList, // 9 (list)
            new byte[] {(byte) recId}, // 10
            r, // 11
            s // 12
        });

        final EthTxData tx = EthTxData.populateEthTxData(raw);
        assertNotNull(tx);
        assertEquals(EthTransactionType.EIP7702, tx.type());

        assertArrayEquals(chainId, tx.chainId());
        assertEquals(nonce, tx.nonce());
        assertNull(tx.gasPrice());
        assertArrayEquals(maxPriorityGas, tx.maxPriorityGas());
        assertArrayEquals(maxGas, tx.maxGas());
        assertEquals(gasLimit, tx.gasLimit());
        assertArrayEquals(to, tx.to());
        assertEquals(value, tx.value().longValue());
        assertArrayEquals(callData, tx.callData());

        assertArrayEquals(expectedAccessListBytes, tx.accessList());

        final Object[] alRlp = tx.accessListAsRlp();
        assertNotNull(alRlp);
        assertEquals(2, alRlp.length);

        final Object[] firstEntry = (Object[]) alRrpEntry(alRlp, 0);
        assertArrayEquals(addr1, (byte[]) firstEntry[0]);
        final Object[] firstKeys = (Object[]) firstEntry[1];
        assertEquals(2, firstKeys.length);
        assertArrayEquals(key1, (byte[]) firstKeys[0]);
        assertArrayEquals(key2, (byte[]) firstKeys[1]);

        assertArrayEquals(expectedAuthorizationListBytes, tx.authorizationList());
        assertEquals(recId, tx.recId());
        assertArrayEquals(r, tx.r());
        assertArrayEquals(s, tx.s());
    }

    @Test
    void rejectsAuthorizationListThatIsNotAList() {
        // Encodes to a plain RLP byte-string, not a list - malformed regardless of content.
        final byte[] auth = {0x01};

        final byte[] raw = buildType4Raw(
                fillBytes(2, 0x01),
                1,
                fillBytes(3, 0x02),
                fillBytes(3, 0x03),
                100,
                fillBytes(20, 0x04),
                0L,
                new byte[] {},
                new Object[] {},
                auth,
                27,
                fillBytes(32, 0x05),
                fillBytes(32, 0x06));

        // The transaction should be rejected at parse time rather than silently accepted with a
        // malformed authorization list.
        final EthTxData tx = EthTxData.populateEthTxData(raw);
        assertNull(tx);
    }

    @Test
    void rejectsAuthorizationListThatIsEmptyButNotACanonicalList() {
        // Encodes to the RLP empty byte-string token 0x80, not the canonical empty list token 0xc0.
        final byte[] authorizationListIsEmptyByteString = new byte[0];

        final byte[] raw = buildType4Raw(
                fillBytes(2, 0x01),
                1,
                fillBytes(3, 0x02),
                fillBytes(3, 0x03),
                100,
                fillBytes(20, 0x04),
                0L,
                new byte[] {},
                new Object[] {}, // canonical empty access list
                authorizationListIsEmptyByteString,
                27,
                fillBytes(32, 0x05),
                fillBytes(32, 0x06));

        // The transaction should be rejected at parse time rather than silently accepted with an
        // empty authorization list.
        final EthTxData tx = EthTxData.populateEthTxData(raw);
        assertNull(tx);
    }

    @Test
    void extractCodeDelegationsThrowsWhenInnerItemNotList() {
        // Authorization list has one entry, and that entry is a single-element list (wrong element
        // count - a genuine authorization entry must have exactly 6 elements).
        final Object[] authorizationList = {new Object[] {new byte[] {0x01}}};

        final byte[] raw = buildType4Raw(
                fillBytes(2, 0x0A),
                2,
                fillBytes(3, 0x0B),
                fillBytes(3, 0x0C),
                200,
                fillBytes(20, 0x0D),
                0L,
                new byte[] {},
                new Object[] {},
                authorizationList,
                28,
                fillBytes(32, 0x0E),
                fillBytes(32, 0x0F));

        final EthTxData tx = EthTxData.populateEthTxData(raw);
        assertNotNull(tx);

        final var thrown = assertThrows(IllegalArgumentException.class, tx::extractCodeDelegations);
        assertEquals("Authorization list item does not contain expected number of elements", thrown.getMessage());
    }

    @Test
    void extractCodeDelegationsThrowsWhenTopLevelListSizeNotSix() {
        // Authorization list has one entry, and that entry is a single-element list (wrong element
        // count - a genuine authorization entry must have exactly 6 elements).
        final Object[] authorizationList = {new Object[] {new Object[] {}}};

        final byte[] raw = buildType4Raw(
                fillBytes(2, 0x1A),
                3,
                fillBytes(3, 0x1B),
                fillBytes(3, 0x1C),
                300,
                fillBytes(20, 0x1D),
                0L,
                new byte[] {},
                new Object[] {},
                authorizationList,
                29,
                fillBytes(32, 0x1E),
                fillBytes(32, 0x1F));

        final EthTxData tx = EthTxData.populateEthTxData(raw);
        assertNotNull(tx);

        final var thrown = assertThrows(IllegalArgumentException.class, tx::extractCodeDelegations);
        assertEquals("Authorization list item does not contain expected number of elements", thrown.getMessage());
    }

    private static byte[] buildType4Raw(
            final byte[] chainId,
            final int nonce,
            final byte[] maxPriorityGas,
            final byte[] maxGas,
            final int gasLimit,
            final byte[] to,
            final long value,
            final byte[] callData,
            final Object[] accessListList,
            final Object authorizationList,
            final int recId,
            final byte[] r,
            final byte[] s) {
        return RLPEncoder.sequence(Integers.toBytes(4), new Object[] {
            chainId,
            Integers.toBytes(nonce),
            maxPriorityGas,
            maxGas,
            Integers.toBytes(gasLimit),
            to,
            Integers.toBytesUnsigned(BigInteger.valueOf(value)),
            callData,
            accessListList,
            authorizationList,
            new byte[] {(byte) recId},
            r,
            s
        });
    }

    private static Object alRrpEntry(final Object[] alRlp, final int index) {
        final Object entry = alRlp[index];
        assertInstanceOf(Object[].class, entry, "access list entry must be a list");
        return entry;
    }

    @Test
    void extractCodeDelegationsThrowsWhenAddressIsNotTwentyBytes() {
        final byte[] chainId = {0x01};
        final byte[] address = repeat((byte) 0x11, 17);
        final int nonce = 5;
        final int yParity = 1;
        final byte[] r = repeat((byte) 0x22, 32);
        final byte[] s = repeat((byte) 0x33, 32);

        final Object[] authorizationList = {
            new Object[] {chainId, address, Integers.toBytes(nonce), Integers.toBytes(yParity), r, s}
        };

        final byte[] raw = buildType4Raw(
                fillBytes(2, 0x01),
                1,
                fillBytes(3, 0x02),
                fillBytes(3, 0x03),
                100,
                fillBytes(20, 0x04),
                0L,
                new byte[] {},
                new Object[] {},
                authorizationList,
                27,
                fillBytes(32, 0x05),
                fillBytes(32, 0x06));

        final EthTxData tx = EthTxData.populateEthTxData(raw);
        assertNotNull(tx);

        final var thrown = assertThrows(IllegalArgumentException.class, tx::extractCodeDelegations);
        assertEquals("Authorization list item address is not 20 bytes length", thrown.getMessage());
    }

    @Test
    void extractCodeDelegationsWithValidAuthorizationListYieldsOneDelegation() {
        final byte[] chainId = {0x01};
        final byte[] address = repeat((byte) 0x11, 20);
        final int nonce = 5;
        final int yParity = 1;
        final byte[] r = repeat((byte) 0x22, 32);
        final byte[] s = repeat((byte) 0x33, 32);

        final Object[] authorizationList = {
            new Object[] {chainId, address, Integers.toBytes(nonce), Integers.toBytes(yParity), r, s}
        };

        final byte[] raw = buildType4Raw(
                fillBytes(2, 0x01),
                1,
                fillBytes(3, 0x02),
                fillBytes(3, 0x03),
                100,
                fillBytes(20, 0x04),
                0L,
                new byte[] {},
                new Object[] {},
                authorizationList,
                27,
                fillBytes(32, 0x05),
                fillBytes(32, 0x06));

        final EthTxData tx = EthTxData.populateEthTxData(raw);

        // Assert
        assertNotNull(tx);
        final var delegations = tx.extractCodeDelegations();
        assertNotNull(delegations);
        assertEquals(1, delegations.size());

        final CodeDelegation cd = delegations.getFirst();
        assertArrayEquals(chainId, cd.chainId());
        assertArrayEquals(address, cd.address());
        assertEquals(nonce, cd.nonce());
        assertEquals((byte) yParity, cd.yParity());
        assertArrayEquals(r, cd.r());
        assertArrayEquals(s, cd.s());
    }

    @Test
    void populateEip7702EthTxDataReturnsNullWhenItemIsNotList() {
        byte[] chainId = {0x01};
        final var raw = RLPEncoder.sequence(Integers.toBytes(4), chainId);

        final EthTxData tx = EthTxData.populateEthTxData(raw);

        assertNull(tx);
    }

    @Test
    void populateEip7702EthTxDataReturnsNullWhenWrongNumberOfItemsInList() {
        byte[] chainId = {0x01};
        final var raw = RLPEncoder.sequence(Integers.toBytes(4), new Object[] {chainId});

        final EthTxData tx = EthTxData.populateEthTxData(raw);

        assertNull(tx);
    }

    /// EIP-2718 requires the envelope to consume its whole input. Bytes past its end would leave every field
    /// intact while changing `keccak256(rawTx)` - the value externalized as a record's `ethereum_hash` - so
    /// they must be rejected rather than left unread. See `EthTxDataTest.rejectsTrailingBytesAfterEnvelope`
    /// for the legacy, type-1 and type-2 equivalents.
    @Test
    void populateEip7702EthTxDataReturnsNullWhenTrailingBytesFollowEnvelope() {
        final Object[] authorizationList = {
            new Object[] {
                new byte[] {0x01},
                repeat((byte) 0x11, 20),
                Integers.toBytes(5),
                Integers.toBytes(1),
                repeat((byte) 0x22, 32),
                repeat((byte) 0x33, 32)
            }
        };
        final byte[] canonical = buildType4Raw(
                fillBytes(2, 0x01),
                1,
                fillBytes(3, 0x02),
                fillBytes(3, 0x03),
                100,
                fillBytes(20, 0x04),
                0L,
                new byte[] {},
                new Object[] {},
                authorizationList,
                27,
                fillBytes(32, 0x05),
                fillBytes(32, 0x06));

        assertNotNull(EthTxData.populateEthTxData(canonical));

        for (final byte[] suffix : new byte[][] {{0x00}, {(byte) 0x80}, {(byte) 0xc0}, {(byte) 0xff}}) {
            final var suffixed = Arrays.copyOf(canonical, canonical.length + suffix.length);
            System.arraycopy(suffix, 0, suffixed, canonical.length, suffix.length);
            assertNull(EthTxData.populateEthTxData(suffixed), "trailing byte must be rejected");
        }
    }

    private static byte[] repeat(byte b, int n) {
        byte[] out = new byte[n];
        Arrays.fill(out, b);
        return out;
    }
}
