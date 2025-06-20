// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.listeners.TokenAccountRegistryRel;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenAssociation;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class RandomTokenAssociationHollowAccount extends RandomTokenAssociation {
    private final String[] signers;

    public RandomTokenAssociationHollowAccount(
            RegistrySourcedNameProvider<TokenID> tokens,
            RegistrySourcedNameProvider<AccountID> accounts,
            RegistrySourcedNameProvider<TokenAccountRegistryRel> tokenRels,
            String... signers) {
        super(tokens, accounts, tokenRels, new ResponseCodeEnum[] {});
        this.signers = signers;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        if (tokenRels.numPresent() >= ceilingNum) {
            return Optional.empty();
        }

        var account = accounts.getQualifying();
        if (account.isEmpty()) {
            return Optional.empty();
        }

        int numTokensToTry = BASE_RANDOM.nextInt(MAX_TOKENS_PER_OP) + 1;
        Set<String> chosen = new HashSet<>();
        while (numTokensToTry-- > 0) {
            var token = tokens.getQualifyingExcept(chosen);
            token.ifPresent(chosen::add);
        }
        if (chosen.isEmpty()) {
            return Optional.empty();
        }
        String[] toUse = chosen.toArray(new String[0]);

        var op = tokenAssociate(account.get(), toUse)
                .hasPrecheckFrom(STANDARD_PERMISSIBLE_PRECHECKS)
                .hasKnownStatusFrom(INVALID_SIGNATURE)
                .signedBy(signers);

        return Optional.of(op);
    }
}
