/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.system.transaction;

import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

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
