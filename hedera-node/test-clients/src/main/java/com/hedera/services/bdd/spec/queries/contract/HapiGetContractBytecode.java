// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.contract;

import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;
import static com.hedera.services.bdd.spec.transactions.contract.HapiContractCall.HEXED_EVM_ADDRESS_LEN;
import static org.hiero.consensus.model.utility.CommonUtils.unhex;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.ContractGetBytecodeQuery;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assertions;

public class HapiGetContractBytecode extends HapiQueryOp<HapiGetContractBytecode> {
    private final String contract;
    private Optional<byte[]> expected = Optional.empty();
    private Optional<Consumer<byte[]>> bytecodeObs = Optional.empty();
    private Optional<String> saveResultToEntry = Optional.empty();
    private boolean hasExpectations = false;

    public HapiGetContractBytecode(String contract) {
        this.contract = contract;
    }

    public HapiGetContractBytecode isNonEmpty() {
        hasExpectations = true;
        return this;
    }

    public HapiGetContractBytecode hasBytecode(byte[] c) {
        expected = Optional.of(c);
        return this;
    }

    public HapiGetContractBytecode exposingBytecodeTo(Consumer<byte[]> obs) {
        bytecodeObs = Optional.of(obs);
        return this;
    }

    public HapiGetContractBytecode saveResultTo(String key) {
        saveResultToEntry = Optional.of(key);
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.ContractGetBytecode;
    }

    @Override
    protected HapiGetContractBytecode self() {
        return this;
    }

    @Override
    @SuppressWarnings("java:S5960")
    protected void assertExpectationsGiven(HapiSpec spec) throws Throwable {
        if (hasExpectations) {
            Assertions.assertFalse(
                    response.getContractGetBytecodeResponse().getBytecode().isEmpty(), "Empty " + "bytecode!");
        }
        expected.ifPresent(bytes -> Assertions.assertArrayEquals(
                bytes, response.getContractGetBytecodeResponse().getBytecode().toByteArray(), "Wrong bytecode!"));
    }

    @Override
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        final var code = response.getContractGetBytecodeResponse().getBytecode();
        saveResultToEntry.ifPresent(s -> spec.registry().saveBytes(s, code));
        bytecodeObs.ifPresent(obs -> obs.accept(code.toByteArray()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Query queryFor(
            @NonNull final HapiSpec spec,
            @NonNull final Transaction payment,
            @NonNull final ResponseType responseType) {
        return getContractBytecodeQuery(spec, payment, responseType == ResponseType.COST_ANSWER);
    }

    private Query getContractBytecodeQuery(HapiSpec spec, Transaction payment, boolean costOnly) {
        final ContractID resolvedTarget;
        if (contract.length() == HEXED_EVM_ADDRESS_LEN) {
            resolvedTarget = ContractID.newBuilder()
                    .setEvmAddress(ByteString.copyFrom(unhex(contract)))
                    .build();
        } else {
            resolvedTarget = TxnUtils.asContractId(contract, spec);
        }
        ContractGetBytecodeQuery query = ContractGetBytecodeQuery.newBuilder()
                .setHeader(costOnly ? answerCostHeader(payment) : answerHeader(payment))
                .setContractID(resolvedTarget)
                .build();
        return Query.newBuilder().setContractGetBytecode(query).build();
    }

    @Override
    protected long costOnlyNodePayment(HapiSpec spec) {
        return spec.fees().forOp(HederaFunctionality.ContractGetBytecode, FeeBuilder.getCostForQueryByIdOnly());
    }

    @Override
    protected boolean needsPayment() {
        return true;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("contract", contract);
    }
}
