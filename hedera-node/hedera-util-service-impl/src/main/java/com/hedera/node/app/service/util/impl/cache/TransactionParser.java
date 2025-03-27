// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl.cache;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;

@FunctionalInterface
public interface TransactionParser {
    TransactionBody parse(Bytes signedTxn, Configuration config) throws PreCheckException;
}
