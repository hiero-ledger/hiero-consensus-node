package org.hiero.telemetryconverter.model.combined;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.hiero.telemetryconverter.model.trace.TransactionTraceInfo;
import org.hiero.telemetryconverter.util.WarningException;

/**
 * Information about a transaction, extracted from the BlockItems and TransactionTraceInfo.
 */
@SuppressWarnings("DataFlowIssue")
public class TransactionInfo {
    private final int txHash; // TransactionID.hashCode() value
    private final List<TransactionTraceInfo> receivedTraces;
    private final List<TransactionTraceInfo> executedTraces;
    private final long transactionReceivedStartTimeNanos;
    private final long transactionReceivedEndTimeNanos;
    private final long transactionLastExecutionTimeNanos;

    public TransactionInfo(final List<BlockItem> transactionItems,
            final IntObjectHashMap<List<TransactionTraceInfo>> transactionTraces) {
        try {
            // parse the transaction body
            Bytes signedTransaction = transactionItems.getFirst().signedTransaction();
            SignedTransaction transaction = SignedTransaction.PROTOBUF.parse(signedTransaction);
            TransactionBody transactionBody = TransactionBody.PROTOBUF.parse(transaction.bodyBytes());
            // get the transaction hash code
            txHash = transactionBody.transactionID().hashCode();
            // find traces
            final var traces = transactionTraces.get(txHash);
            if (traces == null) {
                throw new WarningException("No transaction traces found in JFR files for transaction hash " + txHash);
            }
            receivedTraces = traces.stream()
                    .filter(t -> t.eventType() == TransactionTraceInfo.EventType.RECEIVED).toList();
            executedTraces = traces.stream()
                    .filter(t -> t.eventType() == TransactionTraceInfo.EventType.EXECUTED).toList();
            // find the earliest received time
            var receivedTimeStats = receivedTraces.stream()
                    .mapToLong(TransactionTraceInfo::startTimeNanos).summaryStatistics();
            transactionReceivedStartTimeNanos = receivedTimeStats.getMin();
            transactionReceivedEndTimeNanos = receivedTimeStats.getMax();
            // find the latest executed time
            transactionLastExecutionTimeNanos = executedTraces.stream()
                    .mapToLong(TransactionTraceInfo::endTimeNanos).max().orElse(0L);
        } catch (ParseException e) {
            System.err.println("Failed to parse signed transaction: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public int txHash() {
        return txHash;
    }

    public List<TransactionTraceInfo> receivedTraces() {
        return receivedTraces;
    }

    public List<TransactionTraceInfo> executedTraces() {
        return executedTraces;
    }

    public long transactionReceivedTimeNanos() {
        return transactionReceivedStartTimeNanos;
    }

    public long transactionReceivedEndTimeNanos() {
        return transactionReceivedEndTimeNanos;
    }

    public long transactionLastExecutionTimeNanos() {
        return transactionLastExecutionTimeNanos;
    }
}
