// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.resubmitter;

import com.hedera.hapi.platform.event.EventTransaction;
import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * A simple utility responsible for resubmitting stale transactions.
 */
public interface TransactionResubmitter {

    /**
     * Resubmit transactions that have gone stale.
     *
     * @param event the event that has gone stale
     * @return a list of transactions that should be resubmitted
     */
    @InputWireLabel("stale events")
    @NonNull
    List<EventTransaction> resubmitStaleTransactions(@NonNull PlatformEvent event);

    /**
     * Update the current event window. The transaction resubmitter may use this information to decide which
     * transactions are worth resubmitting.
     *
     * @param eventWindow the new event window
     */
    @InputWireLabel("event window")
    void updateEventWindow(@NonNull final EventWindow eventWindow);
}
