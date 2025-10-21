// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;

/**
 * A {@code TransactionHandler} contains all methods for the different stages of a single operation.
 */
public interface TransactionHandler {

    /**
     * Pre-handles a transaction, extracting all non-payer keys, which signatures need to be validated
     *
     * @param context the {@link PreHandleContext} which collects all information
     * @throws NullPointerException if {@code context} is {@code null}
     * @throws PreCheckException if the transaction is invalid
     */
    void preHandle(@NonNull final PreHandleContext context) throws PreCheckException;

    /**
     * Validate the transaction body, without involving state or dynamic properties.
     * This method is called as first step of preHandle. If there is any failure,
     * throws a {@link PreCheckException}.
     *
     * <p>Since these checks are pure, they need not be repeated in handle workflow.
     * The result of these checks is cached in the {@link PreHandleContext} for use
     * in handle workflow.
     *
     * @param context the {@link PureChecksContext} which collects all information
     * @throws NullPointerException if {@code txBody} is {@code null}
     * @throws PreCheckException if the transaction is invalid
     */
    void pureChecks(@NonNull PureChecksContext context) throws PreCheckException;

    /**
     * This method can be used to perform any warm up, e.g. loading data into memory that is needed
     * for the transaction to be handled. Providing an implementation is optional.
     *
     * @param context the {@link PreHandleContext} which collects all information
     * @throws NullPointerException if {@code context} is {@code null}
     */
    default void warm(@NonNull final WarmupContext context) {}

    /**
     * Calculates the fees for a transaction
     *
     * @param feeContext the {@link FeeContext} with all information needed for the calculation
     * @return the calculated {@link Fees}
     * @throws NullPointerException if {@code feeContext} is {@code null}
     */
    // NOTE: FUTURE: This method should not be default, but should be implemented by all
    // transaction handlers. This is a temporary measure to avoid merge conflicts.
    @NonNull
    default Fees calculateFees(@NonNull final FeeContext feeContext) {
        return Fees.FREE;
    }

    /**
     * Calculates the fees for a transaction in USD using the Simple Fees HIP
     * https://hips.hedera.com/hip/hip-1261
     * @param feeContext the {@link FeeContext} with all information need for the calculation
     * @return the calculated {@link FeeResult}
     * @throws NullPointerException if {@code feeContext} is {@code null}
     */
    @NonNull
    default FeeResult calculateFeeResult(@NonNull final FeeContext feeContext) {
        throw new RuntimeException("we shouldn't be here. we should be using simple fees");
//        return new FeeResult();
    }
    /**
     * Handles a transaction
     *
     * @param context the {@link HandleContext} which collects all information
     * @throws NullPointerException if {@code context} is {@code null}
     * @throws HandleException if an expected failure occurred
     */
    void handle(@NonNull final HandleContext context) throws HandleException;
}
