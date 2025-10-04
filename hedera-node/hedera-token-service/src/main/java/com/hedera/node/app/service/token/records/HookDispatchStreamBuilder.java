// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;

/**
 * A {@link StreamBuilder} that can record the next hook ID to be used in the stream.
 * This information is not used in record stream or block stream. But is useful for
 * updating the first hookId in the {@link com.hedera.hapi.node.state.hooks.EvmHookState}
 * after a series of hook deletions in a transaction.
 */
public interface HookDispatchStreamBuilder extends StreamBuilder {
    /**
     * Returns the  first hook id after this dispatch
     */
    void nextHookId(Long nextHookId);

    /**
     * Returns the next hook id to be used
     *
     * @return the next hook id
     */
    Long getNextHookId();

    /**
     * Returns the EVM transaction call result to be recorded in the record stream
     *
     * @return the EVM transaction call result
     */
    Bytes getEvmCallResult();
}
