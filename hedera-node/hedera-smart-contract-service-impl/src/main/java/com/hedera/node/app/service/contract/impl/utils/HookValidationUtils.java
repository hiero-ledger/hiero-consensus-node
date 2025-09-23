// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.utils;

import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_LAMBDA_STORAGE_UPDATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_CREATION_BYTES_MUST_USE_MINIMAL_REPRESENTATION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_CREATION_BYTES_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_EXTENSION_EMPTY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_CREATION_SPEC;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.minimalRepresentationOf;
import static com.hedera.node.app.service.contract.impl.handlers.LambdaSStoreHandler.MAX_UPDATE_BYTES_LEN;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;

import com.hedera.hapi.node.hooks.HookCreationDetails;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

public class HookValidationUtils {

    public static void validateHook(final HookCreationDetails hook) throws PreCheckException {
        validateTruePreCheck(hook.extensionPoint() != null, HOOK_EXTENSION_EMPTY);
        validateTruePreCheck(hook.hasLambdaEvmHook(), INVALID_HOOK_CREATION_SPEC);

        final var lambda = hook.lambdaEvmHookOrThrow();
        validateTruePreCheck(lambda.hasSpec() && lambda.specOrThrow().hasContractId(), INVALID_HOOK_CREATION_SPEC);

        for (final var storage : lambda.storageUpdates()) {
            validateTruePreCheck(storage.hasStorageSlot() || storage.hasMappingEntries(), EMPTY_LAMBDA_STORAGE_UPDATE);

            if (storage.hasStorageSlot()) {
                final var s = storage.storageSlotOrThrow();
                // The key for a storage slot can be empty. If present, it should have minimal encoding and maximum
                // 32 bytes
                validateWord(s.key());
                validateWord(s.value());
            } else if (storage.hasMappingEntries()) {
                final var mapping = storage.mappingEntriesOrThrow();
                for (final var e : mapping.entries()) {
                    validateTruePreCheck(e.hasKey() || e.hasPreimage(), EMPTY_LAMBDA_STORAGE_UPDATE);
                    if (e.hasKey()) {
                        validateWord(e.keyOrThrow());
                    }
                    validateWord(e.value());
                }
            }
        }
    }

    /**
     * Validates that the given bytes are a valid "word" (i.e. a 32-byte value) for use in a lambda storage update.
     * Specifically, it checks that the length is at most 32 bytes, and that it is in its minimal representation
     * (i.e. no leading zeros).
     * @param bytes the bytes to validate
     * @throws PreCheckException if the bytes are not a valid word
     */
    private static void validateWord(@NonNull final Bytes bytes) throws PreCheckException {
        validateTruePreCheck(bytes.length() <= MAX_UPDATE_BYTES_LEN, HOOK_CREATION_BYTES_TOO_LONG);
        final var minimalBytes = minimalRepresentationOf(bytes);
        validateTruePreCheck(bytes.equals(minimalBytes), HOOK_CREATION_BYTES_MUST_USE_MINIMAL_REPRESENTATION);
    }
}
