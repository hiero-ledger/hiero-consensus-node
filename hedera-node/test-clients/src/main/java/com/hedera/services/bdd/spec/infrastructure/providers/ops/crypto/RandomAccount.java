// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ALIAS_ALREADY_ASSIGNED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.KEY_REQUIRED;
import static java.util.Collections.EMPTY_LIST;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class RandomAccount implements OpProvider {
    public static final int DEFAULT_CEILING_NUM = 100;
    public static final long INITIAL_BALANCE = 1_000_000_000L;
    static final long SEND_THRESHOLD = INITIAL_BALANCE / 50;

    private int ceilingNum = DEFAULT_CEILING_NUM;

    private final boolean fuzzIdentifiers;
    private final AtomicInteger opNo = new AtomicInteger();
    private final EntityNameProvider keys;
    private final RegistrySourcedNameProvider<AccountID> accounts;
    private final ResponseCodeEnum[] permissibleOutcomes =
            standardOutcomesAnd(INVALID_ACCOUNT_ID, INVALID_ALIAS_KEY, ALIAS_ALREADY_ASSIGNED);
    private final ResponseCodeEnum[] permissiblePrechecks =
            standardPrechecksAnd(KEY_REQUIRED, INVALID_ALIAS_KEY, ALIAS_ALREADY_ASSIGNED, INVALID_SIGNATURE);

    public RandomAccount(EntityNameProvider keys, RegistrySourcedNameProvider<AccountID> accounts) {
        this(keys, accounts, false);
    }

    public RandomAccount(
            EntityNameProvider keys, RegistrySourcedNameProvider<AccountID> accounts, final boolean fuzzIdentifiers) {
        this.keys = keys;
        this.accounts = accounts;
        this.fuzzIdentifiers = fuzzIdentifiers;
    }

    public RandomAccount ceiling(int n) {
        ceilingNum = n;
        return this;
    }

    @Override
    public List<SpecOperation> suggestedInitializers() {
        return fuzzIdentifiers ? EMPTY_LIST : List.of(newKeyNamed(my("simpleKey")));
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        if (accounts.numPresent() >= ceilingNum) {
            return Optional.empty();
        }

        Optional<String> key = keys.getQualifying();
        if (key.isEmpty()) {
            return Optional.empty();
        }

        int id = opNo.getAndIncrement();
        final var op = cryptoCreate(key.get())
                .payingWith(key.get())
                .key(key.get())
                .fuzzingIdentifiersIfEcdsaKey(fuzzIdentifiers)
                .memo("randomlycreated" + id)
                .balance(INITIAL_BALANCE)
                .sendThreshold(SEND_THRESHOLD)
                .hasPrecheckFrom(permissiblePrechecks)
                .hasKnownStatusFrom(permissibleOutcomes);
        return Optional.of(op);
    }

    private String my(String opName) {
        return unique(opName, RandomAccount.class);
    }
}
