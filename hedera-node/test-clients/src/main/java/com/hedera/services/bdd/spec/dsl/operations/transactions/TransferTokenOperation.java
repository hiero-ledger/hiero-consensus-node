// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.operations.transactions;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecToken;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenAssociate;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;

/**
 * Associates an account with one or more tokens.
 */
public class TransferTokenOperation extends AbstractSpecTransaction<TransferTokenOperation, HapiTokenAssociate>
        implements SpecOperation {
    @NonNull
    private final SpecToken token;

    private final long amount;

    @NonNull
    private final String senderName;

    @NonNull
    private final String receiverName;

    // non-standard ArrayList initializer
    @SuppressWarnings({"java:S3599", "java:S1171"})
    public TransferTokenOperation(
            final long amount,
            @NonNull final SpecToken token,
            @NonNull final SpecAccount sender,
            @NonNull final SpecAccount receiver) {
        super(new ArrayList<>() {
            {
                add(token);
                add(sender);
                add(receiver);
            }
        });
        this.senderName = requireNonNull(sender).name();
        this.receiverName = requireNonNull(receiver).name();
        this.token = requireNonNull(token);
        this.amount = amount;
    }

    public TransferTokenOperation(
            final long amount,
            @NonNull final SpecToken token,
            @NonNull final SpecAccount sender,
            @NonNull final SpecContract receiver) {
        super(new ArrayList<>() {
            {
                add(token);
                add(sender);
                add(receiver);
            }
        });
        this.senderName = requireNonNull(sender).name();
        this.receiverName = requireNonNull(receiver).name();
        this.token = requireNonNull(token);
        this.amount = amount;
    }

    @Override
    protected TransferTokenOperation self() {
        return this;
    }

    @NonNull
    @Override
    protected SpecOperation computeDelegate(@NonNull final HapiSpec spec) {
        return cryptoTransfer(moving(amount, token.name()).between(senderName, receiverName));
    }
}
