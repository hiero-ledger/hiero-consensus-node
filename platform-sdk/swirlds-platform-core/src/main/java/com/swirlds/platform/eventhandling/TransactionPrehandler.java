// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.eventhandling;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Queue;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

/**
 * Performs the prehandling of transactions
 */
public interface TransactionPrehandler {
    /**
     * Prehandles application transactions
     *
     * @param event the event to prehandle
     */
    @InputWireLabel("PlatformEvent")
    Queue<ScopedSystemTransaction<StateSignatureTransaction>> prehandleApplicationTransactions(
            @NonNull PlatformEvent event);
}
