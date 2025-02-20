// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.associations;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asTokenIds;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.token.TokenAssociateTransactionBody;
import com.hedera.hapi.node.token.TokenDissociateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provides help in decoding an {@link HtsCallAttempt} representing an associate or dissociate call into
 * a synthetic {@link TransactionBody}.
 */
@Singleton
public class AssociationsDecoder {
    /**
     * Default constructor for injection.
     */
    @Inject
    public AssociationsDecoder() {
        // Dagger2
    }

    /**
     * Decodes the given {@code attempt} into a {@link TransactionBody} for an HRC association call.
     *
     * @param attempt the attempt to decode
     * @return a {@link TransactionBody}
     */
    public TransactionBody decodeHrcAssociate(@NonNull final HtsCallAttempt attempt) {
        return TransactionBody.newBuilder()
                .tokenAssociate(TokenAssociateTransactionBody.newBuilder()
                        .account(attempt.senderId())
                        .tokens(requireNonNull(attempt.redirectTokenId())))
                .build();
    }

    /**
     * Decodes the given {@code attempt} into a {@link TransactionBody} for an HRC dissociation call.
     *
     * @param attempt the attempt to decode
     * @return a {@link TransactionBody}
     */
    public TransactionBody decodeHrcDissociate(@NonNull final HtsCallAttempt attempt) {
        return TransactionBody.newBuilder()
                .tokenDissociate(TokenDissociateTransactionBody.newBuilder()
                        .account(attempt.senderId())
                        .tokens(requireNonNull(attempt.redirectTokenId())))
                .build();
    }

    /**
     * Decodes a call to {@link AssociationsTranslator#ASSOCIATE_ONE} into a synthetic {@link TransactionBody}.
     *
     * @param attempt the attempt to translate
     * @return the synthetic transaction body
     */
    public TransactionBody decodeAssociateOne(@NonNull final HtsCallAttempt attempt) {
        final var call = AssociationsTranslator.ASSOCIATE_ONE.decodeCall(attempt.inputBytes());
        return bodyOf(association(attempt.addressIdConverter(), call.get(0), call.get(1)));
    }

    /**
     * Decodes a call to {@link AssociationsTranslator#ASSOCIATE_MANY} into a synthetic {@link TransactionBody}.
     *
     * @param attempt the attempt to translate
     * @return the synthetic transaction body
     */
    public TransactionBody decodeAssociateMany(@NonNull final HtsCallAttempt attempt) {
        final var call = AssociationsTranslator.ASSOCIATE_MANY.decodeCall(attempt.inputBytes());
        return bodyOf(associations(attempt.addressIdConverter(), call.get(0), call.get(1)));
    }

    /**
     * Decodes a call to {@link AssociationsTranslator#DISSOCIATE_ONE} into a synthetic {@link TransactionBody}.
     *
     * @param attempt the attempt to translate
     * @return the synthetic transaction body
     */
    public TransactionBody decodeDissociateOne(@NonNull final HtsCallAttempt attempt) {
        final var call = AssociationsTranslator.DISSOCIATE_ONE.decodeCall(attempt.inputBytes());
        return bodyOf(dissociation(attempt.addressIdConverter(), call.get(0), call.get(1)));
    }

    /**
     * Decodes a call to {@link AssociationsTranslator#DISSOCIATE_MANY} into a synthetic {@link TransactionBody}.
     *
     * @param attempt the attempt to translate
     * @return the synthetic transaction body
     */
    public TransactionBody decodeDissociateMany(@NonNull final HtsCallAttempt attempt) {
        final var call = AssociationsTranslator.DISSOCIATE_MANY.decodeCall(attempt.inputBytes());
        return bodyOf(dissociations(attempt.addressIdConverter(), call.get(0), call.get(1)));
    }

    private TransactionBody bodyOf(@NonNull final TokenAssociateTransactionBody association) {
        return TransactionBody.newBuilder().tokenAssociate(association).build();
    }

    private TransactionBody bodyOf(@NonNull final TokenDissociateTransactionBody dissociation) {
        return TransactionBody.newBuilder().tokenDissociate(dissociation).build();
    }

    private TokenAssociateTransactionBody association(
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final Address accountAddress,
            @NonNull final Address tokenAddress) {
        return internalAssociations(addressIdConverter, accountAddress, tokenAddress);
    }

    private TokenAssociateTransactionBody associations(
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final Address accountAddress,
            @NonNull final Address[] tokenAddresses) {
        return internalAssociations(addressIdConverter, accountAddress, tokenAddresses);
    }

    private TokenDissociateTransactionBody dissociation(
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final Address accountAddress,
            @NonNull final Address tokenAddress) {
        return internalDissociations(addressIdConverter, accountAddress, tokenAddress);
    }

    private TokenDissociateTransactionBody dissociations(
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final Address accountAddress,
            @NonNull final Address[] tokenAddresses) {
        return internalDissociations(addressIdConverter, accountAddress, tokenAddresses);
    }

    private TokenAssociateTransactionBody internalAssociations(
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final Address accountAddress,
            @NonNull final Address... tokenAddresses) {
        return TokenAssociateTransactionBody.newBuilder()
                .account(addressIdConverter.convert(accountAddress))
                .tokens(asTokenIds(tokenAddresses))
                .build();
    }

    private TokenDissociateTransactionBody internalDissociations(
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final Address accountAddress,
            @NonNull final Address... tokenAddresses) {
        return TokenDissociateTransactionBody.newBuilder()
                .account(addressIdConverter.convert(accountAddress))
                .tokens(asTokenIds(tokenAddresses))
                .build();
    }
}
