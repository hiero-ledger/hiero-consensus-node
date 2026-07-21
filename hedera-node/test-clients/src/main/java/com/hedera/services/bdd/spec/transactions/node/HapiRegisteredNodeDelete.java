// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.node;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.RegisteredNodeDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public class HapiRegisteredNodeDelete extends HapiTxnOp<HapiRegisteredNodeDelete> {
    @Nullable
    private final LongSupplier idSupplier;

    @Nullable
    private final String registeredNodeName;

    public HapiRegisteredNodeDelete(@NonNull final LongSupplier idSupplier) {
        this.idSupplier = requireNonNull(idSupplier);
        this.registeredNodeName = null;
    }

    public HapiRegisteredNodeDelete(@NonNull final String registeredNodeName) {
        this.registeredNodeName = requireNonNull(registeredNodeName);
        this.idSupplier = null;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.RegisteredNodeDelete;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(@NonNull final HapiSpec spec) {
        final long nodeId = registeredNodeName != null
                ? spec.registry().getRegisteredNodeId(registeredNodeName)
                : idSupplier.getAsLong();
        final var opBody = RegisteredNodeDeleteTransactionBody.newBuilder()
                .setRegisteredNodeId(nodeId)
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
}
