// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.embedded;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.state.schedule.ScheduledCounts;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.swirlds.state.spi.WritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

/**
 * An operation that allows the test author to directly mutate the schedule counts in an embedded state.
 */
public class MutateScheduleCountsOp extends UtilOp {
    private final Consumer<WritableKVState<TimestampSeconds, ScheduledCounts>> mutation;

    public MutateScheduleCountsOp(
            @NonNull final Consumer<WritableKVState<TimestampSeconds, ScheduledCounts>> mutation) {
        this.mutation = requireNonNull(mutation);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var state = spec.embeddedScheduleCountsOrThrow();
        mutation.accept(state);
        spec.commitEmbeddedState();
        return false;
    }
}
