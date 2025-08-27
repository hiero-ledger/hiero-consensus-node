// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.consensus;

import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.RECEIPT_STORAGE_TIME_SEC;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.TX_HASH_SIZE;

import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class ConsensusOpsUsage {
    private static final long LONG_BASIC_ENTITY_ID_SIZE = BASIC_ENTITY_ID_SIZE;

    @Inject
    public ConsensusOpsUsage() {
        /* No-op */
    }

    public void submitMessageUsage(
            final SigUsage sigUsage,
            final SubmitMessageMeta submitMeta,
            final BaseTransactionMeta baseMeta,
            final UsageAccumulator accumulator) {
        accumulator.resetForTransaction(baseMeta, sigUsage);
        if (submitMeta.numCustomFees() > 0) {
            accumulator.addVpt(Math.max(0, sigUsage.numSigs() - 1));
            accumulator.addBpt((LONG_BASIC_ENTITY_ID_SIZE + submitMeta.numMsgBytes() + 475) / 500);
        } else {
            accumulator.addBpt(LONG_BASIC_ENTITY_ID_SIZE + submitMeta.numMsgBytes());
            /* SubmitMessage receipts include a sequence number and running hash */
            final var extraReceiptBytes = LONG_SIZE + TX_HASH_SIZE;
            accumulator.addNetworkRbs(extraReceiptBytes * RECEIPT_STORAGE_TIME_SEC);
        }
    }
}
