// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.contracts;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.trace.EvmTransactionLog;
import com.hedera.hapi.node.base.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.log.Log;
import org.hyperledger.besu.evm.log.LogTopic;
import org.hyperledger.besu.evm.log.LogsBloomFilter;

public final class ContractUtils {

    private ContractUtils() {
        // Don't allow instantiation
    }

    /**
     * Returns the PBJ bloom for a list of Besu {@link Log}s.
     *
     * @param logs the Besu {@link Log}s
     * @return the PBJ bloom
     */
    public static com.hedera.pbj.runtime.io.buffer.Bytes bloomForAll(@NonNull final List<Log> logs) {
        return com.hedera.pbj.runtime.io.buffer.Bytes.wrap(
                LogsBloomFilter.builder().insertLogs(logs).build().toArray());
    }

    /**
     * Given a Besu {@link Log}, returns its bloom filter as a PBJ {@link com.hedera.pbj.runtime.io.buffer.Bytes}.
     * @param log the Besu {@link Log}
     * @return the PBJ {@link com.hedera.pbj.runtime.io.buffer.Bytes} bloom filter
     */
    public static com.hedera.pbj.runtime.io.buffer.Bytes bloomFor(@NonNull final Log log) {
        requireNonNull(log);
        return com.hedera.pbj.runtime.io.buffer.Bytes.wrap(
                LogsBloomFilter.builder().insertLog(log).build().toArray());
    }

    /**
     * Converts a concise EVM transaction log into a Besu {@link Log}.
     *
     * @param log the concise EVM transaction log to convert
     * @param paddedTopics the 32-byte padded topics to use in the log
     * @return the Besu {@link Log} representation of the log
     */
    public static Log asBesuLog(
            @NonNull final EvmTransactionLog log,
            @NonNull final List<com.hedera.pbj.runtime.io.buffer.Bytes> paddedTopics) {
        requireNonNull(log);
        requireNonNull(paddedTopics);
        return new Log(
                asLongZeroAddress(log.contractIdOrThrow().contractNumOrThrow()),
                pbjToTuweniBytes(log.data()),
                paddedTopics.stream()
                        .map(ContractUtils::pbjToTuweniBytes)
                        .map(LogTopic::create)
                        .toList());
    }

    /**
     * Pads the given bytes to 32 bytes by left-padding with zeros.
     * @param bytes the bytes to pad
     * @return the left-padded bytes, or the original bytes if they are already 32 bytes long
     */
    public static com.hedera.pbj.runtime.io.buffer.Bytes leftPad32(
            @NonNull final com.hedera.pbj.runtime.io.buffer.Bytes bytes) {
        requireNonNull(bytes);
        final int n = (int) bytes.length();
        if (n == 32) {
            return bytes;
        }
        final var padded = new byte[32];
        bytes.getBytes(0, padded, 32 - n, n);
        return com.hedera.pbj.runtime.io.buffer.Bytes.wrap(padded);
    }

    /**
     * Converts a number to a long zero address.
     *
     * @param accountID the account id to convert
     * @return the long zero address
     */
    public static Address asLongZeroAddress(@NonNull final AccountID accountID) {
        return asLongZeroAddress(accountID.accountNumOrThrow());
    }

    /**
     * Converts a PBJ bytes to Tuweni bytes.
     *
     * @param bytes the PBJ bytes
     * @return the Tuweni bytes
     */
    public static @NonNull Bytes pbjToTuweniBytes(@NonNull final com.hedera.pbj.runtime.io.buffer.Bytes bytes) {
        if (bytes.length() == 0) {
            return Bytes.EMPTY;
        }
        return Bytes.wrap(clampedBytes(bytes, 0, Integer.MAX_VALUE));
    }

    /**
     * Given a long entity number, returns its 20-byte EVM address.
     *
     * @param num the entity number
     * @return its 20-byte EVM address
     */
    public static byte[] asEvmAddress(final long num) {
        return copyToLeftPaddedByteArray(num, new byte[20]);
    }

    /**
     * Given a value and a destination byte array, copies the value to the destination array, left-padded.
     *
     * @param value the value
     * @param dest the destination byte array
     * @return the destination byte array
     */
    public static byte[] copyToLeftPaddedByteArray(long value, final byte[] dest) {
        for (int i = 7, j = dest.length - 1; i >= 0; i--, j--) {
            dest[j] = (byte) (value & 0xffL);
            value >>= 8;
        }
        return dest;
    }

    public static byte[] clampedBytes(
            @NonNull final com.hedera.pbj.runtime.io.buffer.Bytes bytes, final int minLength, final int maxLength) {
        final var length = Math.toIntExact(requireNonNull(bytes).length());
        if (length < minLength) {
            throw new IllegalArgumentException("Expected at least " + minLength + " bytes, got " + bytes);
        }
        if (length > maxLength) {
            throw new IllegalArgumentException("Expected at most " + maxLength + " bytes, got " + bytes);
        }
        final byte[] data = new byte[length];
        bytes.getBytes(0, data);
        return data;
    }

    /**
     * Converts a shard, realm, number to a long zero address.
     * @param number the number to convert
     * @return the long zero address
     */
    public static Address asLongZeroAddress(final long number) {
        return Address.wrap(Bytes.wrap(asEvmAddress(number)));
    }
}
