// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.operations.queries;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.entities.SpecToken;
import com.hedera.services.bdd.spec.dsl.operations.AbstractSpecOperation;
import com.hedera.services.bdd.spec.queries.token.HapiGetTokenInfo;
import com.hederahashgraph.api.proto.java.TokenInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public class GetTokenInfoOperation extends AbstractSpecOperation implements SpecOperation {
    private final SpecToken target;

    @Nullable
    private Consumer<HapiGetTokenInfo> assertions = null;

    public GetTokenInfoOperation(@NonNull final SpecToken target) {
        super(List.of(target));
        this.target = target;
    }

    public GetTokenInfoOperation andAssert(@NonNull final Consumer<HapiGetTokenInfo> assertions) {
        this.assertions = assertions;
        return this;
    }

    /**
     * Takes a factory to produce a verification operation from the retrieved token info and returns this.
     *
     * @param verification the factory to produce the verification operation
     * @return this
     */
    public GetTokenInfoOperation andDo(@NonNull final Function<TokenInfo, SpecOperation> verification) {
        this.assertions = op -> op.andVerify(verification);
        return this;
    }

    /**
     * Allows to get TokenInfo into tokenInfoConsumer.
     *
     * @param tokenInfoConsumer the consumer that will get TokenInfo when response will be available
     * @return this
     */
    public GetTokenInfoOperation andGet(@NonNull final Consumer<TokenInfo> tokenInfoConsumer) {
        this.assertions = op -> op.getTokenInfo(tokenInfoConsumer);
        return this;
    }

    @NonNull
    @Override
    protected SpecOperation computeDelegate(@NonNull HapiSpec spec) {
        final var op = getTokenInfo(target.name());
        Optional.ofNullable(assertions).ifPresent(a -> a.accept(op));
        return op;
    }
}
