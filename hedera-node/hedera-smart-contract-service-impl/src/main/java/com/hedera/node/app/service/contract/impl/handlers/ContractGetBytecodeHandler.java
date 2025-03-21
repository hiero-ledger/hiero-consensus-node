// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_ONLY;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbjResponseType;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.EVM_ADDRESS_LENGTH_AS_INT;
import static com.hedera.node.app.spi.validation.Validations.mustExist;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.contract.ContractGetBytecodeQuery;
import com.hedera.hapi.node.contract.ContractGetBytecodeResponse;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.hapi.utils.fee.SmartContractFeeBuilder;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.EntityIdFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#CONTRACT_GET_BYTECODE}.
 */
@Singleton
public class ContractGetBytecodeHandler extends AbstractContractPaidQueryHandler<ContractGetBytecodeQuery> {

    private final SmartContractFeeBuilder feeBuilder = new SmartContractFeeBuilder();

    /**
     * Default constructor for injection.
     */
    @Inject
    public ContractGetBytecodeHandler(@NonNull final EntityIdFactory entityIdFactory) {
        super(entityIdFactory, Query::contractGetBytecodeOrThrow, e -> e.contractIDOrElse(ContractID.DEFAULT));
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
        // TODO Glib: use this validate in ContractCallLocalHandler.validate, ContractGetInfoHandler.validate mb
        // somewhere else?
        requireNonNull(context);
        // contract id validation
        final var contractId = getContractId(context);
        mustExist(contractId, INVALID_CONTRACT_ID);
        if (contractId.hasEvmAddress()) {
            validateTruePreCheck(
                    contractId.evmAddressOrThrow().length() == EVM_ADDRESS_LENGTH_AS_INT, INVALID_CONTRACT_ID);
        }
        // contract account/token validation
        final var contract = contractFrom(context, contractId);
        if (contract == null) {
            final var token = tokenFrom(context, contractId);
            mustExist(token, INVALID_CONTRACT_ID);
            // TODO Glib: mb return TOKEN_WAS_DELETED?
            validateFalsePreCheck(token.deleted(), CONTRACT_DELETED);
        } else {
            validateFalsePreCheck(contract.deleted(), CONTRACT_DELETED);
        }
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
            var effectiveBytecode = bytecodeFrom(context);
            if (effectiveBytecode != null) {
                contractGetBytecode.bytecode(effectiveBytecode);
            }
        }
        return Response.newBuilder()
                .contractGetBytecodeResponse(contractGetBytecode)
                .build();
    }

    @NonNull
    @Override
    public Fees computeFees(@NonNull final QueryContext context) {
        var effectiveBytecode = bytecodeFrom(context);
        if (effectiveBytecode == null) {
            effectiveBytecode = Bytes.EMPTY;
        }
        final var op = getOperation(context);
        final var responseType = op.headerOrElse(QueryHeader.DEFAULT).responseType();
        final var usage = feeBuilder.getContractByteCodeQueryFeeMatrices(
                (int) effectiveBytecode.length(), fromPbjResponseType(responseType));
        return context.feeCalculator().legacyCalculate(sigValueObj -> usage);
    }

    private Bytes bytecodeFrom(@NonNull final QueryContext context) {
        final var contractId = getContractId(context);
        if (contractId == null) {
            return null;
        } else {
            final var contract = contractFrom(context, contractId);
            if (contract == null) {
                final var token = tokenFrom(context, contractId);
                if (token == null || token.deleted()) {
                    return null;
                } else {
                    // TODO Glib: how to get token bytecode?
                    return bytecodeFrom(context, contractId);
                }
            } else {
                if (contract.deleted()) {
                    return null;
                } else {
                    // TODO Glib: what bytecode will be returned if contract or token is deleted?
                    return bytecodeFrom(context, contractId);
                }
            }
        }
    }

    private Bytes bytecodeFrom(@NonNull final QueryContext context, @NonNull final ContractID contractId) {
        final var bytecode = context.createStore(ContractStateStore.class).getBytecode(contractId);
        return bytecode == null ? null : bytecode.code();
    }
}
