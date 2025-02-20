// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.token;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.listeners.TokenAccountRegistryRel;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;
import org.apache.commons.lang3.tuple.Pair;

public class RandomTokenDissociation implements OpProvider {
    private final RegistrySourcedNameProvider<TokenAccountRegistryRel> tokenRels;

    private final ResponseCodeEnum[] customOutcomes;

    public RandomTokenDissociation(
            RegistrySourcedNameProvider<TokenAccountRegistryRel> tokenRels, ResponseCodeEnum[] customOutcomes) {
        this.tokenRels = tokenRels;
        this.customOutcomes = customOutcomes;
    }

    private final ResponseCodeEnum[] permissibleOutcomes = standardOutcomesAnd(
            TOKEN_WAS_DELETED,
            ACCOUNT_IS_TREASURY,
            ACCOUNT_FROZEN_FOR_TOKEN,
            TOKEN_NOT_ASSOCIATED_TO_ACCOUNT,
            ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN,
            TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES);

    @Override
    public Optional<HapiSpecOperation> get() {
        var relToDissociate = tokenRels.getQualifying();
        if (relToDissociate.isEmpty()) {
            return Optional.empty();
        }

        var implicitRel = relToDissociate.get();
        var rel = explicit(implicitRel);
        var op = tokenDissociate(rel.getLeft(), rel.getRight())
                .payingWith(rel.getLeft())
                .signedBy(rel.getLeft())
                .hasPrecheckFrom(plus(STANDARD_PERMISSIBLE_PRECHECKS, customOutcomes))
                .hasKnownStatusFrom(plus(permissibleOutcomes, customOutcomes));
        return Optional.of(op);
    }

    static Pair<String, String> explicit(String implicitRel) {
        var divider = implicitRel.indexOf("|");
        var account = implicitRel.substring(0, divider);
        var token = implicitRel.substring(divider + 1);
        return Pair.of(account, token);
    }
}
