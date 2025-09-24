// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.embedded;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.swirlds.state.spi.WritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

public class MutateKVStateOp<K, V> extends UtilOp {

    private final String serviceName;
    private final int stateId;
    private final Consumer<WritableKVState<K, V>> observer;

    public MutateKVStateOp(
            @NonNull final String serviceName,
            final int stateId,
            @NonNull final Consumer<WritableKVState<K, V>> observer) {
        this.serviceName = requireNonNull(serviceName);
        this.stateId = stateId;
        this.observer = requireNonNull(observer);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var state = spec.embeddedStateOrThrow();
        final var writableStates = state.getWritableStates(serviceName);
        observer.accept(requireNonNull(writableStates.get(stateId)));
        spec.commitEmbeddedState();
        return false;
    }
}
