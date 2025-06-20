// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.hiero.base.io.streams.SerializableDataInputStream;

/**
 * Utility methods for migration testing tool transactions
 */
public class TransactionUtils {
    private static final byte APPLICATION_TRANSACTION_MARKER = 1;

    /**
     * Parse a {@link MigrationTestingToolTransaction} from a {@link Bytes}.
     */
    public static @NonNull MigrationTestingToolTransaction parseTransaction(@NonNull final Bytes bytes) {
        // Remove the first byte, which is marker added to distinguish application transactions from system ones in
        // TransactionGenerator
        final Bytes slicedBytes = bytes.slice(1, bytes.length() - 1);
        final SerializableDataInputStream in = new SerializableDataInputStream(slicedBytes.toInputStream());

        try {
            return in.readSerializable(false, MigrationTestingToolTransaction::new);
        } catch (final IOException e) {
            throw new UncheckedIOException("Could not parse transaction kind:%s".formatted(bytes.toHex()), e);
        }
    }

    public static boolean isSystemTransaction(@NonNull final Bytes bytes) {
        return bytes.getByte(0) != APPLICATION_TRANSACTION_MARKER;
    }
}
