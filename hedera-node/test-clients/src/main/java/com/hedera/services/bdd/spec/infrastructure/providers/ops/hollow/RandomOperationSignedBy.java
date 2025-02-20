// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow;

import static com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow.RandomHollowAccount.ACCOUNT_SUFFIX;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PAYER_ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PAYER_ACCOUNT_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;

/**
 * Operation getting a random account key and signing transaction with it, completing hollow accounts in the process.
 */
abstract class RandomOperationSignedBy<T extends HapiTxnOp<T>> implements OpProvider {
    private final HapiSpecRegistry registry;

    private final RegistrySourcedNameProvider<AccountID> accounts;

    private final ResponseCodeEnum[] permissiblePrechecks =
            standardPrechecksAnd(PAYER_ACCOUNT_NOT_FOUND, ACCOUNT_DELETED, PAYER_ACCOUNT_DELETED);
    private final ResponseCodeEnum[] permissibleOutcomes =
            standardOutcomesAnd(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT, ACCOUNT_DELETED, PAYER_ACCOUNT_DELETED);

    protected RandomOperationSignedBy(HapiSpecRegistry registry, RegistrySourcedNameProvider<AccountID> accounts) {
        this.registry = registry;
        this.accounts = accounts;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        return randomHollowAccountKey().map(this::generateOpSignedBy);
    }

    private Optional<String> randomHollowAccountKey() {
        return accounts.getQualifying().filter(a -> a.endsWith(ACCOUNT_SUFFIX)).map(this::keyFromAccount);
    }

    private String keyFromAccount(String account) {
        final var key = account.replaceAll(ACCOUNT_SUFFIX + "$", "");
        final AccountID fromAccount = registry.getAccountID(account);
        registry.saveAccountId(key, fromAccount);
        registry.saveKey(account, registry.getKey(key)); // needed for HapiTokenAssociate.defaultSigners()
        return key;
    }

    private HapiSpecOperation generateOpSignedBy(String keyName) {
        return hapiTxnOp(keyName)
                .payingWith(keyName)
                .sigMapPrefixes(uniqueWithFullPrefixesFor(keyName))
                .hasPrecheckFrom(permissiblePrechecks)
                .hasKnownStatusFrom(permissibleOutcomes)
                .noLogging();
    }

    protected abstract HapiTxnOp<T> hapiTxnOp(String keyName);
}
