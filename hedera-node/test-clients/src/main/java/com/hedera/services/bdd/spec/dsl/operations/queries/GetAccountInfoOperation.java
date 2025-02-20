// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.operations.queries;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.operations.AbstractSpecOperation;
import com.hedera.services.bdd.spec.queries.crypto.HapiGetAccountInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class GetAccountInfoOperation extends AbstractSpecOperation implements SpecOperation {
    private final SpecAccount target;

    @Nullable
    private Consumer<HapiGetAccountInfo> assertions = null;

    public GetAccountInfoOperation(@NonNull final SpecAccount target) {
        super(List.of(target));
        this.target = target;
    }

    public GetAccountInfoOperation andAssert(@NonNull final Consumer<HapiGetAccountInfo> assertions) {
        this.assertions = assertions;
        return this;
    }

    @Override
    protected @NonNull SpecOperation computeDelegate(@NonNull final HapiSpec spec) {
        final var op = getAccountInfo(target.name());
        Optional.ofNullable(assertions).ifPresent(a -> a.accept(op));
        return op;
    }
}
