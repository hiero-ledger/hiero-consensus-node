// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseType;
import edu.umd.cs.findbugs.annotations.NonNull;

/** An abstract class for all free queries (no costs, not possible to requests costs) */
public abstract class FreeQueryHandler implements QueryHandler {

    @Override
    public boolean requiresNodePayment(@NonNull final ResponseType responseType) {
        requireNonNull(responseType);
        return false;
    }

    @Override
    // Suppressing the warning that this method is the same as requiresNodePayment.
    // To be removed if that changes
    @SuppressWarnings("java:S4144")
    public boolean needsAnswerOnlyCost(@NonNull final ResponseType responseType) {
        requireNonNull(responseType);
        return false;
    }
}
