// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.test.fixtures.transaction;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Random;

public class TestingTransactions {
    /**
     * Generate a random transaction.
     */
    @NonNull
    public static Bytes generateRandomTransaction(@NonNull final Random random) {
        final byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Bytes.wrap(bytes);
    }
}
