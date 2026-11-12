// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.spi.fees.FeeCharging;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An unexpected workflow failure with side effects that must be replayed after rollback.
 *
 * <p>Unlike {@link HandleException}, this retains the cause and follows the ordinary internal-error
 * handling path, including alert logging and waiver of the service fee.
 */
public final class RollbackAwareException extends RuntimeException {
    private final HandleException.OnRollback onRollback;

    public RollbackAwareException(
            @NonNull final RuntimeException cause, @NonNull final HandleException.OnRollback onRollback) {
        super(requireNonNull(cause));
        this.onRollback = requireNonNull(onRollback);
    }

    /** Replays the retained side effects after the standard internal-error rollback and fee charge. */
    public void maybeReplay(
            @NonNull final FeeCharging.Context feeChargingContext, @NonNull final HandleContext handleContext) {
        onRollback.replay(feeChargingContext, handleContext);
    }
}
