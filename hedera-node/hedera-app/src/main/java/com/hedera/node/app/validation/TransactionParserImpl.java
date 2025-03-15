// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.validation;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.validation.TransactionParser;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class TransactionParserImpl implements TransactionParser {

    private final TransactionChecker transactionChecker;

    @Inject
    public TransactionParserImpl(TransactionChecker transactionChecker) {
        this.transactionChecker = transactionChecker;
    }

    @Override
    public TransactionBody parseSigned(Bytes signedBytes) throws PreCheckException {
        return transactionChecker.parseSignedAndCheck(signedBytes).txBody();
    }
}
