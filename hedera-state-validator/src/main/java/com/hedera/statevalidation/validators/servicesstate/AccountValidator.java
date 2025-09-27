// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators.servicesstate;

import static com.hedera.statevalidation.validators.ValidationAssertions.requireEqual;
import static com.hedera.statevalidation.validators.ValidationAssertions.requireNonNull;
import static com.hedera.statevalidation.validators.ValidationAssertions.requireTrue;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.ReadableEntityIdStoreImpl;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.spi.ids.ReadableEntityIdStore;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.validators.KeyValueValidator;
import com.hedera.statevalidation.validators.ValidationException;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.state.merkle.StateKeyUtils;
import com.swirlds.state.merkle.StateValue;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.virtualmap.VirtualMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This validation could be built on top of ValidateLeafIndex.
 */
public class AccountValidator implements KeyValueValidator {

    private static final Logger log = LogManager.getLogger(AccountValidator.class);

    // 1_000_000_000 tiny bar  = 1 h
    // https://help.hedera.com/hc/en-us/articles/360000674317-What-are-the-official-HBAR-cryptocurrency-denominations-
    // https://help.hedera.com/hc/en-us/articles/360000665518-What-is-the-total-supply-of-HBAR-
    final long TOTAL_tHBAR_SUPPLY = 5_000_000_000_000_000_000L;

    private final AtomicLong accountsCreated = new AtomicLong(0L);

    private final AtomicLong totalBalance = new AtomicLong(0L);

    private long numAccounts;

    private int accountStateId;

    public static final String ACCOUNT = "account";

    @Override
    public String getTag() {
        return ACCOUNT;
    }

    @Override
    public void initialize(MerkleNodeState merkleNodeState) {
        final VirtualMap virtualMap = (VirtualMap) merkleNodeState.getRoot();
        requireNonNull(virtualMap, ACCOUNT);

        final ReadableEntityIdStore entityCounters =
                new ReadableEntityIdStoreImpl(merkleNodeState.getReadableStates(EntityIdService.NAME));
        requireNonNull(entityCounters, ACCOUNT);

        final ReadableKVState<AccountID, Account> accounts =
                merkleNodeState.getReadableStates(TokenServiceImpl.NAME).get(V0490TokenSchema.ACCOUNTS_STATE_ID);
        requireNonNull(accounts, ACCOUNT);

        numAccounts = entityCounters.numAccounts();
        log.debug("Number of accounts: {}", numAccounts);

        accountStateId = V0490TokenSchema.ACCOUNTS_STATE_ID;
    }

    @Override
    public void processKeyValue(Bytes keyBytes, Bytes valueBytes) {
        final int readKeyStateId = StateKeyUtils.extractStateIdFromStateKeyOneOf(keyBytes);
        final int readValueStateId = StateValue.extractStateIdFromStateValueOneOf(valueBytes);
        if ((readKeyStateId == accountStateId) && (readValueStateId == accountStateId)) {
            try {
                final com.hedera.hapi.platform.state.StateValue stateValue =
                        com.hedera.hapi.platform.state.StateValue.PROTOBUF.parse(valueBytes);
                final Account account = stateValue.value().as();
                final long tinybarBalance = account.tinybarBalance();
                requireTrue(tinybarBalance >= 0, ACCOUNT);
                totalBalance.addAndGet(tinybarBalance);
                accountsCreated.incrementAndGet();
            } catch (final ParseException e) {
                throw new ValidationException(ACCOUNT, "Failed to parse a key", e);
            }
        }
    }

    @Override
    public void validate() {
        requireEqual(TOTAL_tHBAR_SUPPLY, totalBalance.get(), ACCOUNT);
        requireEqual(accountsCreated.get(), numAccounts, ACCOUNT);
    }
}
