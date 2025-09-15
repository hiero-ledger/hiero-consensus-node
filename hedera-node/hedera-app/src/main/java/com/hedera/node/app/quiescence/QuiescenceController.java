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
import org.hiero.consensus.model.transaction.Transaction;

public class QuiescenceController {
    private static final Logger logger = LogManager.getLogger(QuiescenceController.class);

    private final QuiescenceConfig config;
    private final Time time;
    private final LongSupplier pendingTransactionCount;

    private final Function<Bytes, TransactionBody> transactionBodyParser;
    private final Function<Bytes, SignedTransaction> signedTransactionParser;
    private final AtomicReference<Instant> nextTct;
    private final AtomicLong pipelineTransactionCount;


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

    public void fullySignedBlock(@NonNull final Block block) {
        if(!config.enabled()){
            return;
        }
        final long transactionCount = block.items().stream()
                .filter(BlockItem::hasSignedTransaction)
                .map(BlockItem::signedTransaction)
                .filter(Objects::nonNull)
                .map(signedTransactionParser)
                .map(SignedTransaction::bodyBytes)
                .map(transactionBodyParser)
                .filter(QuiescenceController::nonSignatureTransactions)
                .count();
        final long updatedValue = pipelineTransactionCount.addAndGet(transactionCount);
        if (updatedValue < 0) {
            logger.error("Quiescence transaction count overflow, turning off quiescence");
            disableQuiescence();
        }
    }

    public void onPreHandle(@NonNull final Event event) {
        if(!config.enabled()){
            return;
        }
        final Iterator<Transaction> iterator = event.transactionIterator();
        long transactionCount = 0;
        while (iterator.hasNext()) {

            if (nonSignatureTransactions(
                    transactionBodyParser.apply(iterator.next().getApplicationTransaction()))) {
                transactionCount++;
            }
        }
        pipelineTransactionCount.addAndGet(transactionCount);
    }

    public void staleEvent(){
        //TODO
    }
    public void platformStatusUpdate(){
    //TODO
    }

    public QuiescenceStatus getQuiescenceStatus() {
        if(!config.enabled()){
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

    private static boolean nonSignatureTransactions(@NonNull final TransactionBody body) {
        return !body.hasStateSignatureTransaction() &&
                !body.hasHintsPartialSignature();
    }


}
