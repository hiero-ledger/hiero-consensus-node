// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * The result of decoding an HTS token create or update system contract call: the synthetic body to dispatch, and
 * optionally a status that pre-empts the dispatch.
 *
 * <p>A pre-empting status is set when the call input is well-formed but must not be dispatched, for example when a
 * {@link TokenKeyUtils token key} entry selects no defined key type. The caller then reports the status and
 * dispatches nothing, rather than letting the dispatch silently succeed on an input that changes nothing.
 *
 * @param body the synthetic body to dispatch, or null if the call cannot be decoded
 * @param preemptingStatus if not null, the status to report instead of dispatching the body
 */
public record TokenTransactionBodyWrapper(
        @Nullable TransactionBody body, @Nullable ResponseCodeEnum preemptingStatus) {

    /**
     * A wrapper for a call that cannot be decoded, with neither a body nor a pre-empting status.
     */
    public static final TokenTransactionBodyWrapper EMPTY = new TokenTransactionBodyWrapper(null, null);
}
