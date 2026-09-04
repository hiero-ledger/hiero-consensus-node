// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only access to the per-endpoint submission throttle snapshots
 * used to enforce {@code max_bundles_per_sec}.
 */
public interface ReadableSubmissionThrottleStore {

    /**
     * Returns the latest persisted throttle snapshot for the given endpoint
     * account, or {@code null} if this endpoint has not submitted a bundle
     * before (or its snapshot was cleared).
     *
     * @param endpointAccountId the endpoint's account
     * @return the snapshot, or {@code null} if absent
     */
    @Nullable
    ThrottleUsageSnapshot snapshotFor(@NonNull AccountID endpointAccountId);
}
