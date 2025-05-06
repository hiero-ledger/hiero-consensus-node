// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.validation;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EXPIRATION_REDUCTION_NOT_ALLOWED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.hapi.utils.InvalidTransactionException;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.validation.ExpiryMeta;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.AutoRenewConfig;
import com.hedera.node.config.data.HederaConfig;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Implementation of {@link ExpiryValidator}.
 *
 * <p>The implementation is incomplete, and is a placeholder for future work.
 * GitHub Issue <a href="https://github.com/hashgraph/hedera-services/issues/6701">(#6701)</a>
 */
public class ExpiryValidatorImpl implements ExpiryValidator {

    private final HandleContext context;

    public ExpiryValidatorImpl(@NonNull final HandleContext context) {
        this.context = requireNonNull(context, "context must not be null");
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public ExpiryMeta resolveCreationAttempt(
            final boolean entityCanSelfFundRenewal,
            @NonNull final ExpiryMeta creationMeta,
            @NonNull final HederaFunctionality functionality) {
        if (creationMeta.hasAutoRenewAccountId()) {
            validateAutoRenewAccount(requireNonNull(creationMeta.autoRenewAccountId()));
        }

        long effectiveExpiry = creationMeta.expiry();
        // We prioritize the expiry implied by auto-renew configuration, if it is present
        // and complete (meaning either both auto-renew period and auto-renew account are
        // present; or auto-renew period is present, and the entity can self-fund)
        if (hasCompleteAutoRenewSpec(entityCanSelfFundRenewal, creationMeta)) {
            effectiveExpiry = clampedAdd(context.consensusNow().getEpochSecond(), creationMeta.autoRenewPeriod());
        }
        // To maintain same behaviour for differential testing this condition is needed.
        // FUTURE: This condition should be removed after differential testing is done
        if (functionality == CONSENSUS_CREATE_TOPIC) {
            // In mono-service this check is done first for topic creation.
            context.attributeValidator().validateExpiry(effectiveExpiry);
            // Even if the effective expiry is valid, we still also require any explicit auto-renew period to be valid
            validateAutoRenew(creationMeta);
        } else if (functionality == TOKEN_CREATE) {
            // In mono-service, INVALID_RENEWAL_PERIOD is thrown for token creation and update
            // if the autoRenewPeriod is not in range.
            // It would be more correct to throw AUTO_RENEW_DURATION_NOT_IN_RANGE like the other services do,
            // but this would break differential testing. So for now, we replicate the mono-service behaviour.
            try {
                validateAutoRenew(creationMeta);
            } catch (HandleException e) {
                if (e.getStatus() == AUTORENEW_DURATION_NOT_IN_RANGE) {
                    throw new HandleException(INVALID_RENEWAL_PERIOD);
                } else {
                    throw e;
                }
            }
            context.attributeValidator().validateExpiry(effectiveExpiry);
        } else {
            // Even if the effective expiry is valid, we still also require any explicit auto-renew period to be valid
            validateAutoRenew(creationMeta);
            context.attributeValidator().validateExpiry(effectiveExpiry);
        }

        return new ExpiryMeta(effectiveExpiry, creationMeta.autoRenewPeriod(), creationMeta.autoRenewAccountId());
    }

    private void validateAutoRenew(final ExpiryMeta creationMeta) {
        if (creationMeta.hasAutoRenewPeriod()) {
            context.attributeValidator().validateAutoRenewPeriod(creationMeta.autoRenewPeriod());
        }
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public ExpiryMeta resolveUpdateAttempt(
            @NonNull final ExpiryMeta currentMeta, @NonNull final ExpiryMeta updateMeta) {
        if (updateMeta.hasAutoRenewAccountId()) {
            validateAutoRenewAccount(updateMeta.autoRenewAccountId());
        }

        var resolvedExpiry = currentMeta.expiry();
        if (updateMeta.hasExplicitExpiry()) {
            context.attributeValidator().validateExpiry(updateMeta.expiry());
            validateFalse(updateMeta.expiry() < currentMeta.expiry(), EXPIRATION_REDUCTION_NOT_ALLOWED);
            resolvedExpiry = updateMeta.expiry();
        }

        var resolvedAutoRenewPeriod = currentMeta.autoRenewPeriod();
        if (updateMeta.hasAutoRenewPeriod()) {
            context.attributeValidator().validateAutoRenewPeriod(updateMeta.autoRenewPeriod());
            resolvedAutoRenewPeriod = updateMeta.autoRenewPeriod();
        }

        var resolvedAutoRenewAccountId = currentMeta.autoRenewAccountId();
        if (updateMeta.hasAutoRenewAccountId()) {
            // If just now adding an auto-renew account, confirm the resolved auto-renew period is valid
            if (!currentMeta.hasAutoRenewAccountId()) {
                context.attributeValidator().validateAutoRenewPeriod(resolvedAutoRenewPeriod);
            }
            resolvedAutoRenewAccountId = updateMeta.autoRenewAccountId();
        }
        return new ExpiryMeta(resolvedExpiry, resolvedAutoRenewPeriod, resolvedAutoRenewAccountId);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public ResponseCodeEnum expirationStatus(
            @NonNull final EntityType entityType,
            final boolean isMarkedExpired,
            final long balanceAvailableForSelfRenewal) {
        final var isSmartContract = entityType.equals(EntityType.CONTRACT_BYTECODE);
        final var autoRenewConfig = context.configuration().getConfigData(AutoRenewConfig.class);
        if (!autoRenewConfig.isAutoRenewEnabled()
                || balanceAvailableForSelfRenewal > 0
                || !isMarkedExpired
                || isExpiryDisabled(
                        isSmartContract, autoRenewConfig.expireAccounts(), autoRenewConfig.expireContracts())) {
            return OK;
        }

        return isSmartContract ? CONTRACT_EXPIRED_AND_PENDING_REMOVAL : ACCOUNT_EXPIRED_AND_PENDING_REMOVAL;
    }

    /**
     * Helper to check if an entity with the given metadata has a completely specified
     * auto-renew configuration. This is true if either the {@link ExpiryMeta} includes
     * both an auto-renew period and an auto-renew account; or if the {@link ExpiryMeta}
     * includes only an auto-renew period, and the entity can self-fund its auto-renewal.
     *
     * @param entityCanSelfFundRenewal whether the entity can self-fund its auto-renewal
     * @param creationMetadata the entity's proposed {@link ExpiryMeta}
     * @return whether the entity has a complete auto-renew configuration
     */
    private boolean hasCompleteAutoRenewSpec(
            final boolean entityCanSelfFundRenewal, final ExpiryMeta creationMetadata) {
        return creationMetadata.hasFullAutoRenewSpec()
                || (!creationMetadata.hasExplicitExpiry() && entityCanSelfFundRenewal);
    }

    /**
     * Helper to validate that the given account number is a valid auto-renew account.
     *
     * @param accountID the account id to validate
     * @throws HandleException if the account number is invalid
     */
    private void validateAutoRenewAccount(final AccountID accountID) {
        final var hederaConfig = context.configuration().getConfigData(HederaConfig.class);

        if (isSentinelAccount(accountID)) {
            // 0.0.0 is a sentinel number that says to remove the current auto-renew account
            return;
        }
        validateTrue(
                accountID.shardNum() == hederaConfig.shard() && accountID.realmNum() == hederaConfig.realm(),
                INVALID_AUTORENEW_ACCOUNT);
        final var accountStore = context.storeFactory().readableStore(ReadableAccountStore.class);
        try {
            final var account = accountStore.getAccountById(accountID);
            if (account == null || account.deleted()) {
                throw new HandleException(INVALID_AUTORENEW_ACCOUNT);
            }
        } catch (final InvalidTransactionException e) {
            throw new HandleException(CommonPbjConverters.toPbj(e.getResponseCode()));
        }
    }

    private boolean isExpiryDisabled(boolean smartContract, boolean expireAccounts, boolean expireContracts) {
        return (smartContract && !expireContracts) || (!smartContract && !expireAccounts);
    }

    private static long clampedAdd(final long a, final long b) {
        try {
            return Math.addExact(a, b);
        } catch (final ArithmeticException ae) {
            return a > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private boolean isSentinelAccount(@NonNull final AccountID accountID) {
        return accountID.hasAccountNum()
                && accountID.shardNum() == 0L
                && accountID.realmNum() == 0L
                && accountID.accountNum() == 0L;
    }
}
