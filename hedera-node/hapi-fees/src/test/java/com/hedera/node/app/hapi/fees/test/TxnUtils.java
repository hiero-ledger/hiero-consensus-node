// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.test;

import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TransferList;

public class TxnUtils {
    public static TransferList withAdjustments(
            final AccountID a, final long A, final AccountID b, final long B, final AccountID c, final long C) {
        return TransferList.newBuilder()
                .addAccountAmounts(
                        AccountAmount.newBuilder().setAccountID(a).setAmount(A).build())
                .addAccountAmounts(
                        AccountAmount.newBuilder().setAccountID(b).setAmount(B).build())
                .addAccountAmounts(
                        AccountAmount.newBuilder().setAccountID(c).setAmount(C).build())
                .build();
    }
}
