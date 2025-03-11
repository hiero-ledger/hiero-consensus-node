// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.config;

import com.swirlds.demo.platform.PAYLOAD_TYPE;
import com.swirlds.demo.virtualmerkle.random.PTTRandom;
import java.util.List;
import java.util.function.Supplier;

/**
 * The responsibility of this class is to supply {@link TransactionRequestConfig} instances in
 * a random order.
 */
public final class AssortedTransactionRequestSupplier implements Supplier<TransactionRequestConfig> {

    private final List<TransactionRequestConfig> requestConfigs;
    private final PTTRandom random;
    private TransactionRequestConfig expectedMapTransactionRequest;

    public AssortedTransactionRequestSupplier(final List<TransactionRequestConfig> requestConfigs) {
        this.requestConfigs = requestConfigs;
        this.random = new PTTRandom();
    }

    @Override
    public TransactionRequestConfig get() {
        if (requestConfigs.isEmpty()) {
            // we return the current expected map transaction and mark it as null,
            // so the next time the get() method is executed we are going to return null,
            // hence finish the creation of transactions.
            final TransactionRequestConfig t = expectedMapTransactionRequest;
            expectedMapTransactionRequest = null;
            return t;
        }

        final int randomIndex = random.nextInt(requestConfigs.size());
        final TransactionRequestConfig transactionRequestConfig = requestConfigs.get(randomIndex);
        if (transactionRequestConfig.decrementAndGetAmount() == 0) {
            requestConfigs.remove(transactionRequestConfig);
        }

        // Just to make sure we only execute the SAVE_EXPECTED_MAP transaction at the end
        // if we select it during the process, it is skipped.
        if (transactionRequestConfig.getType() == PAYLOAD_TYPE.SAVE_EXPECTED_MAP) {
            expectedMapTransactionRequest = transactionRequestConfig;
            return get();
        }

        return transactionRequestConfig;
    }
}
