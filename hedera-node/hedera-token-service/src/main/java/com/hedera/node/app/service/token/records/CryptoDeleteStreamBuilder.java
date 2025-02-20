// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import com.hedera.node.app.spi.workflows.record.DeleteCapableTransactionStreamBuilder;

/**
 * A {@code StreamBuilder} specialization for tracking the side effects of a {@code CryptoDelete}
 * transaction.
 */
public interface CryptoDeleteStreamBuilder extends DeleteCapableTransactionStreamBuilder {}
