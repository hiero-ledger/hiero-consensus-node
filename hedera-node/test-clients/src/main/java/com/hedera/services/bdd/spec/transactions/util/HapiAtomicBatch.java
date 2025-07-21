// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.util;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.extractTxnId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.txnToString;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.common.base.MoreObjects;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoCreateMeta;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.exceptions.HapiTxnCheckStateException;
import com.hedera.services.bdd.spec.fees.AdapterUtils;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.AtomicBatchTransactionBody;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiAtomicBatch extends HapiTxnOp<HapiAtomicBatch> {
    private static final Logger log = LogManager.getLogger(HapiAtomicBatch.class);
    private static final String DEFAULT_NODE_ACCOUNT_ID = "0.0.0";
    private final List<HapiTxnOp<?>> operationsToBatch = new ArrayList<>();
    private final Map<TransactionID, HapiTxnOp<?>> innerOpsByTxnId = new HashMap<>();
    private final Map<TransactionID, Transaction> innerTnxsByTxnId = new HashMap<>();

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
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        var result = super.submitOp(spec);

        if (!shouldResolveInnerTransactions()) {
            return result;
        }

        boolean hasInnerTxnFailed = false;
        for (final var op : operationsToBatch) {
            if (!op.shouldResolveStatus()) {
                continue;
            }

            if (!hasInnerTxnFailed) {
                configureDefaultExpectedStatus(op);
                resolveInnerTxnStatus(op, spec);
                if (!isInnerTxnSuccessful(op)) {
                    hasInnerTxnFailed = true;
                }
            } else {
                // When a previous inner transaction fails with its expected status, the batch stops execution.
                // Later operations won't be executed and should not have expected status configured.
                throwIfExpectedStatusSet(op);
            }
        }
        return result;
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
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("range", operationsToBatch);
    }

    /**
     * Determines whether inner transactions within this batch should have their status resolved.
     * <p>
     * Inner transaction status resolution is performed only when:
     * 1. The batch pre-check is expected to pass (OK status)
     * 2. The batch is expected to either succeed (SUCCESS) or contain failed inner transactions (INNER_TRANSACTION_FAILED)
     *
     * @return true if inner transaction status should be resolved, false otherwise
     */
    private boolean shouldResolveInnerTransactions() {
        return getExpectedPrecheck() == OK
                && (getExpectedStatus() == INNER_TRANSACTION_FAILED || getExpectedStatus() == SUCCESS);
    }

    private void throwIfExpectedStatusSet(final HapiTxnOp<?> op) {
        if (op.isExpectedStatusSet()) {
            String errorMessage = String.format(
                    "Invalid test configuration: Operation '%s' has an expected status configured, but it cannot be "
                            + "validated because a previous inner transaction failed with its expected status, causing the batch "
                            + "to terminate execution. Remove expected status from operations that follow an expected failure.",
                    op);
            log.error(errorMessage);
            throw new HapiTxnCheckStateException(errorMessage);
        }
    }

    private void configureDefaultExpectedStatus(final HapiTxnOp<?> op) {
        if (expectedStatus.isPresent() && expectedStatus.get() == INNER_TRANSACTION_FAILED) {
            if (!op.isExpectedStatusSet()) {
                op.hasKnownStatus(REVERTED_SUCCESS);
            }
        }
    }

    private void resolveInnerTxnStatus(final HapiTxnOp<?> op, HapiSpec spec) throws Throwable {
        op.setNode(fixNodeFor(spec).getAccountNum());
        try {
            op.resolveStatus(spec);
        } finally {
            op.setNode(DEFAULT_NODE_ACCOUNT_ID);
        }
    }

    private boolean isInnerTxnSuccessful(final HapiTxnOp<?> op) {
        return op.getActualStatus() == SUCCESS || op.getActualStatus() == REVERTED_SUCCESS;
    }
}
