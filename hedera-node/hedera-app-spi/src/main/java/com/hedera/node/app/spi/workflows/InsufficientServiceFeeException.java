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
public class InsufficientServiceFeeException extends InsufficientBalanceException {
    public InsufficientServiceFeeException(@NonNull ResponseCodeEnum responseCode, long estimatedFee) {
        super(responseCode, estimatedFee);
    }
}
