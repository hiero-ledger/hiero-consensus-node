// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.prehandle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.SO_FAR_SO_GOOD;
import static java.util.Collections.emptySet;

import com.hedera.pbj.runtime.io.buffer.Bytes;

/**
 * Calculates the pre-handle result for an inner transaction in a batch.
 */
@FunctionalInterface
public interface BatchInnerTxnPreHandle {

    BatchInnerTxnPreHandle NOOP_BATCH_INNER_TXN_PREHANDLER = (txn, preHandleResult) ->
            new PreHandleResult(null, null, SO_FAR_SO_GOOD, OK, null, emptySet(), null, emptySet(), null, null, 0);

    /**
     * Pre-handle an inner transaction.
     *
     * @param innerTransaction the inner transaction bytes
     */
    PreHandleResult preHandle(Bytes innerTransaction, PreHandleResult previousResult);
}
