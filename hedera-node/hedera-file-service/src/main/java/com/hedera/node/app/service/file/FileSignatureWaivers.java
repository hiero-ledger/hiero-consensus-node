// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.SigWaivers;

/**
 * Signature waivers needed for transactions in {@link FileService}.
 * */
public interface FileSignatureWaivers extends SigWaivers {
    /**
     * Advises if the target file's key must sign a given file update.
     *
     * @param fileUpdateTxn a file update transaction
     * @return whether the target account's key must sign
     */
    boolean areFileUpdateSignaturesWaived(TransactionBody fileUpdateTxn, AccountID payer);

    /**
     * Advises if the target file's key must sign a given file append.
     *
     * @param fileAppendTxn a file append transaction
     * @return whether the target account's key must sign
     */
    boolean areFileAppendSignaturesWaived(TransactionBody fileAppendTxn, AccountID payer);
}
