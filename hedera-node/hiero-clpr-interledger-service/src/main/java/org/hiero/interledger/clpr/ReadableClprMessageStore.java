// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessageValue;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with CLPR messages.
 */
public interface ReadableClprMessageStore {

    /**
     * Retrieves a message value from the store using its unique key.
     *
     * @param messageKey the key identifying the specific CLPR message to retrieve
     * @return the {@link ClprMessageValue} associated with the key; {@code null}
     * if no such message exists
     */
    @Nullable
    ClprMessageValue get(@NonNull ClprMessageKey messageKey);
}
