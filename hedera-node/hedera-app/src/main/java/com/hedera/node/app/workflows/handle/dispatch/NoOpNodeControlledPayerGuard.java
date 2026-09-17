// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.dispatch;

import com.hedera.node.app.workflows.handle.Dispatch;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * The no-op {@link NodeControlledPayerGuard} bound by the in-process standalone transaction executor, which
 * legitimately dispatches NODE-category transactions (empty signature map) with a caller-chosen payer. It never
 * rejects, regardless of the dispatch — this is a deliberate, unconditional exemption, not an unfinished stub.
 */
@Singleton
public class NoOpNodeControlledPayerGuard implements NodeControlledPayerGuard {
    @Inject
    public NoOpNodeControlledPayerGuard() {
        // Dagger
    }

    @Override
    public boolean rejectsForeignNodePayer(@NonNull final Dispatch dispatch) {
        return false;
    }
}
