// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/** Application services for producing compact Hiero bundles without depending on the hinTS implementation. */
public interface ClprHieroProofSupport {
    /** Reasons a compact bundle could not be offered to the peer this cycle. */
    enum SkipReason {
        UNKNOWN_ANCHOR,
        ANCHOR_AHEAD,
        SNAPSHOT_NOT_READY,
        PENDING_WITNESS,
        UNWITNESSED_GAP,
        SHA_384_NETWORK,
        SIZE_LIMIT,
        NO_PROGRESS,
        INVALID_PROOF
    }

    /** Checks the exact outgoing bytes against the trust anchor reported by the destination. */
    boolean verify(@NonNull Bytes envelope, @NonNull Bytes anchorId);

    /** Records a compact bundle that could not be built or validated. */
    default void reportSkip(@NonNull final Bytes channelId, @NonNull final SkipReason reason) {}
}
