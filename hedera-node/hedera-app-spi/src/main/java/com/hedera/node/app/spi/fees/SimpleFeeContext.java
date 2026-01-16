// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import com.hedera.node.app.spi.workflows.QueryContext;

public interface SimpleFeeContext {
    int numTxnSignatures(); // number of signatures in the transaction

    int numTxnBytes(); // added in a different PR so we can have BYTE extras in the node fees

    FeeContext feeContext(); // may be null

    QueryContext queryContext(); // may be null

    EstimationMode estimationMode(); // Intrinsic or Stateful

    enum EstimationMode {
        STATEFUL,
        INTRINSIC
    }
}
