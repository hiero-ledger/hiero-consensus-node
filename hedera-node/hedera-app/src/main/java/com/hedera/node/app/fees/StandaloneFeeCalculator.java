// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.pbj.runtime.ParseException;
import org.hiero.hapi.fees.FeeResult;

public interface StandaloneFeeCalculator {
    FeeResult calculate(Transaction transaction, ServiceFeeCalculator.EstimationMode mode) throws ParseException;
}
