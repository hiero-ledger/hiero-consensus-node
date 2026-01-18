// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.node.app.history.WritableHistoryStore;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.SortedMap;

/**
 * Callback interface for when proof construction is finished.
 */
@FunctionalInterface
public interface OnProofFinished {
    /**
     * Callback method to be invoked when proof construction is finished.
     * @param historyStore the history store
     * @param construction the history proof construction
     * @param targetNodeWeights the weights of the nodes in the target roster
     */
    void onFinished(
            @NonNull WritableHistoryStore historyStore,
            @NonNull HistoryProofConstruction construction,
            @NonNull SortedMap<Long, Long> targetNodeWeights);
}
