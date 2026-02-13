// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static java.util.Objects.requireNonNull;
import static org.hiero.interledger.clpr.impl.schemas.V0700ClprSchema.CLPR_MESSAGES_STATE_ID;

import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessageValue;
import org.hiero.interledger.clpr.WritableClprMessageStore;

/**
 * A writable store that wraps a writable key-value state and supports operations required to store
 * CLPR messages.
 */
public class WritableClprMessageStoreImpl extends ReadableClprMessageStoreImpl implements WritableClprMessageStore {

    private final WritableKVState<ClprMessageKey, ClprMessageValue> writableMessageState;

    /**
     * Constructs a new {@code WritableClprMessageStoreImpl}.
     *
     * @param states the {@link WritableStates} instance used to retrieve the
     * underlying writable message state
     * @throws NullPointerException if {@code states} is {@code null}
     */
    public WritableClprMessageStoreImpl(@NonNull final WritableStates states) {
        super(states);
        writableMessageState = states.get(CLPR_MESSAGES_STATE_ID);
    }

    /**
     * Stores or updates a CLPR message associated with the provided key.
     *
     * @param messageKey the unique key identifying the message
     * @param clprMessageValue the message content to be stored
     * @throws NullPointerException if {@code messageKey} or {@code clprMessageValue} is {@code null}
     */
    @Override
    public void put(@NonNull ClprMessageKey messageKey, @NonNull ClprMessageValue clprMessageValue) {
        requireNonNull(messageKey);
        requireNonNull(clprMessageValue);
        writableMessageState.put(messageKey, clprMessageValue);
    }

    /**
     * Removes a CLPR message associated with the provided key.
     *
     * @param messageKey the unique key identifying the message
     * @throws NullPointerException if {@code messageKey} is {@code null}
     */
    @Override
    public void remove(@NonNull ClprMessageKey messageKey) {
        requireNonNull(messageKey);
        writableMessageState.remove(messageKey);
    }
}
