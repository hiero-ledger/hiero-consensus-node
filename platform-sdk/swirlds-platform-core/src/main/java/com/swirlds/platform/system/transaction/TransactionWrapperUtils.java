// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.transaction;

import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.transaction.TransactionWrapper;

/**
 * Utility class for creating {@link TransactionWrapper} instances.
 */
public final class TransactionWrapperUtils {
    private TransactionWrapperUtils() {
        // Utility class
    }

    /**
     * Creates an application {@link TransactionWrapper} instance and wraps the given payload in an {@link OneOf} and {@link Bytes} instances.
     *
     * @param payload the payload as a byte array
     *
     * @return the created {@link TransactionWrapper} instance
     */
    @NonNull
    public static TransactionWrapper createAppPayloadWrapper(final byte[] payload) {
        return createAppPayloadWrapper(Bytes.wrap(payload));
    }

    /**
     * Creates a application {@link TransactionWrapper} instance and wraps the given payload in an {@link OneOf} instance.
     *
     * @param payload the payload as {@link Bytes}
     *
     * @return the created {@link TransactionWrapper} instance
     */
    @NonNull
    public static TransactionWrapper createAppPayloadWrapper(@NonNull final Bytes payload) {
        return new TransactionWrapper(payload);
    }
}
