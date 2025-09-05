// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.BAD_HOOK_REQUEST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_ID_REPEATED_IN_CREATION_DETAILS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.KEY_REQUIRED;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isValid;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.UNLIMITED_AUTOMATIC_ASSOCIATIONS;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.hooks.HookCreationDetails;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.node.app.hapi.utils.keys.KeyUtils;
import com.hedera.node.app.service.token.impl.handlers.CryptoCreateHandler.HookSummary;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Provides validation for token fields like token type,  token supply type, token symbol etc.,
 * It is used in pureChecks for token creation.
 */
@Singleton
public class CryptoCreateValidator {
    /**
     * Default constructor for injection.
     */
    @Inject
    public CryptoCreateValidator() {
        // Exists for injection
    }

    /**
     * Validates the key.
     *
     * <p>If the key is dispatched internally, then it is allowed to use {@link KeyUtils#IMMUTABILITY_SENTINEL_KEY} as
     * its key. Otherwise, this key is disallowed. Otherwise, we throw {@link HandleException} with
     * {@link ResponseCodeEnum#BAD_ENCODING} if the key is empty or exceeds the maximum key depth. All other invalid
     * scenarios throw {@link HandleException} with {@link ResponseCodeEnum#INVALID_ADMIN_KEY}.
     *
     * @param key The key to validate
     * @param attributeValidator AttributeValidator
     * @param isInternalDispatch Whether this is a hollow account creation (permits empty key list)
     * @throws HandleException If the inputs are not invalid
     */
    public void validateKey(
            @NonNull final Key key,
            @NonNull final AttributeValidator attributeValidator,
            final boolean isInternalDispatch) {

        final var isSentinel = IMMUTABILITY_SENTINEL_KEY.equals(key);
        if (isSentinel && !isInternalDispatch) {
            // IMMUTABILITY_SENTINEL_KEY is only allowed for internal dispatches.
            throw new HandleException(KEY_REQUIRED);
        } else if (!isSentinel) {
            // If it is not the sentinel key, we need to validate the key, no matter whether internal or HAPI.
            //
            // This solution is not nice, but for now, it is the best we can do and maintain compatibility and some
            // semblance of maintainability. There is a lot of duplicated work, because `isEmpty` is called by
            // `isValid(Key)`, and `isValid(Key)` is called by `validateKey(Key)`! So `isEmpty` gets called at least
            // three times (once in pureChecks), and `isValid` at least twice. But this is the only way to make sure the
            // right exceptions are thrown, without breaking key validation steps down in a granular way which would be
            // hard to maintain.
            if (!isValid(key)) {
                throw new HandleException(INVALID_ADMIN_KEY);
            } else {
                attributeValidator.validateKey(key);
            }
        }
    }

    /**
     * Check if the number of auto associations is too many
     * or in the case of unlimited auto associations, check if the number is less than -1 or 0 if disabled.
     *
     * @param numAssociations number to check
     * @param ledgerConfig LedgerConfig
     * @param entitiesConfig EntitiesConfig
     * @param tokensConfig TokensConfig
     * @return true the given number is greater than the max number of auto associations
     * or negative and unlimited auto associations are disabled
     * or less than -1 if unlimited auto associations are enabled
     */
    public boolean tooManyAutoAssociations(
            final int numAssociations,
            @NonNull final LedgerConfig ledgerConfig,
            @NonNull final EntitiesConfig entitiesConfig,
            @NonNull final TokensConfig tokensConfig) {
        return (entitiesConfig.limitTokenAssociations() && numAssociations > tokensConfig.maxPerAccount())
                || numAssociations > ledgerConfig.maxAutoAssociations()
                || (numAssociations < UNLIMITED_AUTOMATIC_ASSOCIATIONS
                && entitiesConfig.unlimitedAutoAssociationsEnabled())
                || (numAssociations < 0 && !entitiesConfig.unlimitedAutoAssociationsEnabled());
    }

    public void validateHookPureChecks(final CryptoCreateTransactionBody op) throws PreCheckException {
        final var hookIdsSeen = new HashSet<Long>();
        for (final var hook : op.hookCreationDetails()) {
            validateTruePreCheck(hook.hookId() != 0L, INVALID_HOOK_ID);
            //No duplicate hook ids inside one txn
            validateTruePreCheck(hookIdsSeen.add(hook.hookId()), HOOK_ID_REPEATED_IN_CREATION_DETAILS);

            validateTruePreCheck(hook.extensionPoint() != null, BAD_HOOK_REQUEST);
            validateTruePreCheck(hook.hasLambdaEvmHook(), BAD_HOOK_REQUEST);
            final var lambda = hook.lambdaEvmHookOrThrow();

            validateTruePreCheck(lambda.hasSpec(), BAD_HOOK_REQUEST);
            final var spec = lambda.specOrThrow();
            validateTruePreCheck(spec.hasContractId(), BAD_HOOK_REQUEST);

            for (final var storage : lambda.storageUpdates()) {
                validateTruePreCheck(storage.hasStorageSlot() || storage.hasMappingEntries(), BAD_HOOK_REQUEST);

                if (storage.hasStorageSlot()) {
                    final var s = storage.storageSlotOrThrow();

                    validateTruePreCheck(isLengthBetween1And32(s.key()), BAD_HOOK_REQUEST);
                    // value should be between 0..32 bytes (0 length means remove)
                    validateTruePreCheck(hasNonEmptyMax32(s.value()), BAD_HOOK_REQUEST);
                    if (s.value().length() > 0) {
                        validateTruePreCheck(max32(s.value()), BAD_HOOK_REQUEST);
                    }
                } else if (storage.hasMappingEntries()) {
                    final var mapping = storage.mappingEntriesOrThrow();
                    validateTruePreCheck(isLengthBetween1And32(mapping.mappingSlot()), BAD_HOOK_REQUEST);
                    validateTruePreCheck(max32(mapping.mappingSlot()), BAD_HOOK_REQUEST);
                    for (final var e : mapping.entries()) {
                        // entry must choose exactly one key variant
                        final var hasKey = e.hasKey();
                        final var hasPreimage = e.hasPreimage();
                        validateTruePreCheck(hasKey ^ hasPreimage, BAD_HOOK_REQUEST);
                        if (hasKey) {
                            // key: 1..32 bytes, minimal
                            validateTruePreCheck(isLengthBetween1And32(e.key()), BAD_HOOK_REQUEST);
                            validateTruePreCheck(max32(e.key()), BAD_HOOK_REQUEST);
                        }

                        // value: 0..32 bytes, minimal if present (0 length means "remove")
                        validateTruePreCheck(hasNonEmptyMax32(e.value()), BAD_HOOK_REQUEST);
                        if (e.value().length() > 0) {
                            validateTruePreCheck(max32(e.value()), BAD_HOOK_REQUEST);
                        }
                    }
                }
            }
        }
    }

    private static boolean isLengthBetween1And32(final Bytes b) {
        return b != null && b.length() >= 1 && b.length() <= 32;
    }

    private static boolean hasNonEmptyMax32(final Bytes b) {
        return b != null && b.length() >= 0 && b.length() <= 32;
    }

    private static boolean max32(final Bytes b) {
        // No leading zeros except the single-byte 0x00 case
        final var n = b.length();
        if (n == 0) return false;
        if (n == 1) return true;
        return b.toByteArray()[0] != 0;
    }

    public HookSummary summarizeHooks(final List<HookCreationDetails> details) {
        if (details.isEmpty()) {
            return new HookSummary(0L, Collections.emptyList());
        }
        // get the first id from the stream, without any sorting
        final long firstId = details.getFirst().hookId();
        long slots = 0L;
        for (final var detail : details) {
            if (detail.hasLambdaEvmHook()) {
                // count only non-empty values as consuming a slot
                for (final var u : detail.lambdaEvmHookOrThrow().storageUpdates()) {
                    if (u.hasStorageSlot()) {
                        if (u.storageSlotOrThrow().value() != null && u.storageSlotOrThrow().value().length() > 0) {
                            slots++;
                        }
                    } else {
                        for (final var entry : u.mappingEntriesOrThrow().entries()) {
                            if (entry.value() != null && entry.value().length() > 0) {
                                slots++;
                            }
                        }
                    }
                }
            }
        }
        return new HookSummary(slots, details.stream().map(HookCreationDetails::hookId).toList());
    }
}
