// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import com.hedera.node.app.quiescence.TxPipelineTracker;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.model.transaction.TimestampedTransaction;
import org.hiero.consensus.transaction.TransactionPoolNexus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@code Hedera} has no test seam, so this uses the same {@code CALLS_REAL_METHODS} partial-mock-plus-reflection
 * approach as {@link HederaPendingTransactionsTest}.
 */
@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class HederaExpiredTransactionsDroppedTest {
    private static final Instant NOW = Instant.parse("2026-11-10T22:22:28Z");

    @Mock
    private HederaInjectionComponent daggerApp;

    @Mock
    private TxPipelineTracker txPipelineTracker;

    @Mock
    private TransactionPoolNexus transactionPool;

    @Mock
    private AppContext appContext;

    @LoggingSubject
    private Hedera hedera;

    @LoggingTarget
    private LogCaptor logCaptor;

    @BeforeEach
    void setUp() throws Exception {
        hedera = mock(Hedera.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        // The mock skips field initializers
        setField("nextDroppedTxnsLog", Instant.MIN);
    }

    @Test
    void droppedTransactionsStopCountingAsInFlight() throws Exception {
        setField("daggerApp", daggerApp);
        given(daggerApp.txPipelineTracker()).willReturn(txPipelineTracker);

        hedera.onExpiredTransactionsDropped(3, NOW);

        verify(txPipelineTracker).countDropped(3);
    }

    @Test
    void dropsBeforeTheAppIsBuiltAreStillLogged() {
        hedera.onExpiredTransactionsDropped(3, NOW);

        assertThat(dropLogs()).containsExactly("Dropped 3 expired user transactions from the transaction pool");
    }

    @Test
    void logsDropsAtMostOncePerMinuteWithTheirTotal() {
        hedera.onExpiredTransactionsDropped(3, NOW);
        hedera.onExpiredTransactionsDropped(4, NOW.plusSeconds(30));
        hedera.onExpiredTransactionsDropped(5, NOW.plusSeconds(60));

        assertThat(dropLogs())
                .containsExactly(
                        "Dropped 3 expired user transactions from the transaction pool",
                        "Dropped 9 expired user transactions from the transaction pool");
    }

    @Test
    void logsDropsHeldBackByTheLimitAtTheNextEventBuildEvenWithoutMoreDrops() throws Exception {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        final List<TimestampedTransaction> fromPool =
                List.of(new TimestampedTransaction(Bytes.wrap(new byte[] {1}), NOW, false));
        setField("transactionPool", transactionPool);
        setField("appContext", appContext);
        given(appContext.instantSource()).willReturn(now::get);
        given(transactionPool.getTransactionsForEvent()).willReturn(fromPool);

        hedera.onExpiredTransactionsDropped(3, NOW);
        hedera.onExpiredTransactionsDropped(4, NOW.plusSeconds(30));
        hedera.getTransactionsForEvent();
        now.set(NOW.plusSeconds(60));
        assertThat(hedera.getTransactionsForEvent()).isSameAs(fromPool);
        now.set(NOW.plusSeconds(120));
        hedera.getTransactionsForEvent();

        assertThat(dropLogs())
                .containsExactly(
                        "Dropped 3 expired user transactions from the transaction pool",
                        "Dropped 4 expired user transactions from the transaction pool");
    }

    @Test
    void neverThrowsBackIntoThePool() throws Exception {
        setField("daggerApp", daggerApp);
        given(daggerApp.txPipelineTracker()).willThrow(new IllegalStateException("No tracker"));

        assertThatCode(() -> hedera.onExpiredTransactionsDropped(3, NOW)).doesNotThrowAnyException();

        assertThat(logCaptor.errorLogs())
                .anyMatch(log -> log.startsWith(
                        "Failed to account for 3 expired transactions dropped from the transaction pool"));
    }

    // The captured logger is process-wide, so keep only this message
    private List<String> dropLogs() {
        return logCaptor.infoLogs().stream()
                .filter(log -> log.startsWith("Dropped "))
                .toList();
    }

    private void setField(final String name, final Object value) throws Exception {
        final var field = Hedera.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(hedera, value);
    }
}
