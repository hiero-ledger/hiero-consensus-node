// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.dispatch;

import com.hedera.node.app.workflows.handle.Dispatch;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Decides whether a NODE-category dispatch must be rejected because its payer is not node-controlled.
 *
 * <p>A NODE-category transaction (empty signature map) skips payer-signature verification, so its payer must be a
 * node-controlled account. On a live consensus node this is enforced; the in-process standalone transaction executor
 * legitimately dispatches NODE-category transactions with a caller-chosen payer and therefore binds a no-op that never
 * rejects. A consumer only needs to know <em>whether</em> to reject, not <em>why</em> it is (or is not) exempt.
 */
public interface NodeControlledPayerGuard {
    /**
     * Returns whether the given dispatch must be rejected as a node due-diligence failure because it is a
     * NODE-category dispatch whose payer is not node-controlled.
     *
     * @param dispatch the dispatch
     * @return true if the dispatch must be rejected
     */
    boolean rejectsForeignNodePayer(@NonNull Dispatch dispatch);
}
