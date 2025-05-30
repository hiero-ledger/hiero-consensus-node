// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.token;

import static com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenDissociation.explicit;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.listeners.TokenAccountRegistryRel;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;

public class RandomTokenDeletion implements OpProvider {
    private final RegistrySourcedNameProvider<TokenAccountRegistryRel> tokenRels;

    private final ResponseCodeEnum[] permissibleOutcomes = standardOutcomesAnd(TOKEN_IS_IMMUTABLE, TOKEN_WAS_DELETED);
    private final ResponseCodeEnum[] customOutcomes;

    public RandomTokenDeletion(
            RegistrySourcedNameProvider<TokenAccountRegistryRel> tokenRels, ResponseCodeEnum[] customOutcomes) {
        this.tokenRels = tokenRels;
        this.customOutcomes = customOutcomes;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        var rel = tokenRels.getQualifying();
        if (rel.isEmpty()) {
            return Optional.empty();
        }

        var tokenAccountRel = explicit(rel.get());
        var op = tokenDelete(tokenAccountRel.getRight())
                .payingWith(tokenAccountRel.getLeft())
                .signedBy(tokenAccountRel.getLeft())
                .purging()
                .hasPrecheckFrom(plus(STANDARD_PERMISSIBLE_PRECHECKS, customOutcomes))
                .hasKnownStatusFrom(plus(permissibleOutcomes, customOutcomes));
        return Optional.of(op);
    }
}
