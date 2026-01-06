// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;

public interface ReadableClprMessageQueueMetadataStore {

    @Nullable
    ClprMessageQueueMetadata get(@NonNull ClprLedgerId ledgerId);
}
