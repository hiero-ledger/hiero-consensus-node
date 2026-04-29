// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.pbj.runtime.ParseException;
import org.hiero.hapi.fees.FeeResult;

public interface StandaloneFeeCalculator {
    FeeResult calculateIntrinsic(Transaction transaction) throws ParseException;

    FeeResult calculateStateful(Transaction transaction, FeeContext feeContext, QueryContext queryContext)
            throws ParseException;
}
