// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.LedgerConfig;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;

public final class LedgerValidatorImpl implements LedgerValidator {
    private final ConfigProvider configProvider;

    @Inject
    public LedgerValidatorImpl(@NonNull final ConfigProvider configProvider) {
        this.configProvider = requireNonNull(configProvider);
    }

    @Override
    public void validate(@NonNull final State state) throws IllegalStateException {
        final var config = configProvider.getConfiguration().getConfigData(LedgerConfig.class);
        final var expectedTotalTinyBar = config.totalTinyBarFloat();
        final var tokenStates = state.getReadableStates(TokenService.NAME);
        final ReadableKVState<AccountID, Account> accounts = tokenStates.get(V0490TokenSchema.ACCOUNTS_KEY);
        final var total = new AtomicLong(0L);

        // FUTURE: This would be more efficient if we got the values instead of keys. We also should look at returning
        // a stream instead, so we can parallelize it. This would be much faster when reading from disk.
        accounts.keys().forEachRemaining(accountId -> {
            if (accountId.accountNumOrElse(0L) < 1) {
                throw new IllegalStateException("Invalid account id " + HapiUtils.toString(accountId));
            }
            final var account = accounts.get(accountId);
            if (account == null) {
                throw new IllegalStateException("Missing account " + HapiUtils.toString(accountId));
            }
            total.addAndGet(account.tinybarBalance());
        });

        if (total.get() != expectedTotalTinyBar) {
            throw new IllegalStateException(
                    "Wrong ℏ total, expected " + expectedTotalTinyBar + " but was " + total.get());
        }
    }
}
