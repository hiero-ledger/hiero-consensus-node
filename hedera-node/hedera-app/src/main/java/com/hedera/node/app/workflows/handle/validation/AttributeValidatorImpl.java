// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.validation;

import static com.hedera.hapi.node.base.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BAD_ENCODING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isValid;
import static com.hedera.node.app.spi.validation.AttributeValidator.isWithinNestedKeyLimit;
import static com.hedera.node.app.spi.validation.ExpiryMeta.NA;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.charset.StandardCharsets;

/**
 * Implementation of {@link AttributeValidator}.
 *
 * <p>The implementation is incomplete, and is a placeholder for future work.
 * GitHub Issue <a href="https://github.com/hashgraph/hedera-services/issues/6701">(#6701)</a>
 */
public class AttributeValidatorImpl implements AttributeValidator {

    private final HandleContext context;

    public AttributeValidatorImpl(@NonNull final HandleContext context) {
        this.context = requireNonNull(context, "context must not be null");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateKey(@NonNull final Key key) {
        // Enforce the nesting-depth cap through the shared SPI helper so the handle path and the stateless
        // pure-check / pre-handle callers stay in lockstep on the same MAX_NESTED_KEY_LEVELS limit.
        if (!isWithinNestedKeyLimit(key)) {
            throw new HandleException(BAD_ENCODING);
        }
        // If the key is within the depth limit, validate that it is structurally valid.
        if (!isValid(key)) {
            throw new HandleException(BAD_ENCODING);
        }
    }

    @Override
    public void validateKey(@NonNull final Key key, @NonNull final ResponseCodeEnum responseCodeEnum) {
        try {
            validateKey(key);
        } catch (HandleException e) {
            throw new HandleException(responseCodeEnum);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateMemo(@Nullable final String memo) {
        if (memo == null) {
            return;
        }
        final var raw = memo.getBytes(StandardCharsets.UTF_8);
        final var maxMemoUtf8Bytes =
                context.configuration().getConfigData(HederaConfig.class).transactionMaxMemoUtf8Bytes();
        if (raw.length > maxMemoUtf8Bytes) {
            throw new HandleException(MEMO_TOO_LONG);
        } else if (containsZeroByte(raw)) {
            throw new HandleException(INVALID_ZERO_BYTE_IN_STRING);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateExpiry(long expiry) {
        final var maxEntityLifetime =
                context.configuration().getConfigData(EntitiesConfig.class).maxLifetime();
        final var now = context.consensusNow().getEpochSecond();
        final var expiryGivenMaxLifetime = now + maxEntityLifetime;
        final var impliedExpiry = expiry == NA ? expiryGivenMaxLifetime : expiry;
        validateTrue(impliedExpiry > now && impliedExpiry <= expiryGivenMaxLifetime, INVALID_EXPIRATION_TIME);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateAutoRenewPeriod(long autoRenewPeriod) {
        final var ledgerConfig = context.configuration().getConfigData(LedgerConfig.class);
        validateTrue(autoRenewPeriod > 0, INVALID_RENEWAL_PERIOD);
        validateTrue(
                autoRenewPeriod >= ledgerConfig.autoRenewPeriodMinDuration()
                        && autoRenewPeriod <= ledgerConfig.autoRenewPeriodMaxDuration(),
                AUTORENEW_DURATION_NOT_IN_RANGE);
    }

    private static boolean containsZeroByte(@NonNull final byte[] bytes) {
        for (final byte b : bytes) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }
}
