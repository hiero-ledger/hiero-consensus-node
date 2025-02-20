// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.mod;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.suites.HapiSuite.THOUSAND_HBAR;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * An operation that submits a transaction and then resubmits modified versions
 * of it based on modifications returned by a function that will receive the
 * original transaction.
 */
public class SubmitModificationsOp extends UtilOp {
    // An account to create as part of the submit-with-modifications process
    // to ensure fees are charged (the default payer is a superuser account
    // that is never charged fees); this ensures we cover calculateFees()
    // code paths in handlers
    private static final String MODIFIED_CIVILIAN_PAYER = "modifiedCivilianPayer";
    private final boolean useCivilianPayer;
    private final Supplier<HapiTxnOp<?>> txnOpSupplier;
    private final Function<Transaction, List<TxnModification>> modificationsFn;

    public SubmitModificationsOp(
            @NonNull final Supplier<HapiTxnOp<?>> txnOpSupplier,
            @NonNull final Function<Transaction, List<TxnModification>> modificationsFn) {
        this(true, txnOpSupplier, modificationsFn);
    }

    public SubmitModificationsOp(
            final boolean useCivilianPayer,
            @NonNull final Supplier<HapiTxnOp<?>> txnOpSupplier,
            @NonNull final Function<Transaction, List<TxnModification>> modificationsFn) {
        this.useCivilianPayer = useCivilianPayer;
        this.txnOpSupplier = txnOpSupplier;
        this.modificationsFn = modificationsFn;
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final List<TxnModification> modifications = new ArrayList<>();
        allRunFor(
                spec,
                sourcing(() ->
                        useCivilianPayer ? cryptoCreate(MODIFIED_CIVILIAN_PAYER).balance(10 * THOUSAND_HBAR) : noOp()),
                preModifiedTransaction().withTxnTransform(txn -> {
                    modifications.addAll(modificationsFn.apply(txn));
                    return txn;
                }),
                sourcing(() -> blockingOrder(modifications.stream()
                        .flatMap(modification -> {
                            final var op = txnOpSupplier.get();
                            modification.customize(op);
                            return Stream.of(logIt(modification.summary()), op);
                        })
                        .toArray(HapiSpecOperation[]::new))));
        return false;
    }

    private HapiTxnOp<?> preModifiedTransaction() {
        final var op = txnOpSupplier.get();
        if (useCivilianPayer) {
            op.payingWith(MODIFIED_CIVILIAN_PAYER);
        }
        return op;
    }
}
