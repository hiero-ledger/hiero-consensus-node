// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.handlers;

import static java.util.Collections.emptySet;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.spi.workflows.TransactionKeys;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

class TestTransactionKeys implements TransactionKeys {
    private final Key payerKey;
    private final Set<Key> optionalKeys;
    private final Set<Key> requiredKeys;

    TestTransactionKeys(final Key payer, final Set<Key> required, final Set<Key> optional) {
        payerKey = payer;
        requiredKeys = required;
        optionalKeys = optional;
    }

    @Override
    @NonNull
    public Key payerKey() {
        return payerKey;
    }

    @Override
    @NonNull
    public Set<Key> requiredNonPayerKeys() {
        return requiredKeys;
    }

    @Override
    public Set<Account> requiredHollowAccounts() {
        return emptySet();
    }

    @Override
    public Set<Key> optionalNonPayerKeys() {
        return optionalKeys;
    }

    @Override
    public Set<Account> optionalHollowAccounts() {
        return null;
    }
}
