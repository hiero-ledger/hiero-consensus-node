// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.operations.queries;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.operations.AbstractSpecOperation;
import com.hedera.services.bdd.spec.queries.contract.HapiGetContractBytecode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class GetBytecodeOperation extends AbstractSpecOperation implements SpecOperation {
    private final SpecContract target;

    @Nullable
    private Consumer<HapiGetContractBytecode> assertions = null;

    public GetBytecodeOperation(@NonNull final SpecContract target) {
        super(List.of(target));
        this.target = target;
    }

    public GetBytecodeOperation andAssert(@NonNull final Consumer<HapiGetContractBytecode> assertions) {
        this.assertions = assertions;
        return this;
    }

    @Override
    protected @NonNull SpecOperation computeDelegate(@NonNull final HapiSpec spec) {
        final var op = getContractBytecode(target.name());
        Optional.ofNullable(assertions).ifPresent(a -> a.accept(op));
        return op;
    }
}
