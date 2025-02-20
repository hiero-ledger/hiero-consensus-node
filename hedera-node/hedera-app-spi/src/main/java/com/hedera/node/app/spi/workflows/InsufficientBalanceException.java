// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An {@code InsufficientBalanceException} is a {@link PreCheckException} that is thrown, when the
 * payer balance is not sufficient to cover all the required fees. It provides the {@link #estimatedFee}.
 */
public class InsufficientBalanceException extends PreCheckException {

    private final long estimatedFee;

    /**
     * Constructor of {@code InsufficientBalanceException}
     *
     * @param responseCode the {@link ResponseCodeEnum responseCode}
     * @param estimatedFee the estimated fee
     * @throws NullPointerException if {@code responseCode} is {@code null}
     */
    public InsufficientBalanceException(@NonNull final ResponseCodeEnum responseCode, final long estimatedFee) {
        super(responseCode);
        this.estimatedFee = estimatedFee;
    }

    /**
     * Returns the estimated fee
     *
     * @return the estimated fee
     */
    public long getEstimatedFee() {
        return estimatedFee;
    }
}
