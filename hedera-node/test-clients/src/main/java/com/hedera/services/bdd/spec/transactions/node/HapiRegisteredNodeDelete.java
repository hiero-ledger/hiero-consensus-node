// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.node;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.AdapterUtils;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.RegisteredNodeDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public class HapiRegisteredNodeDelete extends HapiTxnOp<HapiRegisteredNodeDelete> {
    private final LongSupplier idSupplier;

    public HapiRegisteredNodeDelete(@NonNull final LongSupplier idSupplier) {
        this.idSupplier = requireNonNull(idSupplier);
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.RegisteredNodeDelete;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(@NonNull final HapiSpec spec) {
        final var opBody = RegisteredNodeDeleteTransactionBody.newBuilder()
                .setRegisteredNodeId(idSupplier.getAsLong())
                .build();
        return b -> b.setRegisteredNodeDelete(opBody);
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper();
    }

    @Override
    protected HapiRegisteredNodeDelete self() {
        return this;
    }

    @Override
    protected long feeFor(@NonNull final HapiSpec spec, @NonNull final Transaction txn, final int numPayerKeys)
            throws Throwable {
        return spec.fees()
                .forActivityBasedOp(HederaFunctionality.RegisteredNodeDelete, this::usageEstimate, txn, numPayerKeys);
    }

    private FeeData usageEstimate(final TransactionBody txn, final SigValueObj svo) {
        final UsageAccumulator accumulator = new UsageAccumulator();
        accumulator.addVpt(Math.max(0, svo.getTotalSigCount() - 1));
        return AdapterUtils.feeDataFrom(accumulator);
    }
}
