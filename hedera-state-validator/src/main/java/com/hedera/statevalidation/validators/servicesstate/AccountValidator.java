// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators.servicesstate;

import static com.hedera.statevalidation.validators.ParallelProcessingUtil.VALIDATOR_FORK_JOIN_POOL;
import static com.swirlds.state.merkle.StateUtils.extractStateKeyValueStateId;
import static com.swirlds.state.merkle.StateUtils.stateIdFor;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.ReadableEntityIdStoreImpl;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.spi.ids.ReadableEntityIdStore;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.parameterresolver.ReportResolver;
import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.hedera.statevalidation.reporting.Report;
import com.hedera.statevalidation.reporting.SlackReportGenerator;
import com.swirlds.base.utility.Pair;
import com.swirlds.common.threading.manager.AdHocThreadManager;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
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

@ExtendWith({StateResolver.class, ReportResolver.class, SlackReportGenerator.class})
@Tag("account")
public class AccountValidator {

    private static final Logger log = LogManager.getLogger(AccountValidator.class);

    // 1_000_000_000 tiny bar  = 1 h
    // https://help.hedera.com/hc/en-us/articles/360000674317-What-are-the-official-HBAR-cryptocurrency-denominations-
    // https://help.hedera.com/hc/en-us/articles/360000665518-What-is-the-total-supply-of-HBAR-
    final long TOTAL_tHBAR_SUPPLY = 5_000_000_000_000_000_000L;

    @Test
    void validate(DeserializedSignedState deserializedState, Report report) throws InterruptedException {
        final MerkleNodeState merkleNodeState =
                deserializedState.reservedSignedState().get().getState();

        final VirtualMap virtualMap = (VirtualMap) merkleNodeState.getRoot();
        assertNotNull(virtualMap);

        final ReadableEntityIdStore entityCounters =
                new ReadableEntityIdStoreImpl(merkleNodeState.getReadableStates(EntityIdService.NAME));
        final ReadableKVState<AccountID, Account> accounts =
                merkleNodeState.getReadableStates(TokenServiceImpl.NAME).get(V0490TokenSchema.ACCOUNTS_KEY);

        assertNotNull(accounts);
        assertNotNull(entityCounters);

        final long numAccounts = entityCounters.numAccounts();
        log.debug("Number of accounts: {}", numAccounts);

        AtomicLong accountsCreated = new AtomicLong(0L);
        AtomicLong totalBalance = new AtomicLong(0L);

        final int targetStateId = stateIdFor(TokenServiceImpl.NAME, V0490TokenSchema.ACCOUNTS_KEY);

        InterruptableConsumer<Pair<Bytes, Bytes>> handler = pair -> {
            final Bytes keyBytes = pair.left();
            final Bytes valueBytes = pair.right();
            final int readKeyStateId = extractStateKeyValueStateId(keyBytes);
            final int readValueStateId = extractStateKeyValueStateId(valueBytes);
            if ((readKeyStateId == targetStateId) && (readValueStateId == targetStateId)) {
                try {
                    final StateValue stateValue = StateValue.PROTOBUF.parse(valueBytes);
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
