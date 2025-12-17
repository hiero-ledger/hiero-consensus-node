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

public class ReadableClprMessageStoreImpl implements ReadableClprMessageStore {

    private final ReadableKVState<ClprMessageKey, ClprMessageValue> readableClprMessageState;

    public ReadableClprMessageStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        readableClprMessageState = states.get(CLPR_MESSAGES_STATE_ID);
    }

    @Override
    public @Nullable ClprMessageValue get(@NonNull ClprMessageKey messageKey) {
        requireNonNull(messageKey);
        return readableClprMessageState.get(messageKey);
    }
}
