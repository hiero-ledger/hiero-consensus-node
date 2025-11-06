// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto;

import static com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomAccount.INITIAL_BALANCE;
import static com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomAccount.SEND_THRESHOLD;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PAYER_ACCOUNT_DELETED;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.LookupUtils;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

public class RandomTransfer implements OpProvider {

    public static final int DEFAULT_NUM_STABLE_ACCOUNTS = 200;
    public static final double DEFAULT_RECORD_PROBABILITY = 0.0;

    private final ResponseCodeEnum[] permissibleOutcomes = standardOutcomesAnd(
            ACCOUNT_DELETED, INSUFFICIENT_ACCOUNT_BALANCE, PAYER_ACCOUNT_DELETED, INVALID_SIGNATURE);

    private final ResponseCodeEnum[] customOutcomes;

    public double recordProb = DEFAULT_RECORD_PROBABILITY;

    protected final EntityNameProvider accounts;

    private int numStableAccounts = DEFAULT_NUM_STABLE_ACCOUNTS;
    private final SplittableRandom r = new SplittableRandom();

    public RandomTransfer(EntityNameProvider accounts, ResponseCodeEnum[] customOutcomes) {
        this.customOutcomes = customOutcomes;
        this.accounts = accounts;
    }

    public RandomTransfer numStableAccounts(int n) {
        numStableAccounts = n;
        return this;
    }

    public RandomTransfer recordProbability(double p) {
        recordProb = p;
        return this;
    }

    private String my(String opName) {
        return unique(opName, RandomTransfer.class);
    }

    public static Set<String> stableAccounts(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> String.format("stable-account%d", i))
                .collect(toSet());
    }

    @Override
    public List<SpecOperation> suggestedInitializers() {
        return stableAccounts(numStableAccounts).stream()
                .map(account -> cryptoCreate(my(account))
                        .noLogging()
                        .balance(INITIAL_BALANCE)
                        .deferStatusResolution()
                        .payingWith(UNIQUE_PAYER_ACCOUNT)
                        .rechargeWindow(3))
                .collect(toList());
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        final var involved = LookupUtils.twoDistinct(accounts);
        if (involved.isEmpty()) {
            return Optional.empty();
        }

        boolean shouldCreateRecord = r.nextDouble() < recordProb;
        long amount = shouldCreateRecord ? (SEND_THRESHOLD + 1) : 1;
        String from = involved.get().getKey(), to = involved.get().getValue();

        HapiCryptoTransfer op = cryptoTransfer(tinyBarsFromTo(from, to, amount))
                .payingWith(from)
                .signedBy(from)
                .hasPrecheckFrom(plus(STANDARD_PERMISSIBLE_PRECHECKS, customOutcomes))
                .hasKnownStatusFrom(plus(permissibleOutcomes, customOutcomes));

        return Optional.of(op);
    }
}
