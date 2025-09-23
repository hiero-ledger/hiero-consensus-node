// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.embedded;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.swirlds.state.spi.ReadableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

public class ViewAccountOp extends UtilOp {

    private final String account;
    private final Consumer<Account> observer;

    public ViewAccountOp(@NonNull final String account, @NonNull final Consumer<Account> observer) {
        this.account = requireNonNull(account);
        this.observer = requireNonNull(observer);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var state = spec.embeddedStateOrThrow();
        final var readableStates = state.getReadableStates(TokenService.NAME);
        final ReadableKVState<AccountID, Account> accounts = readableStates.get(V0490TokenSchema.ACCOUNTS_STATE_ID);
        final var accountId = toPbj(TxnUtils.asId(account, spec));
        final var account = accounts.get(accountId);
        observer.accept(account);
        return false;
    }
}
