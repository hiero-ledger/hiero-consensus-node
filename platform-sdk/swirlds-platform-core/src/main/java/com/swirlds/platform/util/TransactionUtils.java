// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.util;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Utility class for handling PJB transactions.
 * <p>
 * <b>IMPORTANT:</b> This class is subject to deletion in the future. It's only needed for the transition period
 * from old serialization to PBJ serialization.
 */
public final class TransactionUtils {
    private TransactionUtils() {}

    public static int getLegacyTransactionSize(@NonNull final Bytes transaction) {
        return Integer.BYTES // add the the size of array length field
                + (int) transaction.length(); // add the size of the array
    }
}
