// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An {@code InsufficientBalanceException} caused by specifically by the payer being
 * unable or unwilling to cover the network fee for a transaction.
 *
 * <p>If thrown in handle, this is a node due diligence failure. The node that submitted
 * the transaction will be charged its network fee as a penalty.
 *
 * <p>We still report the total expected fee in the {@code estimatedFee} field, however.
 */
public class InsufficientNetworkFeeException extends InsufficientBalanceException {
    /**
     * Constructs an {@link InsufficientBalanceException} that represents a due
     * diligence failure by the node submitting the transaction.
     *
     * @param responseCode the {@link ResponseCodeEnum responseCode}
     * @param estimatedFee the estimated fee
     */
    public InsufficientNetworkFeeException(@NonNull final ResponseCodeEnum responseCode, final long estimatedFee) {
        super(responseCode, estimatedFee);
    }
}
