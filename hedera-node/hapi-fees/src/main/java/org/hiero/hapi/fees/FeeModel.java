// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import com.hedera.hapi.node.base.HederaFunctionality;
import java.util.Map;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;

/**
 * FeeModel represents logic to calculate the fees
 * for a transaction. Many transactions can be handled
 * by the common BaseFeeModel but some might need completely
 * custom implementation.
 */
public interface FeeModel {
    /** Get the @{@link HederaFunctionality} that this fee module handles. */
    String getApi();

    /** A string description of this fee model. Used for human-readable output. */
    String getDescription();

    /** Compute the fee for a specific transaction. */
    FeeResult computeFee(Map<Extra, Long> params, FeeSchedule feeSchedule);
}
