// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessageValue;

/**
 * A writable store that wraps a writable key-value state and supports operations required to store
 * CLPR messages.
 */
public interface WritableClprMessageStore extends ReadableClprMessageStore {

    /**
     * Stores or updates a CLPR message associated with the provided key.
     *
     * @param messageKey the unique key identifying the message
     * @param clprMessageValue the message content to be stored
     */
    void put(@NonNull ClprMessageKey messageKey, @NonNull ClprMessageValue clprMessageValue);
}
