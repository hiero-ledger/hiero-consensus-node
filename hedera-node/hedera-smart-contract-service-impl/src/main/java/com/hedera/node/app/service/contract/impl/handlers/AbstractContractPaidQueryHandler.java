// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.app.service.schedule.ReadableScheduleStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.workflows.PaidQueryHandler;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.swirlds.state.lifecycle.EntityIdFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.Function;

public abstract class AbstractContractPaidQueryHandler<T> extends PaidQueryHandler {

    protected final EntityIdFactory entityIdFactory;
    protected final Function<Query, T> operationGetter;
    protected final Function<T, ContractID> contractIdGetter;

    public AbstractContractPaidQueryHandler(
            @NonNull final EntityIdFactory entityIdFactory,
            @NonNull final Function<Query, T> operationGetter,
            @NonNull final Function<T, ContractID> contractIdGetter) {
        this.entityIdFactory = requireNonNull(entityIdFactory);
        this.operationGetter = requireNonNull(operationGetter);
        this.contractIdGetter = requireNonNull(contractIdGetter);
    }

    protected T getOperation(@NonNull final QueryContext context) {
        return operationGetter.apply(context.query());
    }

    protected ContractID getContractId(@NonNull final QueryContext context) {
        return contractIdGetter.apply(getOperation(context));
    }

    /**
     * Looking in the ReadableAccountStore for ContractID or AccountID for Account object
     * <p>
     * NOTE: ContractID and AccountID are the same thing, really, and contracts are accounts.
     *
     * @param context    Context of a single query. Contains all query specific information.
     * @param contractId the contract id
     * @return the account
     */
    protected @Nullable Account accountFrom(@NonNull final QueryContext context, @NonNull final ContractID contractId) {
        final var store = context.createStore(ReadableAccountStore.class);
        // looking by ContractID first, because in 'normal' behavior it should be more frequent
        var account = store.getContractById(contractId);
        if (account == null) {
            final var accountId = entityIdFactory.newAccountId(ConversionUtils.contractIDToNum(contractId));
            account = store.getAccountById(accountId);
        }
        return account;
    }

    protected @Nullable Token tokenFrom(@NonNull final QueryContext context, @NonNull final ContractID contractId) {
        final var tokenID = entityIdFactory.newTokenId(ConversionUtils.contractIDToNum(contractId));
        return context.createStore(ReadableTokenStore.class).get(tokenID);
    }

    protected @Nullable Schedule scheduleFrom(
            @NonNull final QueryContext context, @NonNull final ContractID contractId) {
        final var scheduleId = entityIdFactory.newScheduleId(ConversionUtils.contractIDToNum(contractId));
        return context.createStore(ReadableScheduleStore.class).get(scheduleId);
    }
}
