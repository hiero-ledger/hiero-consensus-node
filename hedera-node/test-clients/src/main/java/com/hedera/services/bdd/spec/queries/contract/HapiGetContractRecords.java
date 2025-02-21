// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.contract;

import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;

import com.google.common.base.MoreObjects;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.ContractGetRecordsQuery;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;

public class HapiGetContractRecords extends HapiQueryOp<HapiGetContractRecords> {
    private final String contract;

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.ContractGetRecords;
    }

    @Override
    protected HapiGetContractRecords self() {
        return this;
    }

    public HapiGetContractRecords(String contract) {
        this.contract = contract;
    }

    @Override
    protected void assertExpectationsGiven(HapiSpec spec) throws Throwable {
        // No-op, this query no longer supported
    }

    @Override
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        // No-op, this query no longer supported
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Query queryFor(
            @NonNull final HapiSpec spec,
            @NonNull final Transaction payment,
            @NonNull final ResponseType responseType) {
        return getContractRecordsQuery(spec, payment, responseType == ResponseType.COST_ANSWER);
    }

    private Query getContractRecordsQuery(HapiSpec spec, Transaction payment, boolean costOnly) {
        var id = TxnUtils.asContractId(contract, spec);
        ContractGetRecordsQuery opQuery = ContractGetRecordsQuery.newBuilder()
                .setHeader(costOnly ? answerCostHeader(payment) : answerHeader(payment))
                .setContractID(id)
                .build();
        return Query.newBuilder().setContractGetRecords(opQuery).build();
    }

    @Override
    protected long costOnlyNodePayment(HapiSpec spec) throws Throwable {
        return spec.fees().forOp(HederaFunctionality.ContractGetRecords, FeeBuilder.getCostForQueryByIdOnly());
    }

    @Override
    protected boolean needsPayment() {
        return true;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        MoreObjects.ToStringHelper helper = super.toStringHelper().add("contract", contract);
        Optional.ofNullable(response)
                .ifPresent(r -> helper.add(
                        "# records",
                        r.getContractGetRecordsResponse().getRecordsList().size()));
        return helper;
    }
}
