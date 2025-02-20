// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.config;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * This class holds static factory methods to create suppliers of {@link TransactionRequestConfig}.
 */
public final class TransactionRequestSupplierFactory {

    /**
     * This method decides which instance of {@code Supplier<TransactionRequestConfig>} must be
     * used, based on the given {@code virtualMerkleConfig}.
     *
     * @param virtualMerkleConfig
     * 		The configurations to create the requests of transactions.
     * @return A supplier of {@link TransactionRequestConfig}.
     */
    public static final Supplier<TransactionRequestConfig> create(final VirtualMerkleConfig virtualMerkleConfig) {
        final List<TransactionRequestConfig> requestConfigs = virtualMerkleConfig.getSequential().stream()
                .filter(config -> config.getAmount() > 0)
                .map(TransactionRequestConfig::new)
                .collect(Collectors.toList());
        if (virtualMerkleConfig.isAssorted()) {
            return new AssortedTransactionRequestSupplier(requestConfigs);
        }
        return new SequentialTransactionRequestSupplier(requestConfigs);
    }
}
