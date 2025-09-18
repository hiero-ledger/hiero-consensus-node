// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.embedded;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.swirlds.state.spi.ReadableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

public class ViewContractOp extends UtilOp {
    private final String contract;
    private final Consumer<Account> observer;

    public ViewContractOp(@NonNull final String contract, @NonNull final Consumer<Account> observer) {
        this.contract = requireNonNull(contract);
        this.observer = requireNonNull(observer);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var state = spec.embeddedStateOrThrow();
        final var readableStates = state.getReadableStates(TokenService.NAME);
        final ReadableKVState<AccountID, Account> accounts = readableStates.get(ACCOUNTS_STATE_ID);
        final var contractId = toPbj(TxnUtils.asContractId(contract, spec));
        final var account = accounts.get(AccountID.newBuilder()
                .shardNum(contractId.shardNum())
                .realmNum(contractId.realmNum())
                .accountNum(contractId.contractNum())
                .build());
        observer.accept(account);
        return false;
    }
}
