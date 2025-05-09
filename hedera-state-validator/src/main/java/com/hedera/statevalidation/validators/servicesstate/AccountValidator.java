/*
 * Copyright (C) 2023-2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.statevalidation.validators.servicesstate;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.statevalidation.parameterresolver.ReportResolver;
import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.hedera.statevalidation.parameterresolver.VirtualMapAndDataSourceProvider;
import com.hedera.statevalidation.reporting.Report;
import com.hedera.statevalidation.reporting.SlackReportGenerator;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.spi.ReadableKVState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith({StateResolver.class, ReportResolver.class, SlackReportGenerator.class})
@Tag("account")
public class AccountValidator {

    private static final Logger log = LogManager.getLogger(AccountValidator.class);

    // 1_000_000_000 tiny bar  = 1 h
    // https://help.hedera.com/hc/en-us/articles/360000674317-What-are-the-official-HBAR-cryptocurrency-denominations-
    // https://help.hedera.com/hc/en-us/articles/360000665518-What-is-the-total-supply-of-HBAR-
    final long TOTAL_tHBAR_SUPPLY = 5_000_000_000_000_000_000L;

    @ParameterizedTest
    @ArgumentsSource(VirtualMapAndDataSourceProvider.class)
    void validate(DeserializedSignedState deserializedState, Report report) throws IOException {
        final MerkleNodeState servicesState = deserializedState.reservedSignedState().get().getState();

        ReadableKVState<AccountID, Account> accounts =
                servicesState.getReadableStates(TokenServiceImpl.NAME).get(V0490TokenSchema.ACCOUNTS_KEY);

        assertNotNull(accounts);
        log.debug("Number of accounts: {}", accounts.size());

        AtomicLong totalBalance = new AtomicLong(0L);
        accounts.keys().forEachRemaining(key -> {
            final var value = accounts.get(key);
            long tinybarBalance = value.tinybarBalance();
            assertTrue(tinybarBalance >= 0);
            totalBalance.addAndGet(tinybarBalance);
        });

        assertEquals(TOTAL_tHBAR_SUPPLY, totalBalance.get());
    }
}
