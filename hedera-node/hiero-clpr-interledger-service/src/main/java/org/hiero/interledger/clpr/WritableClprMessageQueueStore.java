// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;

public interface WritableClprMessageQueueStore extends ReadableClprMessageQueueStore {

    void put(@NonNull ClprLedgerId ledgerId, @NonNull ClprMessageQueueMetadata messageQueueMetadata);
}
