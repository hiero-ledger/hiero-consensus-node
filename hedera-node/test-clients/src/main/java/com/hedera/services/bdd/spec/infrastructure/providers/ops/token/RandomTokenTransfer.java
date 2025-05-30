// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.token;

import static com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenDissociation.explicit;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.listeners.TokenAccountRegistryRel;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;

public class RandomTokenTransfer implements OpProvider {

    private final RegistrySourcedNameProvider<TokenAccountRegistryRel> tokenRels;
    private final ResponseCodeEnum[] permissibleOutcomes = standardOutcomesAnd(
            INSUFFICIENT_TOKEN_BALANCE,
            ACCOUNT_FROZEN_FOR_TOKEN,
            ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN,
            ACCOUNT_DELETED,
            TOKEN_NOT_ASSOCIATED_TO_ACCOUNT,
            TOKEN_WAS_DELETED);
    private final ResponseCodeEnum[] customOutcomes;

    public RandomTokenTransfer(
            RegistrySourcedNameProvider<TokenAccountRegistryRel> tokenRels, ResponseCodeEnum[] customOutcomes) {
        this.tokenRels = tokenRels;
        this.customOutcomes = customOutcomes;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        var xferRel = tokenRels.getQualifying();
        if (xferRel.isEmpty()) {
            return Optional.empty();
        }

        HapiSpecOperation op;
        var rel = explicit(xferRel.get());
        var token = rel.getRight();
        if (BASE_RANDOM.nextBoolean()) {
            op = cryptoTransfer(moving(1, token)
                            .between(spec -> spec.registry().getTreasury(token), ignore -> rel.getLeft()))
                    .hasPrecheckFrom(STANDARD_PERMISSIBLE_PRECHECKS)
                    .hasKnownStatusFrom(permissibleOutcomes);
        } else {
            op = cryptoTransfer(moving(1, token).between(ignore -> rel.getLeft(), spec -> spec.registry()
                            .getTreasury(token)))
                    .hasPrecheckFrom(plus(STANDARD_PERMISSIBLE_PRECHECKS, customOutcomes))
                    .hasKnownStatusFrom(plus(permissibleOutcomes, customOutcomes));
        }

        return Optional.of(op);
    }
}
