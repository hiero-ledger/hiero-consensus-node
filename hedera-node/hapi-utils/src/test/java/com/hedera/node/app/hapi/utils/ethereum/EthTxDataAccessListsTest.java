// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.ethereum;

import static com.hedera.node.app.hapi.utils.ethereum.CodeDelegationTest.fillBytes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.esaulpaugh.headlong.rlp.RLPEncoder;
import com.esaulpaugh.headlong.util.Integers;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class EthTxDataAccessListsTest {

    // EIP2930
    private static byte[] buildDefaultType1RawTransaction(byte[] accessList) {
        return RLPEncoder.sequence(
                Integers.toBytes(0x01),
                List.of(
                        fillBytes(2, 0x01),
                        Integers.toBytes(1),
                        fillBytes(3, 0x02),
                        Integers.toBytes(100),
                        fillBytes(20, 0x04),
                        Integers.toBytesUnsigned(BigInteger.ZERO),
                        new byte[] {},
                        accessList,
                        Integers.toBytes(27),
                        fillBytes(32, 0x05),
                        fillBytes(32, 0x06)));
    }

    // EIP1559
    private static byte[] buildDefaultType2RawTransaction(byte[] accessList) {
        return RLPEncoder.sequence(
                Integers.toBytes(0x02),
                List.of(
                        fillBytes(2, 0x01),
                        Integers.toBytes(1),
                        fillBytes(3, 0x02),
                        fillBytes(3, 0x03),
                        Integers.toBytes(100),
                        fillBytes(20, 0x04),
                        Integers.toBytesUnsigned(BigInteger.ZERO),
                        new byte[] {},
                        accessList,
                        Integers.toBytes(27),
                        fillBytes(32, 0x05),
                        fillBytes(32, 0x06)));
    }

    // EIP7702
    private static byte[] buildDefaultType4RawTransaction(byte[] accessList) {
        return RLPEncoder.sequence(
                Integers.toBytes(0x04),
                List.of(
                        fillBytes(2, 0x01),
                        Integers.toBytes(1),
                        fillBytes(3, 0x02),
                        fillBytes(3, 0x03),
                        Integers.toBytes(100),
                        fillBytes(20, 0x04),
                        Integers.toBytesUnsigned(BigInteger.ZERO),
                        new byte[] {},
                        accessList,
                        fillBytes(5, 0x40),
                        Integers.toBytes(27),
                        fillBytes(32, 0x05),
                        fillBytes(32, 0x06)));
    }

    private record RawTransactionHolder(EthTxData.EthTransactionType type, byte[] data) {

        public static RawTransactionHolder of(EthTxData.EthTransactionType type, byte[] accessList) {
            return new RawTransactionHolder(
                    type,
                    switch (type) {
                        case EIP2930 -> buildDefaultType1RawTransaction(accessList);
                        case EIP1559 -> buildDefaultType2RawTransaction(accessList);
                        case EIP7702 -> buildDefaultType4RawTransaction(accessList);
                        default -> null;
                    });
        }

        @NonNull
        @Override
        public String toString() {
            return "RawTransactionHolder{" + "type=" + type + '}';
        }
    }

    private static Stream<RawTransactionHolder> provideTransactionsWithCorrectAccessList() {
        final byte[] addr1 = fillBytes(20, 0x10);
        final byte[] addr2 = fillBytes(20, 0x20);
        final byte[] key1 = fillBytes(32, 0x30);
        final byte[] key2 = fillBytes(32, 0x40);
        final Object[] accessList = new Object[] {
            new Object[] {addr1, new Object[] {key1, key2}},
            new Object[] {addr2, new Object[] {}}
        };
        final byte[] accessListBytes = RLPEncoder.sequence(accessList);
        return Stream.of(
                RawTransactionHolder.of(EthTxData.EthTransactionType.EIP2930, accessListBytes),
                RawTransactionHolder.of(EthTxData.EthTransactionType.EIP1559, accessListBytes),
                RawTransactionHolder.of(EthTxData.EthTransactionType.EIP7702, accessListBytes));
    }

    @MethodSource("provideTransactionsWithCorrectAccessList")
    @ParameterizedTest(name = "Transaction {0}")
    void accessListDecoding(RawTransactionHolder raw) {
        // When:
        final EthTxData tx = EthTxData.populateEthTxData(raw.data());
        assertNotNull(tx);
        // Then:
        final var accessLists = tx.extractAccessLists();
        assertNotNull(accessLists);
        assertEquals(2, accessLists.size());
        assertArrayEquals(fillBytes(20, 0x10), accessLists.getFirst().address());
        assertArrayEquals(fillBytes(20, 0x20), accessLists.getLast().address());
        assertNotNull(accessLists.getFirst().storageKeys());
        assertEquals(2, accessLists.getFirst().storageKeys().size());
        assertArrayEquals(
                fillBytes(32, 0x30), accessLists.getFirst().storageKeys().getFirst());
        assertArrayEquals(
                fillBytes(32, 0x40), accessLists.getFirst().storageKeys().getLast());
        assertNotNull(accessLists.getLast().storageKeys());
        assertEquals(0, accessLists.getLast().storageKeys().size());
    }

    private static Stream<RawTransactionHolder> provideTransactionsWhereAccessListIsNotList() {
        final byte[] accessListIsNotAList = new byte[] {0x01};
        return Stream.of(
                RawTransactionHolder.of(EthTxData.EthTransactionType.EIP2930, accessListIsNotAList),
                RawTransactionHolder.of(EthTxData.EthTransactionType.EIP1559, accessListIsNotAList),
                RawTransactionHolder.of(EthTxData.EthTransactionType.EIP7702, accessListIsNotAList));
    }

    @MethodSource("provideTransactionsWhereAccessListIsNotList")
    @ParameterizedTest(name = "Transaction {0}")
    void throwsWhenAccessListIsNotAList(RawTransactionHolder raw) {
        // When:
        final EthTxData tx = EthTxData.populateEthTxData(raw.data());
        assertNotNull(tx);
        // Then:
        final var thrown = assertThrows(IllegalArgumentException.class, tx::extractAccessLists, "qwe");
        assertEquals("Access list item should be a list", thrown.getMessage());
    }

    private static Stream<RawTransactionHolder> provideTransactionsWhereAccessListHasWrongItems() {
        final Object[] accessList =
                new Object[] {new Object[] {new byte[] {0x01}, new byte[] {0x01}, new byte[] {0x01}}};
        final byte[] accessListBytes = RLPEncoder.sequence(accessList);
        return Stream.of(
                RawTransactionHolder.of(EthTxData.EthTransactionType.EIP2930, accessListBytes),
                RawTransactionHolder.of(EthTxData.EthTransactionType.EIP1559, accessListBytes),
                RawTransactionHolder.of(EthTxData.EthTransactionType.EIP7702, accessListBytes));
    }

    @MethodSource("provideTransactionsWhereAccessListHasWrongItems")
    @ParameterizedTest(name = "Transaction {0}")
    void throwsWhenAccessListHasWrongItems(RawTransactionHolder raw) {
        // When:
        final EthTxData tx = EthTxData.populateEthTxData(raw.data());
        assertNotNull(tx);
        // Then:
        final var thrown = assertThrows(IllegalArgumentException.class, tx::extractAccessLists, "qwe");
        assertEquals("Access list item does not contain expected number of elements", thrown.getMessage());
    }

    private static Stream<RawTransactionHolder> provideTransactionsWhereStorageKeyIsNotList() {
        final byte[] addr1 = fillBytes(20, 0x10);
        final Object[] accessList = new Object[] {new Object[] {addr1, new byte[] {0x01}}};
        final byte[] accessListBytes = RLPEncoder.sequence(accessList);
        return Stream.of(
                RawTransactionHolder.of(EthTxData.EthTransactionType.EIP2930, accessListBytes),
                RawTransactionHolder.of(EthTxData.EthTransactionType.EIP1559, accessListBytes),
                RawTransactionHolder.of(EthTxData.EthTransactionType.EIP7702, accessListBytes));
    }

    @MethodSource("provideTransactionsWhereStorageKeyIsNotList")
    @ParameterizedTest(name = "Transaction {0}")
    void throwsWhenStorageKeyIsNotAList(RawTransactionHolder raw) {
        // When:
        final EthTxData tx = EthTxData.populateEthTxData(raw.data());
        assertNotNull(tx);
        // Then:
        final var thrown = assertThrows(IllegalArgumentException.class, tx::extractAccessLists, "qwe");
        assertEquals("Access list storage keys should be a list", thrown.getMessage());
    }
}
