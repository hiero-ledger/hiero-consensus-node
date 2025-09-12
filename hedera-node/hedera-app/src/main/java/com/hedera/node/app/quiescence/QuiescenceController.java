package com.hedera.node.app.quiescence;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.transaction.Transaction;

public class QuiescenceController {
    private final AtomicLong pipelineTransactionCount = new AtomicLong(0);

    public void fullySignedBlock(@NonNull final Block block) {
        final long transactionCount = block.items().stream()
                .filter(BlockItem::hasSignedTransaction)
                .map(BlockItem::signedTransaction)
                .filter(Objects::nonNull)
                .map(uncheckedParse(SignedTransaction.PROTOBUF))
                .map(SignedTransaction::bodyBytes)
                .map(uncheckedParse(TransactionBody.PROTOBUF))
                .filter(QuiescenceController::nonSignatureTransactions)
                .count();
        final long updatedValue = pipelineTransactionCount.addAndGet(transactionCount);
        if (updatedValue < 0) {
            System.out.println("error");
        }
    }

    public void onPreHandle(@NonNull final Event event){
        final Iterator<Transaction> iterator = event.transactionIterator();
        long transactionCount = 0;
        while (iterator.hasNext()){

            if (nonSignatureTransactions(
                    uncheckedParse(TransactionBody.PROTOBUF).apply(iterator.next().getApplicationTransaction()))) {
                transactionCount++;
            }
        }
        pipelineTransactionCount.addAndGet(transactionCount);
    }

    private static boolean nonSignatureTransactions(@NonNull final TransactionBody body) {
        return !body.hasStateSignatureTransaction() &&
                !body.hasHintsPartialSignature();
    }

    private static <T> Function<Bytes, T> uncheckedParse(@NonNull final Codec<T> codec) {
        return function -> {
            try {
                return codec.parse(function);
            } catch (final ParseException e) {
                throw new RuntimeException(e);
            }
        };
    }
}
