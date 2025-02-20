// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.embedded;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.token.Account;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

/**
 * An operation that allows the test author to directly mutate an account in an embedded state.
 */
public class MutateAccountOp extends UtilOp {
    private final String account;
    private final Consumer<Account.Builder> mutation;

    /**
     * Constructs the operation.
     * @param account the identifier of the account to mutate
     * @param mutation the mutation to apply to the account
     */
    public MutateAccountOp(@NonNull final String account, @NonNull final Consumer<Account.Builder> mutation) {
        this.account = requireNonNull(account);
        this.mutation = requireNonNull(mutation);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var accounts = spec.embeddedAccountsOrThrow();
        final var targetId = toPbj(TxnUtils.asId(account, spec));
        final var account = requireNonNull(accounts.get(targetId));
        final var builder = account.copyBuilder();
        mutation.accept(builder);
        accounts.put(targetId, builder.build());
        spec.commitEmbeddedState();
        return false;
    }
}
