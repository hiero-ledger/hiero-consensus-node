// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.spi.store.ReadableStoreFactory;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueryContextImplTest {
    private static final Bytes EXTERNALIZED_LEDGER_ID = Bytes.fromHex("ab12cd34");

    private final State state = mock(State.class);
    private final ReadableStoreFactory storeFactory = mock(ReadableStoreFactory.class);
    private final Query query = mock(Query.class);
    private final RecordCache recordCache = mock(RecordCache.class);
    private final ExchangeRateManager exchangeRateManager = mock(ExchangeRateManager.class);
    private final FeeCalculator feeCalculator = mock(FeeCalculator.class);
    private final ReadableHistoryStore historyStore = mock(ReadableHistoryStore.class);

    private QueryContextImpl subject;

    @BeforeEach
    void setUp() {
        final var configuration =
                HederaTestConfigBuilder.create().withValue("ledger.id", "0x03").getOrCreateConfig();
        subject = new QueryContextImpl(
                state, storeFactory, query, configuration, recordCache, exchangeRateManager, feeCalculator, null);
    }

    @Test
    void returnsExternalizedLedgerIdWhenAvailable() {
        given(storeFactory.readableStore(ReadableHistoryStore.class)).willReturn(historyStore);
        given(historyStore.getLedgerId()).willReturn(EXTERNALIZED_LEDGER_ID);

        assertThat(subject.ledgerId()).isEqualTo(EXTERNALIZED_LEDGER_ID);
    }

    @Test
    void fallsBackToConfiguredLedgerIdWhenHistoryStoreHasNoLedgerId() {
        final var configuredLedgerId =
                subject.configuration().getConfigData(LedgerConfig.class).id();
        given(storeFactory.readableStore(ReadableHistoryStore.class)).willReturn(historyStore);
        given(historyStore.getLedgerId()).willReturn(null);

        assertThat(subject.ledgerId()).isEqualTo(configuredLedgerId);
    }
}
