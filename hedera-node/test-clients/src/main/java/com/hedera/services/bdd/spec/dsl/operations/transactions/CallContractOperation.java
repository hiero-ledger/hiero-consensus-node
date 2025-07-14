// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.operations.transactions;

import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.allRequiredCallEntities;
import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.withSubstitutedTypes;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCall;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Represents a call to a smart contract.
 */
public class CallContractOperation extends AbstractSpecTransaction<CallContractOperation, HapiContractCall>
        implements SpecOperation {
    private static final long DEFAULT_GAS = 100_000;

    private SpecContract target;
    private final String function;
    private final Object[] parameters;
    private long gas = DEFAULT_GAS;
    private long sendValue;
    private String txnName;
    private Consumer<Object[]> resultObserver;
    private boolean wrappedInBatchOperation = false;
    private String batchOperator = null;
    private ResponseCodeEnum batchExpectedPreCheckStatus = null;
    private ResponseCodeEnum batchExpectedKnownStatus = null;

    public CallContractOperation(
            @NonNull final SpecContract target, @NonNull final String function, @NonNull final Object... parameters) {
        super(allRequiredCallEntities(target, parameters));
        this.target = requireNonNull(target);
        this.function = requireNonNull(function);
        this.parameters = requireNonNull(parameters);
    }

    @NonNull
    @Override
    protected SpecOperation computeDelegate(@NonNull final HapiSpec spec) {
        final var op = contractCall(
                        Optional.of(target.variant()),
                        target.name(),
                        function,
                        withSubstitutedTypes(spec.targetNetworkOrThrow(), parameters))
                .sending(sendValue)
                .via(txnName)
                .exposingResultTo(resultObserver)
                .gas(gas);
        maybeAssertions().ifPresent(a -> a.accept(op));
        return wrappedInBatchOperation
                ? atomicBatch(op.batchKey(batchOperator))
                        .payingWith(batchOperator)
                        .hasPrecheck(
                                batchExpectedPreCheckStatus != null ? batchExpectedPreCheckStatus : ResponseCodeEnum.OK)
                        .hasKnownStatus(
                                batchExpectedKnownStatus != null ? batchExpectedKnownStatus : ResponseCodeEnum.SUCCESS)
                : op;
    }

    /**
     * Sets the gas to be used for the call.
     * @param gas the gas
     * @return this
     */
    public CallContractOperation gas(final long gas) {
        this.gas = gas;
        return this;
    }

    /**
     * Sets the value to be sent with the call.
     * @param value the "msg.value" for the call
     * @return this
     */
    public CallContractOperation sending(final long value) {
        this.sendValue = value;
        return this;
    }

    public CallContractOperation via(final String txnName) {
        this.txnName = txnName;
        return this;
    }

    public CallContractOperation exposingResultTo(final Consumer<Object[]> observer) {
        this.resultObserver = observer;
        return this;
    }

    public CallContractOperation wrappedInBatchOperation(String batchOperator) {
        this.wrappedInBatchOperation = true;
        this.batchOperator = batchOperator;
        return this;
    }

    public CallContractOperation wrappedInBatchOperation(
            String batchOperator,
            ResponseCodeEnum batchExpectedPreCheckStatus,
            ResponseCodeEnum batchExpectedKnownStatus) {
        this.wrappedInBatchOperation = true;
        this.batchOperator = batchOperator;
        this.batchExpectedPreCheckStatus = batchExpectedPreCheckStatus;
        this.batchExpectedKnownStatus = batchExpectedKnownStatus;
        return this;
    }

    @Override
    protected CallContractOperation self() {
        return this;
    }
}
