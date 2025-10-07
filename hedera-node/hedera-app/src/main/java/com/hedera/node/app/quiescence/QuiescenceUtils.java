package com.hedera.node.app.quiescence;

import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

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
}
