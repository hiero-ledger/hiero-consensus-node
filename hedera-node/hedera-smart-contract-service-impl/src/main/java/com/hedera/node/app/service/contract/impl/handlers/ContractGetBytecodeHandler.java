// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_ONLY;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbjResponseType;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.contract.ContractGetBytecodeResponse;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.hapi.utils.fee.SmartContractFeeBuilder;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.PaidQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.EntityIdFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#CONTRACT_GET_BYTECODE}.
 */
@Singleton
public class ContractGetBytecodeHandler extends PaidQueryHandler {
    private final SmartContractFeeBuilder feeBuilder = new SmartContractFeeBuilder();
    private final EntityIdFactory entityIdFactory;

    /**
     * Default constructor for injection.
     */
    @Inject
    public ContractGetBytecodeHandler(@NonNull final EntityIdFactory entityIdFactory) {
        this.entityIdFactory = requireNonNull(entityIdFactory);
    }

    @Override
    public QueryHeader extractHeader(@NonNull final Query query) {
        requireNonNull(query);
        return query.contractGetBytecodeOrThrow().header();
    }

    @Override
    public Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = ContractGetBytecodeResponse.newBuilder().header(header);
        return Response.newBuilder().contractGetBytecodeResponse(response).build();
    }

    @Override
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        requireNonNull(context);
        final var contract = contractFrom(context);
        validateFalsePreCheck(contract == null, INVALID_CONTRACT_ID);
        validateFalsePreCheck(requireNonNull(contract).deleted(), CONTRACT_DELETED);
    }

    @Override
    public Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        requireNonNull(context);
        requireNonNull(header);
        final var contractGetBytecode = ContractGetBytecodeResponse.newBuilder().header(header);

        // although ResponseType enum includes an unsupported field ResponseType#ANSWER_STATE_PROOF,
        // the response returned ONLY when both
        // the ResponseHeader#nodeTransactionPrecheckCode is OK and the requested response type is
        // ResponseType#ANSWER_ONLY
        if (header.nodeTransactionPrecheckCode() == OK && header.responseType() == ANSWER_ONLY) {
            final var contract = requireNonNull(contractFrom(context));
            contractGetBytecode.bytecode(bytecodeFrom(context, contract));
        }
        return Response.newBuilder()
                .contractGetBytecodeResponse(contractGetBytecode)
                .build();
    }

    @NonNull
    @Override
    public Fees computeFees(@NonNull final QueryContext context) {
        final Bytes effectiveBytecode;
        final var contract = contractFrom(context);
        if (contract == null || contract.deleted()) {
            effectiveBytecode = Bytes.EMPTY;
        } else {
            effectiveBytecode = bytecodeFrom(context, contract);
        }
        final var op = context.query().contractGetBytecodeOrThrow();
        final var responseType = op.headerOrElse(QueryHeader.DEFAULT).responseType();
        final var usage = feeBuilder.getContractByteCodeQueryFeeMatrices(
                (int) effectiveBytecode.length(), fromPbjResponseType(responseType));
        return context.feeCalculator().legacyCalculate(sigValueObj -> usage);
    }

    private @Nullable Account contractFrom(@NonNull final QueryContext context) {
        final var accountsStore = context.createStore(ReadableAccountStore.class);
        final var contractId = context.query().contractGetBytecodeOrThrow().contractIDOrElse(ContractID.DEFAULT);
        final var contract = accountsStore.getContractById(contractId);
        return (contract == null || !contract.smartContract()) ? null : contract;
    }

    private Bytes bytecodeFrom(@NonNull final QueryContext context, @NonNull Account contract) {
        final var store = context.createStore(ContractStateStore.class);
        var accountId = contract.accountIdOrThrow();
        var contractNumber = accountId.accountNumOrThrow();
        var contractId = entityIdFactory.newContractId(contractNumber);
        final var bytecode = store.getBytecode(contractId);
        return bytecode.code();
    }
}
