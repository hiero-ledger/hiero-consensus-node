// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Read-only access to the {@link ClprEndpointManifestConstruction} singleton — the
 * in-progress construction that gathers per-node endpoint publications. Absent when no
 * construction is in flight (design doc §3, §4). Hiero-internal; never exposed cross-ledger.
 */
public interface ReadableEndpointManifestConstructionStore {

    /**
     * Returns the current construction, or {@code null} if none is in flight.
     */
    @Nullable
    ClprEndpointManifestConstruction get();
}
