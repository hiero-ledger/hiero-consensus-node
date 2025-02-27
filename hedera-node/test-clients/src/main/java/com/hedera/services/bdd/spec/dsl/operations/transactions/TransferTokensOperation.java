// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.operations.transactions;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * An operation that does the simplest possible adjustment of fungible token balances; i.e. debits a sender and
 * credits a receiver.
 */
public class TransferTokensOperation extends AbstractSpecTransaction<TransferTokensOperation, HapiCryptoTransfer>
        implements SpecOperation {
    private final TransferType transferType;
    private final String senderName;
    private final String receiverName;
    private final String tokenName;
    private long units;
    private long[] serialNumber;

    public TransferTokensOperation(
            @NonNull final SpecAccount sender,
            @NonNull final SpecAccount receiver,
            @NonNull final SpecFungibleToken token,
            final long units) {
        super(List.of(sender, receiver, token));
        this.senderName = requireNonNull(sender.name());
        this.receiverName = requireNonNull(receiver.name());
        this.tokenName = requireNonNull(token.name());
        this.units = units;
        this.transferType = TransferType.FUNGIBLE_TOKEN;
    }

    public TransferTokensOperation(
            @NonNull final SpecAccount sender,
            @NonNull final SpecContract receiver,
            @NonNull final SpecFungibleToken token,
            final long units) {
        super(List.of(sender, receiver, token));
        this.senderName = requireNonNull(sender.name());
        this.receiverName = requireNonNull(receiver.name());
        this.tokenName = requireNonNull(token.name());
        this.units = units;
        this.transferType = TransferType.FUNGIBLE_TOKEN;
    }

    public TransferTokensOperation(
            @NonNull final SpecAccount sender,
            @NonNull final SpecContract receiver,
            @NonNull final SpecNonFungibleToken token,
            final long... serialNumber) {
        super(List.of(sender, receiver, token));
        this.senderName = requireNonNull(sender.name());
        this.receiverName = requireNonNull(receiver.name());
        this.tokenName = requireNonNull(token.name());
        this.serialNumber = serialNumber;
        this.transferType = TransferType.NFT;
    }

    public TransferTokensOperation(
            @NonNull final SpecAccount sender,
            @NonNull final SpecAccount receiver,
            @NonNull final SpecNonFungibleToken token,
            final long... serialNumber) {
        super(List.of(sender, receiver, token));
        this.senderName = requireNonNull(sender.name());
        this.receiverName = requireNonNull(receiver.name());
        this.tokenName = requireNonNull(token.name());
        this.serialNumber = serialNumber;
        this.transferType = TransferType.NFT;
    }

    @NonNull
    @Override
    protected SpecOperation computeDelegate(@NonNull final HapiSpec spec) {
        return switch (transferType) {
            case FUNGIBLE_TOKEN -> cryptoTransfer(moving(units, tokenName).between(senderName, receiverName));
            case NFT -> cryptoTransfer(
                    TokenMovement.movingUnique(tokenName, serialNumber).between(senderName, receiverName));
        };
    }

    @Override
    protected TransferTokensOperation self() {
        return this;
    }

    public enum TransferType {
        NFT,
        FUNGIBLE_TOKEN
    }
}
