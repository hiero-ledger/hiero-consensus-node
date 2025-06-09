// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.token;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.listeners.TokenAccountRegistryRel;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class RandomTokenAssociation implements OpProvider {

    public static final int MAX_TOKENS_PER_OP = 2;
    public static final int DEFAULT_CEILING_NUM = 10_000;

    protected int ceilingNum = DEFAULT_CEILING_NUM;

    private final ResponseCodeEnum[] customOutcomes;
    protected final RegistrySourcedNameProvider<TokenID> tokens;
    protected final RegistrySourcedNameProvider<AccountID> accounts;
    protected final RegistrySourcedNameProvider<TokenAccountRegistryRel> tokenRels;
    private final ResponseCodeEnum[] permissibleOutcomes =
            standardOutcomesAnd(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT, TOKEN_WAS_DELETED, ACCOUNT_DELETED);

    public RandomTokenAssociation(
            RegistrySourcedNameProvider<TokenID> tokens,
            RegistrySourcedNameProvider<AccountID> accounts,
            RegistrySourcedNameProvider<TokenAccountRegistryRel> tokenRels,
            ResponseCodeEnum[] customOutcomes) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.tokenRels = tokenRels;
        this.customOutcomes = customOutcomes;
    }

    public RandomTokenAssociation ceiling(int n) {
        ceilingNum = n;
        return this;
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
                .payingWith(account.get())
                .signedBy(account.get())
                .hasPrecheckFrom(plus(STANDARD_PERMISSIBLE_PRECHECKS, customOutcomes))
                .hasKnownStatusFrom(permissibleOutcomes);

        return Optional.of(op);
    }
}
