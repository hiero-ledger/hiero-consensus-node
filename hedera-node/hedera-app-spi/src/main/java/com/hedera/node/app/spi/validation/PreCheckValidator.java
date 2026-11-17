// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.validation;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_MAX_CUSTOM_FEES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_CUSTOM_FEES_IS_NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hedera.node.app.spi.validation.Validations.validateAccountID;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.transaction.CustomFeeLimit;
import com.hedera.node.app.spi.workflows.PreCheckException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PreCheckValidator {
    private static final List<HederaFunctionality> FUNCTIONALITIES_WITH_MAX_CUSTOM_FEES =
            List.of(CONSENSUS_SUBMIT_MESSAGE);

    /** No instantiation permitted */
    private PreCheckValidator() {}

    /**
     * Checks whether the memo passes checks.
     *
     * @param memo The memo to check.
     * @throws PreCheckException if the memo is too long, or otherwise fails the check.
     */
    public static void checkMemo(@Nullable final String memo, final int maxMemoUtf8Bytes) throws PreCheckException {
        if (memo == null) {
            return; // Nothing to do, a null memo is valid.
        }
        // Verify the number of bytes does not exceed the maximum allowed.
        // Note that these bytes are counted in UTF-8.
        final var buffer = memo.getBytes(StandardCharsets.UTF_8);
        if (buffer.length > maxMemoUtf8Bytes) {
            throw new PreCheckException(MEMO_TOO_LONG);
        }
        // FUTURE: This check should be removed after mirror node supports 0x00 in memo fields
        for (final byte b : buffer) {
            if (b == 0) {
                throw new PreCheckException(INVALID_ZERO_BYTE_IN_STRING);
            }
        }
    }

    /**
     * Checks if the maximum custom fee limits are valid for the given functionality.
     *
     * <p>Every entity id in the list is validated, including the entries that fee assessment will ignore because
     * they do not name the transaction payer.
     *
     * @param maxCustomFeeList The list of custom fee limits to validate
     * @param functionality The transaction functionality being executed
     * @param shard This network's shard
     * @param realm This network's realm
     * @throws PreCheckException if the max custom fees are not supported for the given functionality,
     *         or if the custom fee limits contain invalid values
     */
    public static void checkMaxCustomFees(
            final List<CustomFeeLimit> maxCustomFeeList,
            final HederaFunctionality functionality,
            final long shard,
            final long realm)
            throws PreCheckException {
        if (!FUNCTIONALITIES_WITH_MAX_CUSTOM_FEES.contains(functionality) && !maxCustomFeeList.isEmpty()) {
            throw new PreCheckException(MAX_CUSTOM_FEES_IS_NOT_SUPPORTED);
        }

        // check required fields
        for (final var maxCustomFee : maxCustomFeeList) {
            if (maxCustomFee.fees().isEmpty()) {
                throw new PreCheckException(INVALID_MAX_CUSTOM_FEES);
            }
            final var accountId = validateAccountID(maxCustomFee.accountId(), INVALID_MAX_CUSTOM_FEES);
            if (accountId.shardNum() != shard || accountId.realmNum() != realm) {
                throw new PreCheckException(INVALID_MAX_CUSTOM_FEES);
            }
            for (final var fee : maxCustomFee.fees()) {
                if (fee.amount() < 0) {
                    throw new PreCheckException(INVALID_MAX_CUSTOM_FEES);
                }
                if (fee.hasDenominatingTokenId() && !isPlausibleToken(fee.denominatingTokenIdOrThrow(), shard, realm)) {
                    throw new PreCheckException(INVALID_MAX_CUSTOM_FEES);
                }
            }
        }
    }

    private static boolean isPlausibleToken(@NonNull final TokenID tokenId, final long shard, final long realm) {
        return tokenId.shardNum() == shard && tokenId.realmNum() == realm && tokenId.tokenNum() > 0;
    }
}
