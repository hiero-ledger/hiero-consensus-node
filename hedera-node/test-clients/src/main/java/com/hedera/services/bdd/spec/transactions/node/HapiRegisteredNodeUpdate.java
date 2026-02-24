// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.node;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.google.protobuf.StringValue;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.AdapterUtils;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.RegisteredNodeUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.RegisteredServiceEndpoint;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public class HapiRegisteredNodeUpdate extends HapiTxnOp<HapiRegisteredNodeUpdate> {
    private final LongSupplier idSupplier;

    private Optional<String> description = Optional.empty();
    private Optional<List<RegisteredServiceEndpoint>> endpoints = Optional.empty();
    private Optional<AccountID> nodeAccount = Optional.empty();
    private Optional<com.hederahashgraph.api.proto.java.Key> adminKey = Optional.empty();

    public HapiRegisteredNodeUpdate(@NonNull final LongSupplier idSupplier) {
        this.idSupplier = requireNonNull(idSupplier);
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.RegisteredNodeUpdate;
    }

    public HapiRegisteredNodeUpdate description(@NonNull final String description) {
        this.description = Optional.of(requireNonNull(description));
        return this;
    }

    public HapiRegisteredNodeUpdate serviceEndpoints(@NonNull final List<RegisteredServiceEndpoint> endpoints) {
        this.endpoints = Optional.of(requireNonNull(endpoints));
        return this;
    }

    public HapiRegisteredNodeUpdate nodeAccount(@NonNull final AccountID nodeAccount) {
        this.nodeAccount = Optional.of(requireNonNull(nodeAccount));
        return this;
    }

    public HapiRegisteredNodeUpdate adminKey(@NonNull final com.hederahashgraph.api.proto.java.Key adminKey) {
        this.adminKey = Optional.of(requireNonNull(adminKey));
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(@NonNull final HapiSpec spec) {
        final var opBody = RegisteredNodeUpdateTransactionBody.newBuilder().setRegisteredNodeId(idSupplier.getAsLong());
        description.ifPresent(d -> opBody.setDescription(StringValue.of(d)));
        endpoints.ifPresent(eps -> opBody.clearServiceEndpoint().addAllServiceEndpoint(eps));
        nodeAccount.ifPresent(opBody::setNodeAccount);
        adminKey.ifPresent(opBody::setAdminKey);
        return b -> b.setRegisteredNodeUpdate(opBody.build());
    }

    @Override
    protected void updateStateOf(@NonNull final HapiSpec spec) {
        if (actualStatus != SUCCESS) {
            return;
        }
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper();
    }

    @Override
    protected HapiRegisteredNodeUpdate self() {
        return this;
    }

    @Override
    protected long feeFor(@NonNull final HapiSpec spec, @NonNull final Transaction txn, final int numPayerKeys)
            throws Throwable {
        return spec.fees()
                .forActivityBasedOp(HederaFunctionality.RegisteredNodeUpdate, this::usageEstimate, txn, numPayerKeys);
    }

    private FeeData usageEstimate(final TransactionBody txn, final SigValueObj svo) {
        final UsageAccumulator accumulator = new UsageAccumulator();
        accumulator.addVpt(Math.max(0, svo.getTotalSigCount() - 1));
        return AdapterUtils.feeDataFrom(accumulator);
    }
}
