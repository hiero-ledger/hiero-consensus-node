// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.node.app.hints.WritableHintsStore;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Callback interface for when hints construction is finished.
 */
@FunctionalInterface
public interface OnHintsFinished {
    /**
     * Callback method to be invoked when hints construction is finished.
     * @param hintsStore the hints store
     * @param construction the hints construction
     * @param context the hints context
     */
    void accept(
            @NonNull WritableHintsStore hintsStore,
            @NonNull HintsConstruction construction,
            @NonNull HintsContext context);
}
