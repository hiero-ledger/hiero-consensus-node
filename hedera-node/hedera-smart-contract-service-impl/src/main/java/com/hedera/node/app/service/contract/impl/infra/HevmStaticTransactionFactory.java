// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.infra;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_GAS_LIMIT_EXCEEDED;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction.NOT_APPLICABLE;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asPriorityId;
import static com.hedera.node.app.service.contract.impl.utils.ValidationUtils.getMaxGasLimit;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;
import static org.apache.tuweni.bytes.Bytes.EMPTY;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.contract.ContractCallLocalQuery;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.service.contract.impl.annotations.QueryScope;
import com.hedera.node.app.service.contract.impl.exec.gas.HederaGasCalculator;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;

/**
 * A factory that creates a {@link HederaEvmTransaction} for static calls.
 * Used for handling the {@link ContractCallLocalQuery} flow.
 * Hevm in {@link HevmStaticTransactionFactory} is abbreviated for Hedera EVM.
 */
@QueryScope
public class HevmStaticTransactionFactory {
    private final ContractsConfig contractsConfig;
    private final HederaGasCalculator gasCalculator;
    private final QueryContext context;
    private final AccountID payerId;

    /**
     * @param context       the context of this query
     * @param gasCalculator the gas calculator for this query
     */
    @Inject
    public HevmStaticTransactionFactory(
            @NonNull final QueryContext context, @NonNull final HederaGasCalculator gasCalculator) {
        this.context = requireNonNull(context);
        this.contractsConfig = context.configuration().getConfigData(ContractsConfig.class);
        this.gasCalculator = requireNonNull(gasCalculator);
        this.payerId = requireNonNull(context.payer());
    }

    /**
     * Given a {@link Query}, creates the implied {@link HederaEvmTransaction}.
     *
     * @param query the {@link ContractCallLocalQuery} to convert
     * @return the implied {@link HederaEvmTransaction}
     */
    @NonNull
    public HederaEvmTransaction fromHapiQuery(@NonNull final Query query) {
        final var op = query.contractCallLocalOrThrow();
        assertValidCall(op);
        final var senderId = op.hasSenderId() ? op.senderIdOrThrow() : payerId;
        // For mono-service fidelity, allow calls using 0.0.X id even to contracts with a priority EVM address
        final var targetId = asPriorityId(op.contractIDOrThrow(), context.createStore(ReadableAccountStore.class));
        return new HederaEvmTransaction(
                senderId,
                null,
                targetId,
                NOT_APPLICABLE,
                op.functionParameters(),
                null,
                0L,
                op.gas(),
                1L,
                0L,
                null,
                null,
                null,
                null);
    }

    /**
     * Given a {@link Query} and an {@link Exception},
     * create and return a {@link HederaEvmTransaction} containing the exception and gas limit
     *
     * @param query     the {@link ContractCallLocalQuery} to convert
     * @param exception the {@link Exception} to wrap
     * @return the implied {@link HederaEvmTransaction}
     */
    @NonNull
    public HederaEvmTransaction fromHapiQueryException(
            @NonNull final Query query, @NonNull final HandleException exception) {
        final var op = query.contractCallLocalOrThrow();
        final var senderId = op.hasSenderId() ? op.senderIdOrThrow() : payerId;
        // For mono-service fidelity, allow calls using 0.0.X id even to contracts with a priority EVM address
        final var targetId = asPriorityId(op.contractIDOrThrow(), context.createStore(ReadableAccountStore.class));
        return new HederaEvmTransaction(
                senderId,
                null,
                targetId,
                NOT_APPLICABLE,
                op.functionParameters(),
                null,
                0L,
                op.gas(),
                1L,
                0L,
                null,
                null,
                exception,
                null);
    }

    private void assertValidCall(@NonNull final ContractCallLocalQuery body) {
        // baselineCost is 0 for contract calls as neither access list nor EIP-7702 authorizations are supported
        final var gasRequirements = gasCalculator.transactionGasRequirements(EMPTY, false, 0);
        validateTrue(body.gas() >= gasRequirements.minimumGasUsed(), INSUFFICIENT_GAS);
        validateTrue(body.gas() <= getMaxGasLimit(contractsConfig), MAX_GAS_LIMIT_EXCEEDED);
    }
}
