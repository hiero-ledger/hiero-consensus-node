// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static java.util.Objects.requireNonNull;
import static org.hiero.interledger.clpr.impl.schemas.V0700ClprSchema.CLPR_MESSAGES_STATE_ID;

import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessageValue;
import org.hiero.interledger.clpr.ReadableClprMessageStore;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with CLPR messages.
 */
public class ReadableClprMessageStoreImpl implements ReadableClprMessageStore {

    private final ReadableKVState<ClprMessageKey, ClprMessageValue> readableClprMessageState;

    /**
     * Constructs a new {@code ReadableClprMessageStoreImpl}.
     *
     * @param states the {@link ReadableStates} instance providing access to the
     * required message state
     * @throws NullPointerException if {@code states} is {@code null}
     */
    public ReadableClprMessageStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        readableClprMessageState = states.get(CLPR_MESSAGES_STATE_ID);
    }

    /**
     * Retrieves a message value from the store using its unique key.
     *
     * @param messageKey the key identifying the specific CLPR message to retrieve
     * @return the {@link ClprMessageValue} associated with the key; {@code null}
     * if no such message exists
     * @throws NullPointerException if {@code messageKey} is {@code null}
     */
    @Override
    public @Nullable ClprMessageValue get(@NonNull ClprMessageKey messageKey) {
        requireNonNull(messageKey);
        return readableClprMessageState.get(messageKey);
    }
}
