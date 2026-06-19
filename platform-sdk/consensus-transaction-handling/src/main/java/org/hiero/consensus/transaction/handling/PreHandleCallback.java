package org.hiero.consensus.transaction.handling;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

/**
 * Callback interface for pre-handling events.
 */
public interface PreHandleCallback {

    /**
     * Callback method for pre-handling events.
     *
     * @param event the event to pre-handle
     * @param state the state at the time of the event
     * @param stateSignatureTransactionCallback a consumer that will be used to notify the platform of any included state signature transactions
     */
    void onPreHandle(
            @NonNull Event event,
            @NonNull State state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback);
}
