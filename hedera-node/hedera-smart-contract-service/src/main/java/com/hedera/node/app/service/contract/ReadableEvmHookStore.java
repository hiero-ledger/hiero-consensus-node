// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract;

import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with Evm Hooks.
 */
public interface ReadableEvmHookStore {
    /**
     * Returns the EvmHookState for a given HookId.
     * @param hookId the HookId being looked up
     * @return the EvmHookState or null if not found
     */
    @Nullable
    EvmHookState getEvmHook(@NonNull final HookId hookId);
}
