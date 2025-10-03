// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer.customfees;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;

/**
 * A wrapper around an assessed custom fee and the all adjustments happened while reclaiming
 * from multiple payers when assessing a fractional fee.
 *
 * @param assessedCustomFee the assessed custom fee
 * @param multiPayerDeltas the adjustments to the assessed fee's payer in a multi-payer fractional fee scenario,
 *                         or {@code null} if not applicable
 */
public record AssessedFeeWithPayerDebits(
        @NonNull AssessedCustomFee assessedCustomFee, @Nullable Map<AccountID, Long> multiPayerDeltas) {}
