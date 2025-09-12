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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.transaction.Transaction;

public class QuiescenceController {
    private static final Function<Bytes, TransactionBody> TRANSACTION_BODY_PARSER = uncheckedParse(TransactionBody.PROTOBUF);
    private static final Function<Bytes, SignedTransaction> SIGNED_TRANSACTION_PARSER = uncheckedParse(SignedTransaction.PROTOBUF);
    private final AtomicLong pipelineTransactionCount = new AtomicLong(0);

    public void fullySignedBlock(@NonNull final Block block) {
        final long transactionCount = block.items().stream()
                .filter(BlockItem::hasSignedTransaction)
                .map(BlockItem::signedTransaction)
                .filter(Objects::nonNull)
                .map(SIGNED_TRANSACTION_PARSER)
                .map(SignedTransaction::bodyBytes)
                .map(TRANSACTION_BODY_PARSER)
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
                    TRANSACTION_BODY_PARSER.apply(iterator.next().getApplicationTransaction()))) {
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
        return bytes -> {
            try {
                return codec.parse(bytes);
            } catch (final ParseException e) {
                throw new RuntimeException(e);
            }
        };
    }
}
