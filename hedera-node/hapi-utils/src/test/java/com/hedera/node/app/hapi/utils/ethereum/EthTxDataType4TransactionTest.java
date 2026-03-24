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
        final byte[] chainId = new byte[] {0x01, 0x2A};
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

        final byte[] authorizationList = fillBytes(5, 0x40);
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
            authorizationList, // 9
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

        assertArrayEquals(authorizationList, tx.authorizationList());
        assertEquals(recId, tx.recId());
        assertArrayEquals(r, tx.r());
        assertArrayEquals(s, tx.s());
    }

    @Test
    void returnsEmptyWhenAuthorizationTopLevelNotList() {
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

        final EthTxData tx = EthTxData.populateEthTxData(raw);
        assertNotNull(tx);

        final var thrown = assertThrows(IllegalArgumentException.class, tx::extractCodeDelegations);
        assertEquals("Authorization list item should be a list", thrown.getMessage());
    }

    @Test
    void extractCodeDelegationsThrowsWhenInnerItemNotList() {
        final byte[] auth = {(byte) 0xC1, 0x01};

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
                auth,
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
        final byte[] auth = {(byte) 0xC1, (byte) 0xC0};

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
                auth,
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
            final byte[] authorizationList,
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

    private static Object alRrpEntry(Object[] alRlp, int index) {
        final Object entry = alRlp[index];
        assertInstanceOf(Object[].class, entry, "access list entry must be a list");
        return entry;
    }

    @Test
    void extractCodeDelegationsWithValidAuthorizationListYieldsOneDelegation() {
        final byte[] chainId = new byte[] {0x01};
        final byte[] address = repeat((byte) 0x11, 20);
        final int nonce = 5;
        final int yParity = 1;
        final byte[] r = repeat((byte) 0x22, 32);
        final byte[] s = repeat((byte) 0x33, 32);

        final byte[] authorizationList = rlpList(
                rlpBytes(chainId), rlpBytes(address), rlpUInt(nonce), rlpUInt(yParity), rlpBytes(r), rlpBytes(s));

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

        CodeDelegation cd = delegations.getFirst();
        assertArrayEquals(chainId, cd.chainId());
        assertArrayEquals(address, cd.address());
        assertEquals(nonce, cd.nonce());
        assertEquals((byte) yParity, cd.yParity());
        assertArrayEquals(r, cd.r());
        assertArrayEquals(s, cd.s());
    }

    @Test
    void populateEip7702EthTxDataReturnsNullWhenItemIsNotList() {
        byte[] chainId = new byte[] {0x01};
        final var raw = RLPEncoder.sequence(Integers.toBytes(4), chainId);

        final EthTxData tx = EthTxData.populateEthTxData(raw);

        assertNull(tx);
    }

    @Test
    void populateEip7702EthTxDataReturnsNullWhenWrongNumberOfItemsInList() {
        byte[] chainId = new byte[] {0x01};
        final var raw = RLPEncoder.sequence(Integers.toBytes(4), new Object[] {chainId});

        final EthTxData tx = EthTxData.populateEthTxData(raw);

        assertNull(tx);
    }

    private static byte[] rlpBytes(byte[] bytes) {
        if (bytes.length == 1 && (bytes[0] & 0xFF) < 0x80) {
            return new byte[] {bytes[0]};
        }
        if (bytes.length <= 55) {
            byte[] out = new byte[1 + bytes.length];
            out[0] = (byte) (0x80 + bytes.length);
            System.arraycopy(bytes, 0, out, 1, bytes.length);
            return out;
        }
        // length > 55
        byte[] lenEnc = encodeLen(bytes.length);
        byte[] out = new byte[1 + lenEnc.length + bytes.length];
        out[0] = (byte) (0xB7 + lenEnc.length);
        System.arraycopy(lenEnc, 0, out, 1, lenEnc.length);
        System.arraycopy(bytes, 0, out, 1 + lenEnc.length, bytes.length);
        return out;
    }

    private static byte[] rlpUInt(int value) {
        if (value == 0) {
            return new byte[] {(byte) 0x80}; // empty (zero)
        }
        // minimal big-endian
        int v = value;
        int size = 0;
        byte[] tmp = new byte[8];
        while (v != 0) {
            tmp[7 - size] = (byte) (v & 0xFF);
            v >>>= 8;
            size++;
        }
        byte[] be = new byte[size];
        System.arraycopy(tmp, 8 - size, be, 0, size);
        return rlpBytes(be);
    }

    private static byte[] rlpList(byte[]... items) {
        int payloadLen = 0;
        for (byte[] it : items) payloadLen += it.length;
        byte[] payload = new byte[payloadLen];
        int off = 0;
        for (byte[] it : items) {
            System.arraycopy(it, 0, payload, off, it.length);
            off += it.length;
        }
        if (payloadLen <= 55) {
            byte[] out = new byte[1 + payloadLen];
            out[0] = (byte) (0xC0 + payloadLen);
            System.arraycopy(payload, 0, out, 1, payloadLen);
            return out;
        }
        byte[] lenEnc = encodeLen(payloadLen);
        byte[] out = new byte[1 + lenEnc.length + payloadLen];
        out[0] = (byte) (0xF7 + lenEnc.length);
        System.arraycopy(lenEnc, 0, out, 1, lenEnc.length);
        System.arraycopy(payload, 0, out, 1 + lenEnc.length, payloadLen);
        return out;
    }

    private static byte[] encodeLen(int len) {
        // big-endian, minimal
        int n = len;
        int size = 0;
        byte[] tmp = new byte[8];
        while (n != 0) {
            tmp[7 - size] = (byte) (n & 0xFF);
            n >>>= 8;
            size++;
        }
        byte[] out = new byte[size];
        System.arraycopy(tmp, 8 - size, out, 0, size);
        return out;
    }

    private static byte[] repeat(byte b, int n) {
        byte[] out = new byte[n];
        Arrays.fill(out, b);
        return out;
    }
}
