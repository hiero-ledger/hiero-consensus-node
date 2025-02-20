// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomToken;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import java.util.Optional;

public class RandomTokenHollowAccount extends RandomToken {

    private String[] signers;

    public RandomTokenHollowAccount(
            RegistrySourcedNameProvider<TokenID> tokens,
            RegistrySourcedNameProvider<AccountID> accounts,
            String... signers) {
        super(tokens, accounts, accounts);
        this.signers = signers;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        if (tokens.numPresent() >= ceilingNum) {
            return Optional.empty();
        }

        int id = opNo.getAndIncrement();
        HapiTokenCreate op = tokenCreate(my("token" + id))
                .hasPrecheckFrom(STANDARD_PERMISSIBLE_PRECHECKS)
                .hasKnownStatusFrom(INVALID_SIGNATURE)
                .signedBy(signers);

        var prefix = randomlyConfigureKeys(op);
        op.setTokenPrefix(prefix);

        randomlyConfigureSupply(op);
        randomlyConfigureAutoRenew(op);
        randomlyConfigureStrings(op);

        return Optional.of(op);
    }
}
