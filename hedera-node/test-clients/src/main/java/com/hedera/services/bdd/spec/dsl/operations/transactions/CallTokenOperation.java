// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.operations.transactions;

import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.allRequiredCallEntities;
import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.withSubstitutedTypes;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.contracts.TokenRedirectContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecToken;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCall;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;

public class CallTokenOperation extends AbstractSpecTransaction<CallTokenOperation, HapiContractCall>
        implements SpecOperation {
    private static final long DEFAULT_GAS = 100_000;

    private final SpecToken target;
    private final String function;
    private final Object[] parameters;
    private final TokenRedirectContract redirectContract;
    private boolean wrappedInBatchOperation = false;
    private String batchOperator = null;
    private ResponseCodeEnum batchExpectedPreCheckStatus = null;
    private ResponseCodeEnum batchExpectedKnownStatus = null;

    private long gas = DEFAULT_GAS;

    public CallTokenOperation(
            @NonNull final SpecToken target,
            @NonNull final TokenRedirectContract redirectContract,
            @NonNull final String function,
            @NonNull final Object... parameters) {
        super(allRequiredCallEntities(target, parameters));
        this.target = requireNonNull(target);
        this.function = requireNonNull(function);
        this.parameters = requireNonNull(parameters);
        this.redirectContract = requireNonNull(redirectContract);
    }

    /**
     * Sets the gas to be used for the call.
     *
     * @param gas the gas
     * @return this
     */
    public CallTokenOperation gas(final long gas) {
        this.gas = gas;
        return this;
    }

    @NonNull
    @Override
    protected SpecOperation computeDelegate(@NonNull HapiSpec spec) {
        final var network = spec.targetNetworkOrThrow();
        final var abi = getABIFor(FUNCTION, function, redirectContract.abiResource());
        final var arguments = withSubstitutedTypes(network, parameters);
        final var op = contractCallWithFunctionAbi(target.addressOn(network).toString(), abi, arguments)
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

    public CallTokenOperation wrappedInBatchOperation(String batchOperator) {
        this.wrappedInBatchOperation = true;
        this.batchOperator = batchOperator;
        return this;
    }

    public CallTokenOperation wrappedInBatchOperation(
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
    protected CallTokenOperation self() {
        return this;
    }
}
