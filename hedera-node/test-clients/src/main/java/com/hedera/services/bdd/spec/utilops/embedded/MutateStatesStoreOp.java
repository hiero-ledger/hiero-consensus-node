// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.embedded;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.BiConsumer;

public class MutateStatesStoreOp extends UtilOp {
    private final String serviceName;
    private final BiConsumer<WritableStates, WritableEntityCounters> observer;

    public MutateStatesStoreOp(
            @NonNull final String serviceName,
            @NonNull final BiConsumer<WritableStates, WritableEntityCounters> observer) {
        this.serviceName = requireNonNull(serviceName);
        this.observer = requireNonNull(observer);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var state = spec.embeddedStateOrThrow();
        final var writableStates = state.getWritableStates(serviceName);
        final var entityWritableStates = state.getWritableStates(EntityIdService.NAME);
        observer.accept(writableStates, new WritableEntityIdStore(entityWritableStates));
        spec.commitEmbeddedState();
        return false;
    }
}
