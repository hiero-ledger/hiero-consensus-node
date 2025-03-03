// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.operations.deferred;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.entities.AbstractSpecEntity;
import com.hedera.services.bdd.spec.dsl.operations.AbstractSpecOperation;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.function.Function;

/**
 * Represents an operation that is deferred until a model is available.
 *
 * @param <M> the type of the model
 */
public class DoWithModelOperation<M> extends AbstractSpecOperation implements SpecOperation {

    private final AbstractSpecEntity<?, M> source;
    private final Function<M, SpecOperation> function;

    public DoWithModelOperation(
            @NonNull final AbstractSpecEntity<?, M> source, @NonNull final Function<M, SpecOperation> function) {
        super(List.of(source));
        this.source = source;
        this.function = requireNonNull(function);
    }

    @NonNull
    @Override
    protected SpecOperation computeDelegate(@NonNull final HapiSpec spec) {
        requireNonNull(spec);
        return function.apply(source.modelOrThrow(spec.targetNetworkOrThrow()));
    }
}
