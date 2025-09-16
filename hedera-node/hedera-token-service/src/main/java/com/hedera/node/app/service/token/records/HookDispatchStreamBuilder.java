// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import com.hedera.node.app.spi.workflows.record.StreamBuilder;

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
    void nextHookId(long nextHookId);

    /**
     * Returns the next hook id to be used
     *
     * @return the next hook id
     */
    long getNextHookId();
}
