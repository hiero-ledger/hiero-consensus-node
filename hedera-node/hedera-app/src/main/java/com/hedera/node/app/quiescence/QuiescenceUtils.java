package com.hedera.node.app.quiescence;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import org.hiero.consensus.model.transaction.Transaction;

public final class QuiescenceUtils {
    private QuiescenceUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static boolean isRelevantTransaction(@Nullable final TransactionInfo txInfo) {
        if (txInfo == null) {
            // This is most likely an unparsable transaction.
            // An unparsable transaction is considered relevant because it needs to reach consensus so that the node
            // that submitted it can be changed for it.
            return true;
        }
        return isRelevantTransaction(txInfo.txBody());
    }

    public static boolean isRelevantTransaction(@NonNull final TransactionBody body) {
        return !body.hasStateSignatureTransaction() && !body.hasHintsPartialSignature();
    }

    public static boolean isRelevantTransaction(@NonNull final Transaction txn) throws BadMetadataException {
        if (!(txn.getMetadata() instanceof final PreHandleResult preHandleResult)) {
            throw new BadMetadataException(txn);
        }
        return isRelevantTransaction(preHandleResult.txInfo());
    }

    public static long countRelevantTransactions(@NonNull final Iterator<Transaction> transactions)
            throws BadMetadataException {
        long count = 0;
        while (transactions.hasNext()) {
            if (isRelevantTransaction(transactions.next())) {
                count++;
            }
        }
        return count;
    }
}
