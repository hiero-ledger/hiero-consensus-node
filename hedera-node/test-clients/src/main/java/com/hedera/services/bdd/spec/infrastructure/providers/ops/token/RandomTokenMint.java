// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.token;

import static com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomToken.DEFAULT_MAX_SUPPLY;
import static com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenDissociation.explicit;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_MINT_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.listeners.TokenAccountRegistryRel;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;

public class RandomTokenMint implements OpProvider {
    private final RegistrySourcedNameProvider<TokenAccountRegistryRel> tokenRels;

    private final ResponseCodeEnum[] permissibleOutcomes = standardOutcomesAnd(
            TOKEN_WAS_DELETED, TOKEN_HAS_NO_SUPPLY_KEY, INVALID_TOKEN_MINT_AMOUNT, ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN);
    private final ResponseCodeEnum[] customOutcomes;

    public RandomTokenMint(
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
        var amount = BASE_RANDOM.nextLong(1, DEFAULT_MAX_SUPPLY);

        var op = mintToken(tokenAccountRel.getRight(), amount)
                .payingWith(tokenAccountRel.getLeft())
                .signedBy(tokenAccountRel.getLeft())
                .hasPrecheckFrom(plus(STANDARD_PERMISSIBLE_PRECHECKS, customOutcomes))
                .hasKnownStatusFrom(plus(permissibleOutcomes, customOutcomes));

        return Optional.of(op);
    }
}
