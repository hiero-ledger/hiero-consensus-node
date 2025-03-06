// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.token;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import java.util.Optional;

public class RandomTokenInfo implements OpProvider {
    private final RegistrySourcedNameProvider<TokenID> tokens;

    private final ResponseCodeEnum[] permissibleCostAnswerPrechecks = standardQueryPrechecksAnd(TOKEN_WAS_DELETED);
    private final ResponseCodeEnum[] permissibleAnswerOnlyPrechecks = standardQueryPrechecksAnd(TOKEN_WAS_DELETED);

    public RandomTokenInfo(RegistrySourcedNameProvider<TokenID> tokens) {
        this.tokens = tokens;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        Optional<String> token = tokens.getQualifying();
        if (token.isEmpty()) {
            return Optional.empty();
        }

        var op = getTokenInfo(token.get())
                .hasCostAnswerPrecheckFrom(permissibleCostAnswerPrechecks)
                .hasAnswerOnlyPrecheckFrom(permissibleAnswerOnlyPrechecks);

        return Optional.of(op);
    }
}
