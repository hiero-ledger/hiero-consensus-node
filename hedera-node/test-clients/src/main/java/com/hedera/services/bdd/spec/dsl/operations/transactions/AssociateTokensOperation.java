// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.operations.transactions;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecToken;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenAssociate;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Associates an account with one or more tokens.
 */
public class AssociateTokensOperation extends AbstractSpecTransaction<AssociateTokensOperation, HapiTokenAssociate>
        implements SpecOperation {
    @Nullable
    private final SpecAccount account;

    @Nullable
    private final SpecContract contract;

    private final List<SpecToken> tokens;

    // non-standard ArrayList initializer
    @SuppressWarnings({"java:S3599", "java:S1171"})
    public AssociateTokensOperation(@NonNull final SpecAccount account, @NonNull final List<SpecToken> tokens) {
        super(new ArrayList<>() {
            {
                add(account);
                addAll(tokens);
            }
        });
        this.contract = null;
        this.account = requireNonNull(account);
        this.tokens = requireNonNull(tokens);
    }

    public AssociateTokensOperation(@NonNull final SpecContract contract, @NonNull final List<SpecToken> tokens) {
        super(new ArrayList<>() {
            {
                add(contract);
                addAll(tokens);
            }
        });
        this.contract = requireNonNull(contract);
        this.account = null;
        this.tokens = requireNonNull(tokens);
    }

    @Override
    protected AssociateTokensOperation self() {
        return this;
    }

    @NonNull
    @Override
    protected SpecOperation computeDelegate(@NonNull final HapiSpec spec) {
        final var tokenNames = tokens.stream().map(SpecToken::name).toArray(String[]::new);
        return account != null
                ? tokenAssociate(account.name(), tokenNames)
                : tokenAssociate(requireNonNull(contract).name(), tokenNames).signedByPayerAnd(contract.name());
    }
}
