// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.operations.queries;

import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.allRequiredCallEntities;
import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.withSubstitutedTypes;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.queries.contract.HapiContractCallLocal;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents a static call to a smart contract.
 */
public class StaticCallContractOperation extends AbstractSpecQuery<StaticCallContractOperation, HapiContractCallLocal>
        implements SpecOperation {
    private final SpecContract target;
    private final String function;
    private final Object[] parameters;

    public StaticCallContractOperation(
            @NonNull final SpecContract target, @NonNull final String function, @NonNull final Object... parameters) {
        super(allRequiredCallEntities(target, parameters));
        this.target = requireNonNull(target);
        this.function = requireNonNull(function);
        this.parameters = requireNonNull(parameters);
    }

    @NonNull
    @Override
    protected SpecOperation computeDelegate(@NonNull HapiSpec spec) {
        final var op = contractCallLocal(
                target.name(), function, withSubstitutedTypes(spec.targetNetworkOrThrow(), parameters));
        maybeAssertions().ifPresent(a -> a.accept(op));
        return op;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected StaticCallContractOperation self() {
        return this;
    }
}
