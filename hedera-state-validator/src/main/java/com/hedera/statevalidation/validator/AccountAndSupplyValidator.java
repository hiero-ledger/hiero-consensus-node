// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.ReadableEntityIdStore;
import com.hedera.node.app.service.entityid.impl.ReadableEntityIdStoreImpl;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.validator.util.ValidationAssertions;
import com.swirlds.state.merkle.StateKeyUtils;
import com.swirlds.state.merkle.StateValue;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * @see LeafBytesValidator
 */
public class AccountAndSupplyValidator implements LeafBytesValidator {

    private static final Logger log = LogManager.getLogger(AccountAndSupplyValidator.class);

    public static final String ACCOUNT_GROUP = "account";

    // 1_000_000_000 tiny bar  = 1 h
    // https://help.hedera.com/hc/en-us/articles/360000674317-What-are-the-official-HBAR-cryptocurrency-denominations-
    // https://help.hedera.com/hc/en-us/articles/360000665518-What-is-the-total-supply-of-HBAR-
    private final long TOTAL_tHBAR_SUPPLY = 5_000_000_000_000_000_000L;

    private final AtomicLong accountsCreated = new AtomicLong(0L);
    private final AtomicLong totalBalance = new AtomicLong(0L);
    private final AtomicLong invalidAccountBalanceCount = new AtomicLong(0);

    private long numAccounts;

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getGroup() {
        return ACCOUNT_GROUP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getName() {
        // Intentionally same as group, as currently it is the only one
        return ACCOUNT_GROUP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(@NonNull final VirtualMapState state) {
        final VirtualMap virtualMap = state.getRoot();
        Objects.requireNonNull(virtualMap);

        final ReadableEntityIdStore entityCounters =
                new ReadableEntityIdStoreImpl(state.getReadableStates(EntityIdService.NAME));
        final ReadableKVState<AccountID, Account> accounts =
                state.getReadableStates(TokenServiceImpl.NAME).get(V0490TokenSchema.ACCOUNTS_STATE_ID);

        Objects.requireNonNull(accounts);
        Objects.requireNonNull(entityCounters);

        this.numAccounts = entityCounters.numAccounts();
        log.debug("Number of accounts: {}", numAccounts);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void processLeafBytes(long dataLocation, @NonNull final VirtualLeafBytes<?> leafBytes) {
        final Bytes keyBytes = leafBytes.keyBytes();
        final Bytes valueBytes = leafBytes.valueBytes();
        final int readKeyStateId = StateKeyUtils.extractStateIdFromStateKeyOneOf(keyBytes);
        final int readValueStateId = StateValue.extractStateIdFromStateValueOneOf(valueBytes);
        if ((readKeyStateId == V0490TokenSchema.ACCOUNTS_STATE_ID)
                && (readValueStateId == V0490TokenSchema.ACCOUNTS_STATE_ID)) {
            try {
                final com.hedera.hapi.platform.state.StateValue stateValue =
                        com.hedera.hapi.platform.state.StateValue.PROTOBUF.parse(valueBytes);
                final Account account = stateValue.value().as();
                final long tinybarBalance = account.tinybarBalance();
                if (tinybarBalance < 0) {
                    invalidAccountBalanceCount.incrementAndGet();
                    log.error("Invalid balance for account {}", account.accountId());
                }
                totalBalance.addAndGet(tinybarBalance);
                accountsCreated.incrementAndGet();
            } catch (final ParseException e) {
                throw new RuntimeException("Failed to parse a key", e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validate() {
        log.debug("Checked {} accounts", accountsCreated.get());

        final boolean ok = TOTAL_tHBAR_SUPPLY == totalBalance.get()
                && numAccounts == accountsCreated.get()
                && invalidAccountBalanceCount.get() == 0;

        ValidationAssertions.requireTrue(
                ok,
                getName(),
                ("""
                        %s validation failed.
                        totalSupplyExpected=%d vs totalSupplyActual=%d
                        accountsExpected=%d vs accountsObserved=%d
                        invalidAccountBalanceCount=%d""")
                        .formatted(
                                getName(),
                                TOTAL_tHBAR_SUPPLY,
                                totalBalance.get(),
                                numAccounts,
                                accountsCreated.get(),
                                invalidAccountBalanceCount.get()));
    }
}
