// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import edu.umd.cs.findbugs.annotations.Nullable;

// TODO Glib: update java doc
/**
 *
 * @param body             the synthetic body to dispatch, or null if the call cannot be decoded
 * @param preemptingStatus if not null, the status to report instead of dispatching the body
 */
public record TokenTransactionBodyWrapper(
        @Nullable TransactionBody body, @Nullable ResponseCodeEnum preemptingStatus) {

    public static final TokenTransactionBodyWrapper EMPTY = new TokenTransactionBodyWrapper(null, null);
}
