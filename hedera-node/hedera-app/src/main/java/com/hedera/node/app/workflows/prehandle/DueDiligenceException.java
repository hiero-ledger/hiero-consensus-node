// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.prehandle;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.workflows.TransactionInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A transaction that captures a node due diligence failure; including the
 * associated status and (if it was parseable) the information on the
 * offending transaction.
 */
public class DueDiligenceException extends PreCheckException {
    @Nullable
    private final TransactionInfo transactionInfo;

    public DueDiligenceException(
            @NonNull final ResponseCodeEnum responseCode, @Nullable TransactionInfo transactionInfo) {
        super(responseCode);
        this.transactionInfo = transactionInfo;
    }

    @Nullable
    public TransactionInfo txInfo() {
        return transactionInfo;
    }
}
