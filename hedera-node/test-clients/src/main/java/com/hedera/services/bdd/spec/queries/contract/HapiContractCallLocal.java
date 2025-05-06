// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.contract;

import static com.hedera.services.bdd.spec.HapiPropertySourceStaticInitializer.REALM;
import static com.hedera.services.bdd.spec.HapiPropertySourceStaticInitializer.SHARD;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.rethrowSummaryError;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;
import static com.hedera.services.bdd.spec.transactions.contract.HapiContractCall.HEXED_EVM_ADDRESS_LEN;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.encodeParametersForCall;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.spec.assertions.ErroringAsserts;
import com.hedera.services.bdd.spec.infrastructure.meta.ActionableContractCallLocal;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.ContractCallLocalQuery;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.utility.CommonUtils;

public class HapiContractCallLocal extends HapiQueryOp<HapiContractCallLocal> {
    private static final Logger LOG = LogManager.getLogger(HapiContractCallLocal.class);

    private static final String FALLBACK_ABI = "<empty>";
    private String abi;
    private String contract;
    private Object[] params;
    private Optional<Long> gas = Optional.empty();
    private Optional<Long> maxResultSize = Optional.empty();
    private Optional<String> details = Optional.empty();
    private Optional<String> saveResultToEntry = Optional.empty();
    private Optional<ContractFnResultAsserts> expectations = Optional.empty();
    private Optional<Function<HapiSpec, Object[]>> paramsFn = Optional.empty();
    private Optional<Consumer<Object[]>> typedResultsObs = Optional.empty();

    @Nullable
    private byte[] explicitRawParams;

    @Nullable
    private Consumer<byte[]> rawResultsObs;

    @Nullable
    private Consumer<ContractFunctionResult> resultsObs;

    @Nullable
    private Object[] expectedResults;

    @Nullable
    private BiConsumer<ResponseCodeEnum, ContractFunctionResult> fullResultsObs;

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.ContractCallLocal;
    }

    public static HapiContractCallLocal fromDetails(String actionable) {
        HapiContractCallLocal localCall = new HapiContractCallLocal();
        localCall.details = Optional.of(actionable);
        return localCall;
    }

    private HapiContractCallLocal() {}

    public HapiContractCallLocal(String abi, String contract, Object... params) {
        this.abi = abi;
        this.contract = contract;
        this.params = params;
    }

    public HapiContractCallLocal(String abi, String contract, Function<HapiSpec, Object[]> fn) {
        this(abi, contract);
        paramsFn = Optional.of(fn);
    }

    public HapiContractCallLocal(String contract, byte[] rawParams) {
        this.contract = contract;
        this.explicitRawParams = rawParams;
    }

    public HapiContractCallLocal has(ContractFnResultAsserts provider) {
        expectations = Optional.of(provider);
        return this;
    }

    public HapiContractCallLocal exposingResultTo(final Consumer<ContractFunctionResult> resultsObs) {
        this.resultsObs = resultsObs;
        return this;
    }

    public HapiContractCallLocal hasResult(@NonNull final Object... expected) {
        return exposingTypedResultsTo(actual -> assertArrayEquals(expected, actual));
    }

    public HapiContractCallLocal exposingFullResultTo(
            final BiConsumer<ResponseCodeEnum, ContractFunctionResult> fullResultsObs) {
        this.fullResultsObs = fullResultsObs;
        return this;
    }

    public HapiContractCallLocal exposingRawResultsTo(final Consumer<byte[]> obs) {
        rawResultsObs = obs;
        return this;
    }

    public HapiContractCallLocal exposingTypedResultsTo(final Consumer<Object[]> obs) {
        typedResultsObs = Optional.of(obs);
        return this;
    }

    public HapiContractCallLocal maxResultSize(long size) {
        maxResultSize = Optional.of(size);
        return this;
    }

    public HapiContractCallLocal gas(long amount) {
        gas = Optional.of(amount);
        return this;
    }

    public HapiContractCallLocal saveResultTo(String key) {
        saveResultToEntry = Optional.of(key);
        return this;
    }

    @Override
    protected void assertExpectationsGiven(HapiSpec spec) throws Throwable {
        if (expectations.isPresent()) {
            ContractFunctionResult actual = response.getContractCallLocal().getFunctionResult();
            if (!loggingOff) {
                final String hex =
                        CommonUtils.hex(actual.getContractCallResult().toByteArray());
                LOG.info(hex);
            }
            ErroringAsserts<ContractFunctionResult> asserts = expectations.get().assertsFor(spec);
            List<Throwable> errors = asserts.errorsIn(actual);
            rethrowSummaryError(LOG, "Bad local call result!", errors);
        }
    }

    @Override
    protected HapiContractCallLocal self() {
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        if (verboseLoggingOn) {
            LOG.info(
                    "{}{} result = {}",
                    spec::logPrefix,
                    () -> this,
                    response.getContractCallLocal()::getFunctionResult);
        }

        if (resultsObs != null) {
            resultsObs.accept(response.getContractCallLocal().getFunctionResult());
        }
        if (fullResultsObs != null) {
            fullResultsObs.accept(
                    response.getContractCallLocal().getHeader().getNodeTransactionPrecheckCode(),
                    response.getContractCallLocal().getFunctionResult());
        }
        final var rawResult =
                response.getContractCallLocal().getFunctionResult().getContractCallResult();
        saveResultToEntry.ifPresent(s -> spec.registry().saveBytes(s, rawResult));
        if (typedResultsObs.isPresent()) {
            final var function = com.esaulpaugh.headlong.abi.Function.fromJson(abi);
            if (rawResult.size() > 0) {
                final var typedResult = function.decodeReturn(rawResult.toByteArray());
                typedResultsObs.get().accept(typedResult.toArray());
            } else {
                typedResultsObs.get().accept(new Object[1]);
            }
        }
        if (rawResultsObs != null) {
            rawResultsObs.accept(rawResult.toByteArray());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Query queryFor(
            @NonNull final HapiSpec spec,
            @NonNull final Transaction payment,
            @NonNull final ResponseType responseType) {
        return getContractCallLocal(spec, payment, responseType == ResponseType.COST_ANSWER);
    }

    private Query getContractCallLocal(HapiSpec spec, Transaction payment, boolean costOnly) {
        final byte[] callData;
        if (explicitRawParams == null) {
            if (details.isPresent()) {
                ActionableContractCallLocal actionable = spec.registry().getActionableLocalCall(details.get());
                contract = actionable.getContract();
                abi = actionable.getDetails().getAbi();
                params = actionable.getDetails().getExampleArgs();
            } else if (paramsFn.isPresent()) {
                params = paramsFn.get().apply(spec);
            }
            callData = encodeParametersForCall(params, abi);
        } else {
            callData = explicitRawParams;
        }

        @SuppressWarnings("java:S1874")
        final var opBuilder = ContractCallLocalQuery.newBuilder()
                .setHeader(costOnly ? answerCostHeader(payment) : answerHeader(payment))
                .setFunctionParameters(ByteString.copyFrom(callData))
                .setGas(gas.orElse(spec.setup().defaultCallGas()))
                .setMaxResultSize(maxResultSize.orElse(spec.setup().defaultMaxLocalCallRetBytes()));

        final var effContract = contract.startsWith("0x") ? contract.substring(2) : contract;
        if (effContract.length() == HEXED_EVM_ADDRESS_LEN) {
            opBuilder.setContractID(ContractID.newBuilder()
                    .setShardNum(SHARD)
                    .setRealmNum(REALM)
                    .setEvmAddress(ByteString.copyFrom(CommonUtils.unhex(effContract))));
        } else {
            opBuilder.setContractID(TxnUtils.asContractId(contract, spec));
        }
        return Query.newBuilder().setContractCallLocal(opBuilder).build();
    }

    @Override
    protected long costOnlyNodePayment(HapiSpec spec) {
        return spec.fees().forOp(HederaFunctionality.ContractCallLocal, FeeBuilder.getCostForQueryByIdOnly());
    }

    @Override
    protected boolean needsPayment() {
        return true;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        MoreObjects.ToStringHelper helper =
                super.toStringHelper().add("contract", contract).add("abi", abi).add("params", Arrays.toString(params));
        gas.ifPresent(a -> helper.add("gas", a));
        maxResultSize.ifPresent(s -> helper.add("maxResultSize", s));
        return helper;
    }
}
