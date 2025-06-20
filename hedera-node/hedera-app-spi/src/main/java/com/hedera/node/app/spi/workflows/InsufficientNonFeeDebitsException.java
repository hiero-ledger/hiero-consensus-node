// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An {@link InsufficientBalanceException} that exists only for backward
 * compatibility with historical network behavior.
 *
 * <p>It is thrown when the payer is willing and able to pay all the fees, but will then not
 * be able to afford the other debits they will incur in the transaction.
 *
 * <p>For example, if the payer balance is {@code 100 hbar}, and they sign a
 * {@link com.hedera.hapi.node.token.CryptoTransferTransactionBody} that debits {@code 100 hbar}
 * from their account, although they can clearly afford the transfer fee, after paying it
 * they will no longer be able to afford the transfer debit itself.
 *
 * <p>There is no compelling reason to treat this as a special case instead of letting the
 * downstream handler fail; but for historical reasons we continue to do so.
 */
public class InsufficientNonFeeDebitsException extends InsufficientBalanceException {
    public InsufficientNonFeeDebitsException(@NonNull ResponseCodeEnum responseCode, long estimatedFee) {
        super(responseCode, estimatedFee);
    }
}
