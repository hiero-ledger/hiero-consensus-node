// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.state.service;

import static com.hedera.statevalidation.util.ParallelProcessingUtils.VALIDATOR_FORK_JOIN_POOL;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.ReadableEntityIdStore;
import com.hedera.node.app.service.entityid.impl.ReadableEntityIdStoreImpl;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.report.SlackReportGenerator;
import com.hedera.statevalidation.util.junit.StateResolver;
import com.swirlds.base.utility.Pair;
import com.swirlds.common.threading.manager.AdHocThreadManager;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.merkle.StateKeyUtils;
import com.swirlds.state.merkle.StateValue;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualMapMigration;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.interrupt.InterruptableConsumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({StateResolver.class, SlackReportGenerator.class})
@Tag("account")
public class AccountValidator {

    private static final Logger log = LogManager.getLogger(AccountValidator.class);

    // 1_000_000_000 tiny bar  = 1 h
    // https://help.hedera.com/hc/en-us/articles/360000674317-What-are-the-official-HBAR-cryptocurrency-denominations-
    // https://help.hedera.com/hc/en-us/articles/360000665518-What-is-the-total-supply-of-HBAR-
    final long TOTAL_tHBAR_SUPPLY = 5_000_000_000_000_000_000L;

    @Test
    void validate(DeserializedSignedState deserializedState) throws InterruptedException {
        final MerkleNodeState merkleNodeState =
                deserializedState.reservedSignedState().get().getState();

        final VirtualMap virtualMap = (VirtualMap) merkleNodeState.getRoot();
        assertNotNull(virtualMap);

        final ReadableEntityIdStore entityCounters =
                new ReadableEntityIdStoreImpl(merkleNodeState.getReadableStates(EntityIdService.NAME));
        final ReadableKVState<AccountID, Account> accounts =
                merkleNodeState.getReadableStates(TokenServiceImpl.NAME).get(V0490TokenSchema.ACCOUNTS_STATE_ID);

        assertNotNull(accounts);
        assertNotNull(entityCounters);

        final long numAccounts = entityCounters.numAccounts();
        log.debug("Number of accounts: {}", numAccounts);

        AtomicLong accountsCreated = new AtomicLong(0L);
        AtomicLong totalBalance = new AtomicLong(0L);

        final int accountStateId = V0490TokenSchema.ACCOUNTS_STATE_ID;

        InterruptableConsumer<Pair<Bytes, Bytes>> handler = pair -> {
            final Bytes keyBytes = pair.left();
            final Bytes valueBytes = pair.right();
            final int readKeyStateId = StateKeyUtils.extractStateIdFromStateKeyOneOf(keyBytes);
            final int readValueStateId = StateValue.extractStateIdFromStateValueOneOf(valueBytes);
            if ((readKeyStateId == accountStateId) && (readValueStateId == accountStateId)) {
                try {
                    final com.hedera.hapi.platform.state.StateValue stateValue =
                            com.hedera.hapi.platform.state.StateValue.PROTOBUF.parse(valueBytes);
                    final Account account = stateValue.value().as();
                    final long tinybarBalance = account.tinybarBalance();
                    assertTrue(tinybarBalance >= 0);
                    totalBalance.addAndGet(tinybarBalance);
                    accountsCreated.incrementAndGet();
                } catch (final ParseException e) {
                    throw new RuntimeException("Failed to parse a key", e);
                }
            }
        };

        VirtualMapMigration.extractVirtualMapDataC(
                AdHocThreadManager.getStaticThreadManager(),
                virtualMap,
                handler,
                VALIDATOR_FORK_JOIN_POOL.getParallelism());

        assertEquals(TOTAL_tHBAR_SUPPLY, totalBalance.get());
        assertEquals(accountsCreated.get(), numAccounts);
    }
}
