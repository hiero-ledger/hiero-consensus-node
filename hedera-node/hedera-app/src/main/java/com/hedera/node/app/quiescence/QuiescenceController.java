package com.hedera.node.app.quiescence;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.Transaction;

/**
 * Tracks all the information needed to determine if the system is quiescent or not.
 */
public class QuiescenceController {
    private static final Logger logger = LogManager.getLogger(QuiescenceController.class);

    private final QuiescenceConfig config;
    private final Time time;
    private final LongSupplier pendingTransactionCount;

    private final Function<Bytes, TransactionBody> transactionBodyParser;
    private final Function<Bytes, SignedTransaction> signedTransactionParser;
    private final AtomicReference<Instant> nextTct;
    private final AtomicLong pipelineTransactionCount;


    /**
     * Constructs a new quiescence controller.
     *
     * @param config                  the quiescence configuration
     * @param time                    the time source
     * @param pendingTransactionCount a supplier that provides the number of transactions submitted to the node but not
     *                                yet included put into an event
     */
    public QuiescenceController(
            final QuiescenceConfig config,
            final Time time,
            final LongSupplier pendingTransactionCount) {
        this.config = Objects.requireNonNull(config);
        this.time = Objects.requireNonNull(time);
        this.pendingTransactionCount = Objects.requireNonNull(pendingTransactionCount);
        transactionBodyParser = createParser(TransactionBody.PROTOBUF);
        signedTransactionParser = createParser(SignedTransaction.PROTOBUF);
        nextTct = new AtomicReference<>();
        pipelineTransactionCount = new AtomicLong(0);
    }

    /**
     * Notifies the controller that a block has been fully signed.
     *
     * @param block the fully signed block
     */
    public void fullySignedBlock(@NonNull final Block block) {
        if (!config.enabled()) {
            return;
        }
        final long transactionCount = block.items().stream()
                .filter(BlockItem::hasSignedTransaction)
                .map(BlockItem::signedTransaction)
                .filter(Objects::nonNull)
                .filter(this::isRelevantTransaction)
                .count();
        final long updatedValue = pipelineTransactionCount.addAndGet(-transactionCount);
        if (updatedValue < 0) {
            logger.error("Quiescence transaction count overflow, turning off quiescence");
            disableQuiescence();
        }
    }

    /**
     * Notifies the controller that an event has been and will be handled soon (if it doesn't become stale).
     *
     * @param event the event that will be handled
     */
    public void onPreHandle(@NonNull final Event event) {
        if (!config.enabled()) {
            return;
        }
        pipelineTransactionCount.addAndGet(countRelevantTransactions(event));
    }

    /**
     * Notifies the controller that an event has become stale and will not be handled.
     *
     * @param event the event that has become stale
     */
    public void staleEvent(@NonNull final Event event) {
        if (!config.enabled()) {
            return;
        }
        pipelineTransactionCount.addAndGet(-countRelevantTransactions(event));
    }

    /**
     * Notifies the controller of the next target consensus time.
     *
     * @param targetConsensusTime the next target consensus time
     */
    public void setNextTargetConsensusTime(@Nullable final Instant targetConsensusTime) {
        if (!config.enabled()) {
            return;
        }
        nextTct.set(targetConsensusTime);
    }

    /**
     * Notifies the controller that the platform status has changed.
     *
     * @param platformStatus the new platform status
     */
    public void platformStatusUpdate(@NonNull final PlatformStatus platformStatus) {
        if (platformStatus == PlatformStatus.RECONNECT_COMPLETE) {
            pipelineTransactionCount.set(0);
        }
    }

    /**
     * Returns the current quiescence status.
     *
     * @return the current quiescence status
     */
    public QuiescenceStatus getQuiescenceStatus() {
        if (!config.enabled()) {
            return QuiescenceStatus.NOT_QUIESCENT;
        }
        if (pipelineTransactionCount.get() > 0) {
            return QuiescenceStatus.NOT_QUIESCENT;
        }
        final Instant tct = nextTct.get();
        if (tct != null && tct.minus(config.tctDuration()).isBefore(time.now())) {
            return QuiescenceStatus.NOT_QUIESCENT;
        }
        if (pendingTransactionCount.getAsLong() > 0) {
            return QuiescenceStatus.BREAKING_QUIESCENCE;
        }
        return QuiescenceStatus.QUIESCENT;
    }

    private long countRelevantTransactions(@NonNull final Event event) {
        long count = 0;
        final Iterator<Transaction> iterator = event.transactionIterator();
        while (iterator.hasNext()) {
            if (isRelevantTransaction(iterator.next().getApplicationTransaction())) {
                count++;
            }
        }
        return count;
    }

    private <T> Function<Bytes, T> createParser(@NonNull final Codec<T> codec) {
        return bytes -> {
            try {
                return codec.parse(bytes);
            } catch (final ParseException e) {
                logger.error("Failed parsing transactions, turning off quiescence", e);
                disableQuiescence();
                return null;
            }
        };
    }

    private void disableQuiescence() {
        // setting to a very high value to effectively disable quiescence
        // if set to Long.MAX_VALUE, it may overflow and become negative
        pipelineTransactionCount.set(Long.MAX_VALUE / 2);
    }

    private boolean isRelevantTransaction(@NonNull final Bytes bytes) {
        final TransactionBody body = transactionBodyParser.apply(signedTransactionParser.apply(bytes)
                .bodyBytes());
        return !body.hasStateSignatureTransaction() &&
                !body.hasHintsPartialSignature();
    }
}
