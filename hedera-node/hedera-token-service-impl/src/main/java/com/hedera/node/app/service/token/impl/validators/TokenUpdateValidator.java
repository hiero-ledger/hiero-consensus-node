// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsable;
import static com.hedera.node.app.spi.validation.ExpiryMeta.NA;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.validation.ExpiryMeta;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.config.data.TokensConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;

/**
 * Validator for token update transactions.
 */
public class TokenUpdateValidator {
    private final TokenAttributesValidator validator;

    /**
     * Create a new {@link TokenUpdateValidator} instance.
     * @param validator The {@link TokenAttributesValidator} to use.
     */
    @Inject
    public TokenUpdateValidator(@NonNull final TokenAttributesValidator validator) {
        this.validator = validator;
    }

    /**
     * Validate the semantics of a token update transaction.
     * @param token The token to update.
     * @param resolvedExpiryMeta The resolved expiry metadata.
     */
    public record ValidationResult(@NonNull Token token, @NonNull ExpiryMeta resolvedExpiryMeta) {}

    /**
     * Validate the semantics of a token update transaction.
     * @param context The context to use.
     * @param op The token update transaction body.
     * @return The result of the validation
     */
    @NonNull
    public ValidationResult validateSemantics(
            @NonNull final HandleContext context, @NonNull final TokenUpdateTransactionBody op) {
        final var storeFactory = context.storeFactory();
        final var readableAccountStore = storeFactory.readableStore(ReadableAccountStore.class);
        final var tokenStore = storeFactory.readableStore(ReadableTokenStore.class);
        final var token = getIfUsable(op.tokenOrThrow(), tokenStore);
        final var tokensConfig = context.configuration().getConfigData(TokensConfig.class);
        if (op.hasMemo()) {
            context.attributeValidator().validateMemo(op.memo());
        }
        if (op.hasMetadata()) {
            validator.validateTokenMetadata(op.metadataOrThrow(), tokensConfig);
        }
        if (!op.symbol().isEmpty()) {
            validator.validateTokenSymbol(op.symbol(), tokensConfig);
        }
        if (!op.name().isEmpty()) {
            validator.validateTokenName(op.name(), tokensConfig);
        }
        if (!(op.hasExpiry() || op.hasAutoRenewPeriod() || op.hasAutoRenewAccount())) {
            return new ValidationResult(
                    token,
                    new ExpiryMeta(token.expirationSecond(), token.autoRenewSeconds(), token.autoRenewAccountId()));
        }
        final var resolvedExpiryMeta = resolveExpiry(token, op, context.expiryValidator());
        if (op.hasAutoRenewAccount()) {
            validateNewAndExistingAutoRenewAccount(
                    resolvedExpiryMeta.autoRenewAccountId(),
                    token.autoRenewAccountId(),
                    readableAccountStore,
                    context.expiryValidator());
        }
        return new ValidationResult(token, resolvedExpiryMeta);
    }

    private void validateNewAndExistingAutoRenewAccount(
            final AccountID resolvedAutoRenewId,
            final AccountID existingAutoRenewId,
            final ReadableAccountStore readableAccountStore,
            final ExpiryValidator expiryValidator) {
        // Get resolved auto-renewal account
        getIfUsable(resolvedAutoRenewId, readableAccountStore, expiryValidator, INVALID_AUTORENEW_ACCOUNT);
        // If token has an existing auto-renewal account, validate its expiration
        // FUTURE : Not sure why we should validate existing auto-renew account. Retained as in mono-service
        if (!resolvedAutoRenewId.equals(AccountID.DEFAULT) && existingAutoRenewId != null) {
            getIfUsable(existingAutoRenewId, readableAccountStore, expiryValidator, INVALID_AUTORENEW_ACCOUNT);
        }
    }

    private ExpiryMeta resolveExpiry(
            @NonNull final Token token,
            @NonNull final TokenUpdateTransactionBody op,
            @NonNull final ExpiryValidator expiryValidator) {
        final var givenExpiryMeta =
                new ExpiryMeta(token.expirationSecond(), token.autoRenewSeconds(), token.autoRenewAccountId());
        final var updateExpiryMeta = new ExpiryMeta(
                op.hasExpiry() ? op.expiryOrThrow().seconds() : NA,
                op.hasAutoRenewPeriod() ? op.autoRenewPeriodOrThrow().seconds() : NA,
                op.autoRenewAccount());
        return expiryValidator.resolveUpdateAttempt(givenExpiryMeta, updateExpiryMeta);
    }
}
