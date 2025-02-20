// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.token;

import static com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenDissociation.explicit;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.revokeTokenKyc;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.listeners.TokenAccountRegistryRel;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;

public class RandomTokenKycRevoke implements OpProvider {
    private final RegistrySourcedNameProvider<TokenAccountRegistryRel> tokenRels;
    private final ResponseCodeEnum[] customOutcomes;
    private final ResponseCodeEnum[] permissibleOutcomes = standardOutcomesAnd(
            TOKEN_WAS_DELETED,
            TOKEN_HAS_NO_KYC_KEY,
            TOKEN_NOT_ASSOCIATED_TO_ACCOUNT,
            ACCOUNT_FROZEN_FOR_TOKEN,
            ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN);

    public RandomTokenKycRevoke(
            RegistrySourcedNameProvider<TokenAccountRegistryRel> tokenRels, ResponseCodeEnum[] customOutcomes) {
        this.tokenRels = tokenRels;
        this.customOutcomes = customOutcomes;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        var relToRevoke = tokenRels.getQualifying();
        if (relToRevoke.isEmpty()) {
            return Optional.empty();
        }

        var implicitRel = relToRevoke.get();
        var rel = explicit(implicitRel);
        var op = revokeTokenKyc(rel.getRight(), rel.getLeft())
                .payingWith(rel.getLeft())
                .signedBy(rel.getLeft())
                .hasPrecheckFrom(plus(STANDARD_PERMISSIBLE_PRECHECKS, customOutcomes))
                .hasKnownStatusFrom(plus(permissibleOutcomes, customOutcomes));
        return Optional.of(op);
    }
}
