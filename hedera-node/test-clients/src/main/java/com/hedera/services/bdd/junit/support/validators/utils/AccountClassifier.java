// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.utils;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.services.stream.proto.RecordStreamItem;
import java.util.HashSet;
import java.util.Set;

public class AccountClassifier {
    private final Set<Long> contractAccounts = new HashSet<>();

    public void incorporate(final RecordStreamItem item) {
        try {
            final var txn = CommonUtils.extractTransactionBody(item.getTransaction());
            if (txn.hasContractCreateInstance()) {
                final var createdContract = item.getRecord().getReceipt().getContractID();
                contractAccounts.add(createdContract.getContractNum());
            }
        } catch (final InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean isContract(final long num) {
        return contractAccounts.contains(num);
    }
}
