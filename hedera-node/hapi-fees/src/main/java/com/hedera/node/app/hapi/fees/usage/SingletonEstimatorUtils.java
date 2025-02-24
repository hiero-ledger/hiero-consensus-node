// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage;

import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ACCOUNT_AMT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_RECEIPT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_TX_RECORD_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.FEE_MATRICES_CONST;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.INT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.RECEIPT_STORAGE_TIME_SEC;

import com.hedera.hapi.node.base.FeeComponents;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;

public enum SingletonEstimatorUtils implements EstimatorUtils {
    ESTIMATOR_UTILS;

    @Override
    public long baseNetworkRbs() {
        return BASIC_RECEIPT_SIZE * RECEIPT_STORAGE_TIME_SEC;
    }

    @Override
    public UsageEstimate baseEstimate(TransactionBody txn, SigUsage sigUsage) {
        var base = FeeComponents.newBuilder()
                .bpr(INT_SIZE)
                .vpt(sigUsage.numSigs())
                .bpt(baseBodyBytes(txn) + sigUsage.sigsSize());
        var estimate = new UsageEstimate(base);
        estimate.addRbs(baseRecordBytes(txn) * RECEIPT_STORAGE_TIME_SEC);
        return estimate;
    }

    public FeeData withDefaultTxnPartitioning(FeeComponents usage, SubType subType, long networkRbh, int numPayerKeys) {
        var usages = FeeData.newBuilder();

        var network = FeeComponents.newBuilder()
                .constant(FEE_MATRICES_CONST)
                .bpt(usage.bpt())
                .vpt(usage.vpt())
                .rbh(networkRbh);
        var node = FeeComponents.newBuilder()
                .constant(FEE_MATRICES_CONST)
                .bpt(usage.bpt())
                .vpt(numPayerKeys)
                .bpr(usage.bpr())
                .sbpr(usage.sbpr());
        var service = FeeComponents.newBuilder()
                .constant(FEE_MATRICES_CONST)
                .rbh(usage.rbh())
                .sbh(usage.sbh())
                .tv(usage.tv());
        return usages.networkdata(network)
                .nodedata(node)
                .servicedata(service)
                .subType(subType)
                .build();
    }

    @Override
    public FeeData withDefaultQueryPartitioning(FeeComponents usage) {
        var usages = FeeData.newBuilder();
        var node = FeeComponents.newBuilder()
                .constant(FEE_MATRICES_CONST)
                .bpt(usage.bpt())
                .bpr(usage.bpr())
                .sbpr(usage.sbpr());
        return usages.nodedata(node).build();
    }

    int baseRecordBytes(TransactionBody txn) {
        return BASIC_TX_RECORD_SIZE
                + txn.memo().getBytes().length
                + transferListBytes(txn.cryptoTransferOrElse(CryptoTransferTransactionBody.DEFAULT)
                        .transfersOrElse(TransferList.DEFAULT));
    }

    private int transferListBytes(TransferList transfers) {
        return BASIC_ACCOUNT_AMT_SIZE * transfers.accountAmounts().size();
    }
}
