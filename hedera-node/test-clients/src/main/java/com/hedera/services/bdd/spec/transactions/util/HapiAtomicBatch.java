// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.util;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.extractTxnId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.txnToString;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.common.base.MoreObjects;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoCreateMeta;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.fees.AdapterUtils;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.AtomicBatchTransactionBody;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiAtomicBatch extends HapiTxnOp<HapiAtomicBatch> {
    private static final Logger log = LogManager.getLogger(HapiAtomicBatch.class);
    private static final String DEFAULT_NODE_ACCOUNT_ID = "0.0.0";
    private final List<HapiTxnOp<?>> operationsToBatch = new ArrayList<>();
    private final Map<TransactionID, HapiTxnOp<?>> innerOpsByTxnId = new HashMap<>();
    private final Map<TransactionID, Transaction> innerTnxsByTxnId = new HashMap<>();
    private final List<String> txnIdsForOrderValidation = new ArrayList<>();

    public HapiAtomicBatch() {}

    public HapiAtomicBatch(HapiTxnOp<?>... ops) {
        this.operationsToBatch.addAll(Arrays.stream(ops).toList());
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.AtomicBatch;
    }

    @Override
    protected HapiAtomicBatch self() {
        return this;
    }

    @Override
    protected long feeFor(final HapiSpec spec, final Transaction txn, final int numPayerKeys) throws Throwable {
        return spec.fees().forActivityBasedOp(HederaFunctionality.AtomicBatch, this::usageEstimate, txn, numPayerKeys);
    }

    private FeeData usageEstimate(final TransactionBody txn, final SigValueObj svo) {
        final var baseMeta = new BaseTransactionMeta(txn.getMemoBytes().size(), 0);
        final var opMeta = new CryptoCreateMeta(txn.getCryptoCreateAccount());
        final var accumulator = new UsageAccumulator();
        cryptoOpsUsage.cryptoCreateUsage(suFrom(svo), baseMeta, opMeta, accumulator);
        return AdapterUtils.feeDataFrom(accumulator);
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final AtomicBatchTransactionBody opBody = spec.txns()
                .<AtomicBatchTransactionBody, AtomicBatchTransactionBody.Builder>body(
                        AtomicBatchTransactionBody.class, b -> {
                            for (HapiTxnOp<?> op : operationsToBatch) {
                                try {
                                    // set node account id to 0.0.0 if not set
                                    if (op.getNode().isEmpty()) {
                                        op.setNode(DEFAULT_NODE_ACCOUNT_ID);
                                    }
                                    // create a transaction for each operation
                                    final var transaction = op.signedTxnFor(spec);
                                    if (!loggingOff) {
                                        log.info(
                                                "{} add inner transaction to batch - {}",
                                                spec.logPrefix(),
                                                txnToString(transaction));
                                    }
                                    // save transaction id and transaction
                                    final var txnId = extractTxnId(transaction);
                                    innerOpsByTxnId.put(txnId, op);
                                    innerTnxsByTxnId.put(txnId, transaction);

                                    // add the transaction to the batch
                                    b.addTransactions(transaction.getSignedTransactionBytes());
                                } catch (Throwable e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        });
        return b -> b.setAtomicBatch(opBody);
    }

    @Override
    public void setTransactionSubmitted(final Transaction txn) {
        // Set the submitted outer (batch) transaction
        this.txnSubmitted = txn;

        // For each of the included operations, also set the submitted transaction
        this.innerOpsByTxnId.forEach(
                (transactionID, hapiTxnOp) -> hapiTxnOp.setTransactionSubmitted(innerTnxsByTxnId.get(transactionID)));
    }

    @Override
    protected void maybeRegisterTxnSubmitted(final HapiSpec spec) throws Throwable {
        super.maybeRegisterTxnSubmitted(spec);

        for (final var entry : innerTnxsByTxnId.entrySet()) {
            final var op = innerOpsByTxnId.get(entry.getKey());
            if (op != null && op.shouldRegisterTxn()) {
                HapiSpecOperation.registerTransaction(spec, op.getTxnName(), entry.getValue());
            }
        }
    }

    @Override
    public void updateStateOf(HapiSpec spec) throws Throwable {
        if (actualStatus == SUCCESS) {
            for (Map.Entry<TransactionID, HapiTxnOp<?>> entry : innerOpsByTxnId.entrySet()) {
                TransactionID txnId = entry.getKey();
                HapiTxnOp<?> op = entry.getValue();

                final HapiGetTxnRecord recordQuery =
                        getTxnRecord(txnId).noLogging().assertingNothing();
                final Optional<Throwable> error = recordQuery.execFor(spec);
                if (error.isPresent()) {
                    throw error.get();
                }
                op.updateStateFromRecord(recordQuery.getResponseRecord(), spec);
            }
        }

        // validate execution order of specific transactions
        validateExecutionOrder(spec, txnIdsForOrderValidation);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)));
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("range", operationsToBatch);
    }

    public HapiAtomicBatch validateTxnOrder(String... txnIds) {
        txnIdsForOrderValidation.addAll(Arrays.asList(txnIds));
        return this;
    }

    private void validateExecutionOrder(HapiSpec spec, List<String> transactionIds) throws Throwable {
        for (int i = 0; i < transactionIds.size() - 1; i++) {
            final var txnId1 = spec.registry().getTxnId(transactionIds.get(i));
            final var txnId2 = spec.registry().getTxnId(transactionIds.get(i + 1));

            if (txnId1 == null || txnId2 == null) {
                throw new IllegalArgumentException("Invalid transaction id to validate execution order");
            }
            final var record1 = getTxnRecord(txnId1).noLogging().assertingNothing();
            final var record2 = getTxnRecord(txnId2).noLogging().assertingNothing();

            final var error1 = record1.execFor(spec);
            final var error2 = record2.execFor(spec);

            if (error1.isPresent()) {
                throw error1.get();
            }

            if (error2.isPresent()) {
                throw error2.get();
            }

            final var consensus1 = record1.getResponseRecord().getConsensusTimestamp();
            final var consensus2 = record2.getResponseRecord().getConsensusTimestamp();

            // throw if second consensus is before the first
            // 1. compare seconds
            if (consensus2.getSeconds() < consensus1.getSeconds()) {
                throw new IllegalArgumentException("Invalid execution order");
            }
            // 2. compare nanos
            if (consensus2.getNanos() <= consensus1.getNanos()) {
                throw new IllegalArgumentException("Invalid execution order");
            }
        }
    }
}
