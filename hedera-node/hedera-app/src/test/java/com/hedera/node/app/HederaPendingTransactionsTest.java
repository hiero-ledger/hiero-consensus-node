// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZING;
import static org.hiero.consensus.platformstate.PlatformStateAccessor.GENESIS_ROUND;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.quiescence.QuiescenceController;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.workflows.clpr.ClprRuntime;
import com.hedera.node.app.workflows.ingest.IngestWorkflow;
import com.hedera.node.app.workflows.ingest.pending.PendingTransactionsRestorer;
import com.hedera.node.app.workflows.ingest.pending.PendingTransactionsSaver;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableStates;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.transaction.TransactionPoolNexus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the {@link Hedera} hooks for saving and restoring pending user transactions. Uses the same
 * {@code CALLS_REAL_METHODS} partial-mock-plus-reflection approach as {@link HederaCatastrophicFailureOrderingTest}.
 */
@ExtendWith(MockitoExtension.class)
class HederaPendingTransactionsTest {
    @Mock
    private HederaInjectionComponent daggerApp;

    @Mock
    private QuiescenceController quiescenceController;

    @Mock
    private ClprRuntime clprRuntime;

    @Mock
    private IngestWorkflow ingestWorkflow;

    @Mock
    private BlockStreamManager blockStreamManager;

    @Mock
    private BlockRecordManager blockRecordManager;

    @Mock
    private TransactionPoolNexus transactionPool;

    @Mock
    private Platform platform;

    @Mock
    private ConfigProviderImpl configProvider;

    @Mock
    private PendingTransactionsSaver saver;

    @Mock
    private PendingTransactionsRestorer restorer;

    @Mock
    private Round round;

    @Mock
    private State state;

    @Mock
    private ReadableStates readableStates;

    private Hedera hedera;

    @BeforeEach
    void setUp() throws Exception {
        hedera = mock(Hedera.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        setField("daggerApp", daggerApp);
        setField("configProvider", configProvider);
        setField("platform", platform);
        setField("transactionPool", transactionPool);
        setField("pendingTransactionsSaver", saver);
        setField("pendingTransactionsRestorer", restorer);
    }

    @Test
    void freezingDrainsThePool() {
        stubStatusBasics();

        hedera.newPlatformStatus(FREEZING);

        verify(saver).drain();
    }

    @Test
    void activeStartsTheRestoreWithTheMaxValidDurationAsWindow() throws Exception {
        stubStatusBasics();
        given(daggerApp.clprRuntime()).willReturn(clprRuntime);
        doNothing().when(hedera).startGrpcServer();

        hedera.newPlatformStatus(ACTIVE);

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Function<Bytes, ResponseCodeEnum>> submitterCaptor =
                ArgumentCaptor.forClass(Function.class);
        verify(restorer).restoreAsync(submitterCaptor.capture(), eq(Duration.ofSeconds(180)));

        // A RECONNECT rebuilds the Dagger graph; the submitter must resolve it lazily, not bind the stale one
        final var reconnectedDaggerApp = mock(HederaInjectionComponent.class);
        final var reconnectedIngestWorkflow = mock(IngestWorkflow.class);
        given(reconnectedDaggerApp.ingestWorkflow()).willReturn(reconnectedIngestWorkflow);
        setField("daggerApp", reconnectedDaggerApp);

        submitterCaptor.getValue().apply(Bytes.EMPTY);

        verify(reconnectedIngestWorkflow).submitRestoredTransaction(Bytes.EMPTY);
        verify(ingestWorkflow, never()).submitRestoredTransaction(any());
    }

    @Test
    void disabledFeatureIsANoOp() throws Exception {
        stubStatusBasics();
        setField("pendingTransactionsSaver", null);
        setField("pendingTransactionsRestorer", null);

        hedera.newPlatformStatus(FREEZING);

        verify(transactionPool).updatePlatformStatus(FREEZING);
        verify(transactionPool, never()).drainApplicationTransactions();
    }

    @Test
    void genesisWithEmptyPlatformStateDoesNotThrow() {
        // At GENESIS the platform state singleton is still empty; roundOf(state) must fall back to GENESIS_ROUND
        // instead of NPEing.
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.isEmpty()).willReturn(true);

        hedera.initializePendingTransactions(state, InitTrigger.GENESIS);

        verify(restorer).onStateInitialized(InitTrigger.GENESIS, GENESIS_ROUND);
    }

    @Test
    void freezeRoundWaitIncludesTheSave() throws Exception {
        stubFreezeWait("500ms");
        final var save = new CompletableFuture<Void>();

        final long start = System.nanoTime();
        hedera.awaitFreezeRoundBlockProofsAndAcks(round, save);
        final var elapsed = Duration.ofNanos(System.nanoTime() - start);

        // Block proofs and WRB writers are done, so only the unfinished save can hold the wait until the timeout
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(400));
    }

    @Test
    void finishedSaveDoesNotDelayTheFreezeRound() throws Exception {
        stubFreezeWait("5s");

        final long start = System.nanoTime();
        hedera.awaitFreezeRoundBlockProofsAndAcks(round, CompletableFuture.completedFuture(null));
        final var elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
    }

    private void stubStatusBasics() {
        given(daggerApp.quiescenceController()).willReturn(quiescenceController);
        given(configProvider.getConfiguration())
                .willReturn(new VersionedConfigImpl(HederaTestConfigBuilder.createConfig(), 1L));
    }

    private void stubFreezeWait(final String timeout) {
        // FILE writer mode skips the block node acknowledgement wait (default FILE_AND_GRPC would need a connection
        // manager)
        final var config = HederaTestConfigBuilder.create()
                .withConfigDataType(HederaConfig.class)
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("hedera.nowFrozenWriteTimeout", timeout)
                .withValue("blockStream.writerMode", "FILE")
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1L));
        given(daggerApp.blockStreamManager()).willReturn(blockStreamManager);
        given(daggerApp.blockRecordManager()).willReturn(blockRecordManager);
        given(blockStreamManager.pendingBlockProofsFuture()).willReturn(CompletableFuture.completedFuture(null));
        given(blockRecordManager.noOpenWrbWritersFuture()).willReturn(CompletableFuture.completedFuture(null));
    }

    private void setField(final String name, final Object value) throws Exception {
        final Field field = Hedera.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(hedera, value);
    }
}
