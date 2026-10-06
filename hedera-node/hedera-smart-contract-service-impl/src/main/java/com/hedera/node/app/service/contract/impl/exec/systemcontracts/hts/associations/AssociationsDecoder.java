// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.associations;

import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_REFERENCE_LIST_SIZE_LIMIT_EXCEEDED;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asTokenIds;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.token.TokenAssociateTransactionBody;
import com.hedera.hapi.node.token.TokenDissociateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.config.data.ContractsConfig;
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
        return bodyOf(association(attempt, call.get(0), call.get(1)));
    }

    /**
     * Decodes a call to {@link AssociationsTranslator#ASSOCIATE_MANY} into a synthetic {@link TransactionBody}.
     *
     * @param attempt the attempt to translate
     * @return the synthetic transaction body
     */
    public TransactionBody decodeAssociateMany(@NonNull final HtsCallAttempt attempt) {
        final var call = AssociationsTranslator.ASSOCIATE_MANY.decodeCall(attempt.inputBytes());
        return bodyOf(associations(attempt, call.get(0), call.get(1)));
    }

    /**
     * Decodes a call to {@link AssociationsTranslator#DISSOCIATE_ONE} into a synthetic {@link TransactionBody}.
     *
     * @param attempt the attempt to translate
     * @return the synthetic transaction body
     */
    public TransactionBody decodeDissociateOne(@NonNull final HtsCallAttempt attempt) {
        final var call = AssociationsTranslator.DISSOCIATE_ONE.decodeCall(attempt.inputBytes());
        return bodyOf(dissociation(attempt, call.get(0), call.get(1)));
    }

    /**
     * Decodes a call to {@link AssociationsTranslator#DISSOCIATE_MANY} into a synthetic {@link TransactionBody}.
     *
     * @param attempt the attempt to translate
     * @return the synthetic transaction body
     */
    public TransactionBody decodeDissociateMany(@NonNull final HtsCallAttempt attempt) {
        final var call = AssociationsTranslator.DISSOCIATE_MANY.decodeCall(attempt.inputBytes());
        return bodyOf(dissociations(attempt, call.get(0), call.get(1)));
    }

    private TransactionBody bodyOf(@NonNull final TokenAssociateTransactionBody association) {
        return TransactionBody.newBuilder().tokenAssociate(association).build();
    }

    private TransactionBody bodyOf(@NonNull final TokenDissociateTransactionBody dissociation) {
        return TransactionBody.newBuilder().tokenDissociate(dissociation).build();
    }

    private TokenAssociateTransactionBody association(
            @NonNull final HtsCallAttempt attempt,
            @NonNull final Address accountAddress,
            @NonNull final Address tokenAddress) {
        return internalAssociations(attempt, accountAddress, tokenAddress);
    }

    private TokenAssociateTransactionBody associations(
            @NonNull final HtsCallAttempt attempt,
            @NonNull final Address accountAddress,
            @NonNull final Address[] tokenAddresses) {
        return internalAssociations(attempt, accountAddress, tokenAddresses);
    }

    private TokenDissociateTransactionBody dissociation(
            @NonNull final HtsCallAttempt attempt,
            @NonNull final Address accountAddress,
            @NonNull final Address tokenAddress) {
        return internalDissociations(attempt, accountAddress, tokenAddress);
    }

    private TokenDissociateTransactionBody dissociations(
            @NonNull final HtsCallAttempt attempt,
            @NonNull final Address accountAddress,
            @NonNull final Address[] tokenAddresses) {
        return internalDissociations(attempt, accountAddress, tokenAddresses);
    }

    /**
     * Validate the max length of token addresses in associate call based on the canonical gas for a single association
     * @param attempt the HTS call attempt
     * @param tokenAddresses associate tokens addresses
     */
    private void validateTokensMaxLength(
            @NonNull final HtsCallAttempt attempt, @NonNull final Address... tokenAddresses) {
        final var canonicalGas = attempt.systemContractGasCalculator().canonicalGasRequirement(DispatchType.ASSOCIATE);
        final var config = attempt.configuration().getConfigData(ContractsConfig.class);
        validateTrue(
                tokenAddresses.length * canonicalGas <= config.maxGasPerTransaction(),
                TOKEN_REFERENCE_LIST_SIZE_LIMIT_EXCEEDED);
    }

    private TokenAssociateTransactionBody internalAssociations(
            @NonNull final HtsCallAttempt attempt,
            @NonNull final Address accountAddress,
            @NonNull final Address... tokenAddresses) {
        validateTokensMaxLength(attempt, tokenAddresses);
        return TokenAssociateTransactionBody.newBuilder()
                .account(attempt.addressIdConverter().convert(accountAddress))
                .tokens(asTokenIds(attempt.nativeOperations().entityIdFactory(), tokenAddresses))
                .build();
    }

    private TokenDissociateTransactionBody internalDissociations(
            @NonNull final HtsCallAttempt attempt,
            @NonNull final Address accountAddress,
            @NonNull final Address... tokenAddresses) {
        validateTokensMaxLength(attempt, tokenAddresses);
        return TokenDissociateTransactionBody.newBuilder()
                .account(attempt.addressIdConverter().convert(accountAddress))
                .tokens(asTokenIds(attempt.nativeOperations().entityIdFactory(), tokenAddresses))
                .build();
    }
}
