// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.embedded;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.UnaryOperator;

/**
 * An operation that allows the test author to view a singleton value in an embedded state.
 * @param <T> the type of the singleton
 */
public class MutateSingletonOp<T> extends UtilOp {
    private final String serviceName;
    private final String stateKey;
    private final UnaryOperator<T> mutator;

    /**
     * Constructs the operation.
     * @param serviceName the name of the service that manages the record
     * @param stateKey the key of the record in the state
     * @param mutator the mutator that will receive the record
     */
    public MutateSingletonOp(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final UnaryOperator<T> mutator) {
        this.serviceName = requireNonNull(serviceName);
        this.stateKey = requireNonNull(stateKey);
        this.mutator = requireNonNull(mutator);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var state = spec.embeddedStateOrThrow();
        final var writableStates = state.getWritableStates(serviceName);
        final var singletonState = writableStates.<T>getSingleton(stateKey);
        singletonState.put(mutator.apply(singletonState.get()));
        spec.commitEmbeddedState();
        return false;
    }
}
