// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions;

import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class HapiExplicitTxn extends HapiTxnOp<HapiExplicitTxn> {
    private final HederaFunctionality function;
    private final BiConsumer<HapiSpec, TransactionBody.Builder> explicitDef;

    public HapiExplicitTxn(
            @NonNull final HederaFunctionality function,
            @NonNull final BiConsumer<HapiSpec, TransactionBody.Builder> explicitDef) {
        this.function = requireNonNull(function);
        this.explicitDef = requireNonNull(explicitDef);
    }

    @Override
    public HederaFunctionality type() {
        return function;
    }

    @Override
    protected HapiExplicitTxn self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        return b -> explicitDef.accept(spec, b);
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
        return ONE_HBAR;
    }
}
