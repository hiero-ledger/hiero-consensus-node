// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides read-only access to the local {@link ClprEndpointManifest} singleton in state.
 * The manifest is service-scoped: all admitted endpoints serve every Channel on this
 * CLPR Service (spec §2.4.1).
 */
public interface ReadableEndpointManifestStore {

    /**
     * Returns the current endpoint manifest. The manifest is always present - it is created at
     * genesis (via the V0660 schema migration) at {@code version = 1} with an empty endpoint
     * list and never removed.
     *
     * @return the current manifest, never {@code null}
     */
    @NonNull
    ClprEndpointManifest get();
}
