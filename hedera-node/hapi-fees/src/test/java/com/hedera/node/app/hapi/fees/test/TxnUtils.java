// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.test;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransferList;

public class TxnUtils {
    public static TransferList withAdjustments(
            final AccountID a, final long A, final AccountID b, final long B, final AccountID c, final long C) {
        return TransferList.newBuilder()
                .accountAmounts(
                        AccountAmount.newBuilder().accountID(a).amount(A).build(),
                        AccountAmount.newBuilder().accountID(b).amount(B).build(),
                        AccountAmount.newBuilder().accountID(c).amount(C).build())
                .build();
    }
}
