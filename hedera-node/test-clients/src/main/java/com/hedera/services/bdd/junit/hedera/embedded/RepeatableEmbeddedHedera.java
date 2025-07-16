// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.system.transaction.TransactionWrapperUtils.createAppPayloadWrapper;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.hedera.embedded.fakes.AbstractFakePlatform;
import com.hedera.services.bdd.junit.hedera.embedded.fakes.FakeConsensusEvent;
import com.hedera.services.bdd.junit.hedera.embedded.fakes.FakeEvent;
import com.hedera.services.bdd.junit.hedera.embedded.fakes.FakeRound;
import com.hedera.services.bdd.junit.hedera.embedded.fakes.LapsingBlockHashSigner;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.system.Platform;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

/**
 * An embedded Hedera node that handles transactions synchronously on ingest and thus
 * cannot be used in concurrent tests.
 */
public class RepeatableEmbeddedHedera extends AbstractEmbeddedHedera implements EmbeddedHedera {
    private static final Instant FIXED_POINT = Instant.parse("2024-06-24T12:05:41.487328Z");

    // Using a default round duration of one second makes it easier to structure tests with
    // time-based events like transactions scheduled with wait_for_expiry=true
    public static final Duration DEFAULT_ROUND_DURATION = Duration.ofSeconds(1);

    private final FakeTime time = new FakeTime(FIXED_POINT, Duration.ZERO);
    private final SynchronousFakePlatform platform;
    private final Queue<Runnable> pendingNodeSubmissions = new ArrayDeque<>();

    private static final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> NO_OP_CALLBACK = ignore -> {};
    private Consumer<ScopedSystemTransaction<StateSignatureTransaction>> preHandleStateSignatureCallback =
            NO_OP_CALLBACK;
    private Consumer<ScopedSystemTransaction<StateSignatureTransaction>> handleStateSignatureCallback = NO_OP_CALLBACK;

    // The amount of consensus time that will be simulated to elapse before the next transaction---note
    // that in repeatable mode, every transaction gets its own event, and each event gets its own round
    private Duration roundDuration = DEFAULT_ROUND_DURATION;

    public RepeatableEmbeddedHedera(@NonNull final EmbeddedNode node) {
        super(node);
        platform = new SynchronousFakePlatform(defaultNodeId, executorService, metrics);
    }

    @Override
    protected AbstractFakePlatform fakePlatform() {
        return platform;
    }

    @Override
    public Instant now() {
        return time.now();
    }

    @Override
    public void tick(@NonNull Duration duration) {
        time.tick(duration);
    }

    @Override
    public TransactionResponse submit(
            @NonNull Transaction transaction,
            @NonNull AccountID nodeAccountId,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> preHandleCallback,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> handleCallback) {
        this.preHandleStateSignatureCallback = preHandleCallback;
        this.handleStateSignatureCallback = handleCallback;
        final var response = submit(transaction, nodeAccountId);
        this.preHandleStateSignatureCallback = NO_OP_CALLBACK;
        this.handleStateSignatureCallback = NO_OP_CALLBACK;
        return response;
    }

    @Override
    public TransactionResponse submit(Transaction transaction, AccountID nodeAccountId, final long eventBirthRound) {
        var response = OK_RESPONSE;
        final Bytes payload = Bytes.wrap(transaction.toByteArray());
        if (defaultNodeAccountId.equals(nodeAccountId)) {
            final var responseBuffer = BufferedData.allocate(MAX_PLATFORM_TXN_SIZE);
            hedera.ingestWorkflow().submitTransaction(payload, responseBuffer);
            response = parseTransactionResponse(responseBuffer);
        } else {
            final var nodeId = nodeIds.getOrDefault(nodeAccountId, MISSING_NODE_ID);
            warnOfSkippedIngestChecks(nodeAccountId, nodeId);
            platform.lastCreatedEvent =
                    new FakeEvent(nodeId, time.now(), createAppPayloadWrapper(payload), eventBirthRound);
        }
        return handleNextRounds(response);
    }

    @Override
    public TransactionResponse submit(
            @NonNull final Transaction transaction,
            @NonNull final AccountID nodeAccountId,
            @NonNull final SemanticVersion semanticVersion) {
        requireNonNull(transaction);
        requireNonNull(nodeAccountId);
        requireNonNull(semanticVersion);
        var response = OK_RESPONSE;
        final Bytes payload = Bytes.wrap(transaction.toByteArray());
        if (defaultNodeAccountId.equals(nodeAccountId)) {
            final var responseBuffer = BufferedData.allocate(MAX_PLATFORM_TXN_SIZE);
            hedera.ingestWorkflow().submitTransaction(payload, responseBuffer);
            response = parseTransactionResponse(responseBuffer);
        } else {
            final var nodeId = nodeIds.getOrDefault(nodeAccountId, MISSING_NODE_ID);
            warnOfSkippedIngestChecks(nodeAccountId, nodeId);
            platform.lastCreatedEvent = new FakeEvent(nodeId, time.now(), createAppPayloadWrapper(payload));
        }
        return handleNextRounds(response);
    }

    @Override
    protected long validStartOffsetSecs() {
        // We handle each transaction in a round starting in the next second of fake consensus time, so
        // we don't need any offset here; this simplifies tests that validate purging expired receipts
        return 0L;
    }

    /**
     * Returns the block hash signer for this embedded node that can be told to lapse into an unresponsive state
     * and start ignoring signature requests.
     */
    public LapsingBlockHashSigner blockHashSigner() {
        return blockHashSigner;
    }

    /**
     * Returns the last consensus round number.
     */
    public long lastRoundNo() {
        return platform.lastRoundNo();
    }

    /**
     * Sets the duration of each simulated consensus round, and hence the consensus time that will
     * elapse before the next transaction is handled.
     * @param roundDuration the duration of each simulated round
     */
    public void setRoundDuration(@NonNull final Duration roundDuration) {
        this.roundDuration = requireNonNull(roundDuration);
    }

    @Override
    protected void handleRoundWith(@NonNull final byte[] serializedTxn) {
        final var round = platform.roundWith(serializedTxn);
        hedera.onPreHandle(round.iterator().next(), state, preHandleStateSignatureCallback);
        hedera.handleWorkflow().handleRound(state, round, handleStateSignatureCallback);
        hedera.onSealConsensusRound(round, state);
        notifyStateHashed(round.getRoundNum());
    }

    /**
     * Executes the transaction in the last-created event within its own round.
     */
    public void handleNextRoundIfPresent() {
        if (platform.lastCreatedEvent != null) {
            hedera.onPreHandle(platform.lastCreatedEvent, state, preHandleStateSignatureCallback);
            final var round = platform.nextConsensusRound();
            // Handle each transaction in own round
            hedera.handleWorkflow().handleRound(state, round, handleStateSignatureCallback);
            hedera.onSealConsensusRound(round, state);
            notifyStateHashed(round.getRoundNum());
        }
    }

    /**
     * Handles a round with no user transactions.
     */
    public void handleRoundWithNoUserTransactions() {
        handleRoundWith(mockStateSignatureTxn());
    }

    private class SynchronousFakePlatform extends AbstractFakePlatform implements Platform {
        private FakeEvent lastCreatedEvent;

        public SynchronousFakePlatform(
                @NonNull final NodeId selfId,
                @NonNull final ScheduledExecutorService executorService,
                @NonNull final Metrics metrics) {
            super(selfId, roster, executorService, metrics);
        }

        @Override
        public boolean createTransaction(@NonNull final byte[] transaction) {
            lastCreatedEvent = new FakeEvent(defaultNodeId, time.now(), createAppPayloadWrapper(transaction));
            return true;
        }

        @Override
        public void start() {
            // No-op
        }

        @Override
        public void destroy() throws InterruptedException {
            getMetricsProvider().removePlatformMetrics(platform.getSelfId());
        }

        /**
         * Creates a new round with the given transaction.
         * @param serializedTxn the serialized transaction
         * @return the new round
         */
        private Round roundWith(@NonNull final byte[] serializedTxn) {
            time.tick(roundDuration);
            final var firstRoundTime = time.now();
            return new FakeRound(
                    roundNo.getAndIncrement(),
                    requireNonNull(roster),
                    List.of(new FakeConsensusEvent(
                            new FakeEvent(defaultNodeId, firstRoundTime, createAppPayloadWrapper(serializedTxn)),
                            consensusOrder.getAndIncrement(),
                            firstRoundTime)));
        }

        private Round nextConsensusRound() {
            time.tick(roundDuration);
            final var firstRoundTime = time.now();
            final var consensusEvents = List.<ConsensusEvent>of(new FakeConsensusEvent(
                    requireNonNull(lastCreatedEvent), consensusOrder.getAndIncrement(), firstRoundTime));
            return new FakeRound(roundNo.getAndIncrement(), requireNonNull(roster), consensusEvents);
        }
    }

    @NonNull
    private TransactionResponse handleNextRounds(final TransactionResponse response) {
        if (response.getNodeTransactionPrecheckCode() == OK) {
            handleNextRoundIfPresent();
            // If handling this transaction scheduled node transactions, handle them now
            while (!pendingNodeSubmissions.isEmpty()) {
                platform.lastCreatedEvent = null;
                pendingNodeSubmissions.poll().run();
                if (platform.lastCreatedEvent != null) {
                    handleNextRoundIfPresent();
                }
            }
        }
        return response;
    }
}
