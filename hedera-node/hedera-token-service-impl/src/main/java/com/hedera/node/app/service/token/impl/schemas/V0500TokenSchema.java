// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.schemas;

import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_KEY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.spi.WritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.SortedMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A schema that ensures the first contract storage key of each account matches what
 * is set in the shared migration context at key {@code "V0500_FIRST_STORAGE_KEYS"}.
 */
public class V0500TokenSchema extends Schema {
    private static final Logger log = LogManager.getLogger(V0500TokenSchema.class);
    private static final String SHARED_VALUES_KEY = "V0500_FIRST_STORAGE_KEYS";

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(50).patch(0).build();

    public V0500TokenSchema() {
        super(VERSION);
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        requireNonNull(ctx);
        @SuppressWarnings("unchecked")
        final SortedMap<ContractID, Bytes> migratedFirstKeys =
                (SortedMap<ContractID, Bytes>) ctx.sharedValues().get(SHARED_VALUES_KEY);
        if (migratedFirstKeys == null) {
            log.warn("  -> No first contract keys were published, skipping migration");
            return;
        }
        final WritableKVState<AccountID, Account> writableAccounts =
                ctx.newStates().get(ACCOUNTS_KEY);
        migratedFirstKeys.forEach((contractId, firstKey) -> {
            final var accountId = ctx.entityIdFactory().newAccountId(contractId.contractNumOrThrow());
            final var account = writableAccounts.get(accountId);
            if (account == null) {
                log.error("Contract account {} not found in the new state", accountId);
            } else if (!firstKey.equals(account.firstContractStorageKey())) {
                if (!account.smartContract()) {
                    log.error("Non-contract account {} has storage slots", accountId);
                }
                writableAccounts.put(
                        accountId,
                        account.copyBuilder().firstContractStorageKey(firstKey).build());
            }
        });
    }
}
