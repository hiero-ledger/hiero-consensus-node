// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.token;

import static com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenDissociation.explicit;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_WIPING_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_WIPE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.listeners.TokenAccountRegistryRel;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;

public class RandomTokenAccountWipe implements OpProvider {
    private final RegistrySourcedNameProvider<TokenAccountRegistryRel> tokenRels;
    private final ResponseCodeEnum[] customOutcomes;

    public RandomTokenAccountWipe(
            RegistrySourcedNameProvider<TokenAccountRegistryRel> tokenRels, ResponseCodeEnum[] customOutcomes) {
        this.tokenRels = tokenRels;
        this.customOutcomes = customOutcomes;
    }

    private final ResponseCodeEnum[] permissibleOutcomes = standardOutcomesAnd(
            TOKEN_WAS_DELETED,
            ACCOUNT_FROZEN_FOR_TOKEN,
            ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN,
            TOKEN_HAS_NO_WIPE_KEY,
            CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT,
            INVALID_WIPING_AMOUNT,
            TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);

    @Override
    public Optional<HapiSpecOperation> get() {
        var relToWipe = tokenRels.getQualifying();
        if (relToWipe.isEmpty()) {
            return Optional.empty();
        }

        var implicitRel = relToWipe.get();
        var rel = explicit(implicitRel);
        var amount = BASE_RANDOM.nextLong(1, RandomToken.DEFAULT_MAX_SUPPLY);
        var op = wipeTokenAccount(rel.getRight(), rel.getLeft(), amount)
                .payingWith(rel.getLeft())
                .signedBy(rel.getLeft())
                .hasPrecheckFrom(plus(STANDARD_PERMISSIBLE_PRECHECKS, customOutcomes))
                .hasKnownStatusFrom(plus(permissibleOutcomes, customOutcomes));
        return Optional.of(op);
    }
}
