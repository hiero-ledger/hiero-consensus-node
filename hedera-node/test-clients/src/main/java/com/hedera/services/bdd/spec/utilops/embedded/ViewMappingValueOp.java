// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.embedded;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

public class ViewMappingValueOp<K, V> extends UtilOp {

    private final String serviceName;
    private final int stateId;
    private final K key;
    private final Consumer<V> observer;

    public ViewMappingValueOp(
            @NonNull final String serviceName,
            final int stateId,
            @NonNull final K key,
            @NonNull final Consumer<V> observer) {
        this.serviceName = requireNonNull(serviceName);
        this.stateId = stateId;
        this.key = requireNonNull(key);
        this.observer = requireNonNull(observer);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var state = spec.embeddedStateOrThrow();
        final var readableStates = state.getReadableStates(serviceName);
        final var mapping = requireNonNull(readableStates.<K, V>get(stateId));
        final var value = mapping.get(key);
        assertNotNull(value, "No value found for key '" + key + "' in state '" + serviceName + "." + stateId + "'");
        observer.accept(value);
        return false;
    }
}
