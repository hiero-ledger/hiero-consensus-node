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

public class WritableClprMessageStoreImpl extends ReadableClprMessageStoreImpl implements WritableClprMessageStore {

    private final WritableKVState<ClprMessageKey, ClprMessageValue> writableMessageState;

    public WritableClprMessageStoreImpl(@NonNull final WritableStates states) {
        super(states);
        writableMessageState = states.get(CLPR_MESSAGES_STATE_ID);
    }

    @Override
    public void put(@NonNull ClprMessageKey messageKey, @NonNull ClprMessageValue clprMessageValue) {
        requireNonNull(messageKey);
        requireNonNull(clprMessageValue);
        writableMessageState.put(messageKey, clprMessageValue);
    }
}
