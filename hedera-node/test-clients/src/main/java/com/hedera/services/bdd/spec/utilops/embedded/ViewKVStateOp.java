// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.embedded;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.swirlds.state.spi.ReadableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

public class ViewKVStateOp<K, V> extends UtilOp {
    private final String serviceName;
    private final String stateKey;
    private final Consumer<ReadableKVState<K, V>> observer;

    public ViewKVStateOp(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final Consumer<ReadableKVState<K, V>> observer) {
        this.serviceName = requireNonNull(serviceName);
        this.stateKey = requireNonNull(stateKey);
        this.observer = requireNonNull(observer);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var state = spec.embeddedStateOrThrow();
        final var readableStates = state.getReadableStates(serviceName);
        observer.accept(requireNonNull(readableStates.get(stateKey)));
        return false;
    }
}
