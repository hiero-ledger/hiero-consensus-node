// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.operations.queries;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.dsl.SpecEntity;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.operations.AbstractSpecOperation;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public abstract class AbstractSpecQuery<S extends AbstractSpecQuery<S, T>, T extends HapiQueryOp<T>>
        extends AbstractSpecOperation {
    @Nullable
    private Consumer<T> assertions;

    @Nullable
    protected SpecAccount payer;

    protected AbstractSpecQuery(@NonNull final List<SpecEntity> requiredEntities) {
        super(requiredEntities);
    }

    /**
     * Set the account that will pay for the query.
     * @param payer the account
     * @return this
     */
    public S payingWith(@NonNull final SpecAccount payer) {
        this.payer = requireNonNull(payer);
        requiredEntities.add(payer);
        return self();
    }

    /**
     * Set the assertions to be made on the query.
     *
     * @param assertions the assertions
     * @return this
     */
    public S andAssert(@NonNull final Consumer<T> assertions) {
        this.assertions = requireNonNull(assertions);
        return self();
    }

    /**
     * Get the assertions to be made on the query, if present.
     *
     * @return the assertions
     */
    protected Optional<Consumer<T>> maybeAssertions() {
        return Optional.ofNullable(assertions)
                .map(spec -> payer == null ? spec : spec.andThen(op -> op.payingWith(payer.name())));
    }

    /**
     * Return this object as an instance of its concrete type.
     *
     * @return this
     */
    protected abstract S self();
}
