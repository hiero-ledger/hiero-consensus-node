// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.fees;

import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.TransferList;
import java.util.List;
import java.util.function.Predicate;

public class TinyBarTransfers implements Predicate<TransferList> {
    private final TransferList transfers;

    public TinyBarTransfers(TransferList transfers) {
        this.transfers = transfers;
    }

    public TransferList getTransfers() {
        return transfers;
    }

    @Override
    public boolean test(TransferList transfers) {
        /* We currently assume that the different types of transfers
        (fee payments, CryptoTransfer transaction, SC activity)
        create separate entries in the List<AccountAmount>; and
        these entries are not consolidated. */
        return transfersContains(transfers.getAccountAmountsList(), this.transfers.getAccountAmountsList());
    }

    private boolean transfersContains(List<AccountAmount> amounts, List<AccountAmount> changes) {
        return changes.stream()
                .allMatch(c -> amounts.stream().filter(c::equals).findAny().isPresent());
    }
}
