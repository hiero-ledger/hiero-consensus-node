// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.mod;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hederahashgraph.api.proto.java.Query;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class QueryModificationsOp extends UtilOp {
    // An account to create as part of the send-with-modifications process
    // to ensure fees are charged (the default payer is a superuser account
    // that is never charged fees); this ensures we cover calculateFees()
    // code paths in handlers
    private static final String MODIFIED_CIVILIAN_PAYER = "modifiedCivilianPayer";
    private final boolean useCivilianPayer;
    private final Supplier<HapiQueryOp<?>> queryOpSupplier;
    private final Function<Query, List<QueryModification>> modificationsFn;

    public QueryModificationsOp(
            @NonNull final Supplier<HapiQueryOp<?>> queryOpSupplier,
            @NonNull final Function<Query, List<QueryModification>> modificationsFn) {
        this(true, queryOpSupplier, modificationsFn);
    }

    public QueryModificationsOp(
            final boolean useCivilianPayer,
            @NonNull final Supplier<HapiQueryOp<?>> queryOpSupplier,
            @NonNull final Function<Query, List<QueryModification>> modificationsFn) {
        this.useCivilianPayer = useCivilianPayer;
        this.queryOpSupplier = queryOpSupplier;
        this.modificationsFn = modificationsFn;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        final var unmodifiedOp = originalQuery();
        allRunFor(
                spec,
                sourcing(() -> useCivilianPayer ? cryptoCreate(MODIFIED_CIVILIAN_PAYER) : noOp()),
                unmodifiedOp,
                sourcing(() -> blockingOrder(modificationsFn.apply(unmodifiedOp.getQuery()).stream()
                        .flatMap(modification -> {
                            final var op = originalQuery();
                            modification.customize(op);
                            return Stream.of(logIt(modification.summary()), op);
                        })
                        .toArray(HapiSpecOperation[]::new))));
        return false;
    }

    private HapiQueryOp<?> originalQuery() {
        final var op = queryOpSupplier.get();
        if (useCivilianPayer) {
            op.payingWith(MODIFIED_CIVILIAN_PAYER);
        }
        return op;
    }
}
