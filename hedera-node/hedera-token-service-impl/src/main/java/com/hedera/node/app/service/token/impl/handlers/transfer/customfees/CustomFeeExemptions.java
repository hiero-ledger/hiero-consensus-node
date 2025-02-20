// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer.customfees;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.CustomFee;

/**
 * Our policy for payer exemptions is the following:
 *
 * <ul>
 *   <li>A token's treasury is exempt from all its custom fees.
 *   <li>A fee collection account is exempt from any fee for which it would be the collector.
 *   <li>A fee collection account is exempt from any of its token's fees with {@code
 *       all_collectors_are_exempt=true}.
 * </ul>.
 */
public final class CustomFeeExemptions {
    private CustomFeeExemptions() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Given the fee metadata for a token, and one of this token's custom fees, returns whether the
     * given payer is exempt from the specific custom fee provided.
     * Payer is exempt if:
     * <ul>
     *     <li>the payer is the treasury of the token
     *     <li>the payer is the fee collector of the fee
     *     <li>if allCollectorsAreExempt set to true and payer is collector for any fee on token
     * </ul>
     *
     * @param token  metadata for the token that "owns" the specific custom fee
     * @param fee    the fee to check for a payer exemption
     * @param sender the potential fee payer
     * @return whether the payer is exempt from the fee
     */
    public static boolean isPayerExempt(final Token token, final CustomFee fee, final AccountID sender) {
        if (token.treasuryAccountIdOrThrow().equals(sender)) {
            return true;
        }
        if (fee.feeCollectorAccountIdOrElse(AccountID.DEFAULT).equals(sender)) {
            return true;
        }
        if (fee.allCollectorsAreExempt()) {
            return isPayerCollectorFor(token, sender);
        } else {
            // If payer isn't the treasury or the collector of a fee without
            // a global collector exemption, then it must pay, nothing more to check
            return false;
        }
    }

    /**
     * Returns whether the given payer is a collector for any of the fees on the given token.
     * @param token metadata for the token to check
     * @param sender the potential fee payer
     * @return whether the payer is a collector for any of the fees on the given token
     */
    private static boolean isPayerCollectorFor(final Token token, final AccountID sender) {
        for (final var fee : token.customFees()) {
            if (fee.feeCollectorAccountIdOrElse(AccountID.DEFAULT).equals(sender)) {
                return true;
            }
        }
        return false;
    }
}
