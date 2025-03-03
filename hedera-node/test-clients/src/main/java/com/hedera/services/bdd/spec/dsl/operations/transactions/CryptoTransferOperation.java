// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.operations.transactions;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenAssociate;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Associates an account with one or more tokens.
 */
public class CryptoTransferOperation extends AbstractSpecTransaction<CryptoTransferOperation, HapiTokenAssociate>
        implements SpecOperation {

    private final long amount;

    @NonNull
    private final String senderName;

    @NonNull
    private final String receiverName;

    public CryptoTransferOperation(
            final long amount, @NonNull final SpecAccount sender, @NonNull final SpecAccount receiver) {
        super(List.of(sender, receiver));

        this.senderName = requireNonNull(sender).name();
        this.receiverName = requireNonNull(receiver).name();
        this.amount = amount;
    }

    public CryptoTransferOperation(
            final long amount, @NonNull final SpecAccount sender, @NonNull final SpecContract receiver) {
        super(List.of(sender, receiver));

        this.senderName = requireNonNull(sender).name();
        this.receiverName = requireNonNull(receiver).name();
        this.amount = amount;
    }

    @Override
    protected CryptoTransferOperation self() {
        return this;
    }

    @NonNull
    @Override
    protected SpecOperation computeDelegate(@NonNull final HapiSpec spec) {
        return cryptoTransfer(tinyBarsFromTo(senderName, receiverName, amount));
    }
}
