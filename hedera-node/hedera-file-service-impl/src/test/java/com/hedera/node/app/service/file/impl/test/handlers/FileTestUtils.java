// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.test.handlers;

import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.token.ReadableAccountStore;

public final class FileTestUtils {
    private FileTestUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    static Key mockPayerLookup(Key key, AccountID accountId, ReadableAccountStore accountStore) {
        final var account = Account.newBuilder().accountId(accountId).key(key).build();
        lenient().when(accountStore.getAccountById(accountId)).thenReturn(account);
        return key;
    }
}
