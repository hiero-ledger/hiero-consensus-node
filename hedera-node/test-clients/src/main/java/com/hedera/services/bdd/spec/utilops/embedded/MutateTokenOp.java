// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.embedded;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.protoToPbj;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asTokenId;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

/**
 * An operation that allows the test author to directly mutate a token in an embedded state.
 */
public class MutateTokenOp extends UtilOp {
    private final String token;
    private final Consumer<Token.Builder> mutation;

    /**
     * Constructs the operation.
     * @param token the identifier of the token to mutate
     * @param mutation the mutation to apply to the token
     */
    public MutateTokenOp(@NonNull final String token, @NonNull final Consumer<Token.Builder> mutation) {
        this.token = requireNonNull(token);
        this.mutation = requireNonNull(mutation);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var tokens = spec.embeddedTokensOrThrow();
        final var targetId = protoToPbj(asTokenId(token, spec), TokenID.class);
        final var token = requireNonNull(tokens.get(targetId));
        final var builder = token.copyBuilder();
        mutation.accept(builder);
        tokens.put(targetId, builder.build());
        spec.commitEmbeddedState();
        return false;
    }
}
