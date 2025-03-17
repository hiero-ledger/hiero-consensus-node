// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.validation;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.validation.TransactionParser;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class TransactionParserImpl implements TransactionParser {

    private final TransactionChecker transactionChecker;
    private final ConfigProvider configProvider;

    @Inject
    public TransactionParserImpl(TransactionChecker transactionChecker, ConfigProvider configProvider) {
        this.transactionChecker = transactionChecker;
        this.configProvider = configProvider;
    }

    @Override
    public TransactionBody parseSigned(Bytes signedBytes) throws PreCheckException {
        final var maxTxnBytes = configProvider.getConfiguration().getConfigData(HederaConfig.class).nodeTransactionMaxBytes();
        return transactionChecker.parseSignedAndCheck(signedBytes, maxTxnBytes).txBody();
    }
}
