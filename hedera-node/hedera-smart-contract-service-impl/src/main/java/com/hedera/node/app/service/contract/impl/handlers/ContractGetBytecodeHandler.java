// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_ONLY;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbjResponseType;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.contract.ContractGetBytecodeQuery;
import com.hedera.hapi.node.contract.ContractGetBytecodeResponse;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.hapi.utils.fee.SmartContractFeeBuilder;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.app.service.contract.impl.utils.RedirectBytecodeUtils;
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
        requireNonNull(context);
        final ContractID contractId;
        final Account contract;
        final Token token;
        final Schedule schedule;
        if ((contractId = getContractId(context)) == null) {
            throw new PreCheckException(INVALID_CONTRACT_ID);
        } else if ((contract = accountFrom(context, contractId)) != null) {
            if (contract.deleted()) {
                throw new PreCheckException(CONTRACT_DELETED);
            }
        } else if ((token = tokenFrom(context, contractId)) != null) {
            if (token.deleted()) {
                throw new PreCheckException(CONTRACT_DELETED);
            }
        } else if ((schedule = scheduleFrom(context, contractId)) != null) {
            if (schedule.deleted()) {
                throw new PreCheckException(CONTRACT_DELETED);
            }
        } else {
            throw new PreCheckException(INVALID_CONTRACT_ID);
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

    /**
     * Checks if the current contractId from the context is: account(contract), token or schedule and return the related
     * bytecode
     *
     * @param context Context of a single query. Contains all query specific information.
     * @return Bytecode
     */
    private Bytes bytecodeFrom(@NonNull final QueryContext context) {
        final ContractID contractId;
        final Account account;
        final Token token;
        final Schedule schedule;
        if ((contractId = getContractId(context)) == null) {
            return null;
        } else if ((account = accountFrom(context, contractId)) != null) {
            if (account.deleted()) {
                return null;
            } else if (account.smartContract()) {
                return bytecodeFrom(context, account);
            } else {
                return RedirectBytecodeUtils.accountProxyBytecodePjb(
                        ConversionUtils.contractIDToBesuAddress(entityIdFactory, contractId));
            }
        } else if ((token = tokenFrom(context, contractId)) != null) {
            if (token.deleted()) {
                return null;
            } else {
                return RedirectBytecodeUtils.tokenProxyBytecodePjb(
                        ConversionUtils.contractIDToBesuAddress(entityIdFactory, contractId));
            }
        } else if ((schedule = scheduleFrom(context, contractId)) != null) {
            if (schedule.deleted()) {
                return null;
            } else {
                return RedirectBytecodeUtils.scheduleProxyBytecodePjb(
                        ConversionUtils.contractIDToBesuAddress(entityIdFactory, contractId));
            }
        } else {
            return null;
        }
    }

    /**
     * Getting bytecode by contract account
     * <p>
     * We are getting bytecode from Account, but not from initial ContractID,
     * because initial ContractID can be an alias to real account.
     *
     * @param context Context of a single query. Contains all query specific information.
     * @param contract the account of the contract
     * @return the bytecode
     */
    private Bytes bytecodeFrom(@NonNull final QueryContext context, @NonNull final Account contract) {
        var accountId = contract.accountIdOrThrow();
        var contractNumber = accountId.accountNumOrThrow();
        var contractId = entityIdFactory.newContractId(contractNumber);
        final var bytecode = context.createStore(ContractStateStore.class).getBytecode(contractId);
        return bytecode == null ? null : bytecode.code();
    }
}
