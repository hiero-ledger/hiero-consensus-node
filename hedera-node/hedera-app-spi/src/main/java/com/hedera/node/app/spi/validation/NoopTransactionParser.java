// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.validation;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;

public enum NoopTransactionParser implements TransactionParser {
    NOOP_TRANSACTION_PARSER;

    @Override
    public TransactionBody parseSigned(Bytes signedBytes) {
        return TransactionBody.DEFAULT;
    }
}
