// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import com.hedera.node.app.spi.workflows.record.StreamBuilder;

/**
 * Exposes the record customizations needed for a HAPI contract call transaction.
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
